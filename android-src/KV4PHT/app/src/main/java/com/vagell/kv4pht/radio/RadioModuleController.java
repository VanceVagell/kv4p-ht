/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.radio;

import static com.vagell.kv4pht.radio.Protocol.DRA818_12K5;
import static com.vagell.kv4pht.radio.Protocol.DRA818_25K;

/**
 * Owns the Android-side desired/applied radio state.
 * <p>
 * Protocol.Sender only knows how to write frames; this class decides what the
 * next desired-state snapshot should contain. Public getters expose the latest
 * firmware-reported state; setters mutate desired state that will be sent to
 * firmware.
 */
public class RadioModuleController {
    private interface DesiredStateChange {
        Protocol.HostDesiredState apply(Protocol.HostDesiredState state);
    }

    private static final int MAX_DESIRED_STATE_RETRIES = 3;
    private static final int DESIRED_DEVICE_FLAGS_MASK =
        Protocol.HOST_STATE_RADIO_CONFIG_VALID
            | Protocol.HOST_STATE_PTT_REQUESTED
            | Protocol.HOST_STATE_RX_AUDIO_OPEN
            | Protocol.HOST_STATE_HIGH_POWER
            | Protocol.HOST_STATE_RSSI_ENABLED
            | Protocol.HOST_STATE_FILTER_PRE
            | Protocol.HOST_STATE_FILTER_HIGH
            | Protocol.HOST_STATE_FILTER_LOW
            | Protocol.HOST_STATE_TX_ALLOWED;
    private static final int DEFAULT_DESIRED_FLAGS =
        Protocol.HOST_STATE_HIGH_POWER | Protocol.HOST_STATE_RSSI_ENABLED;

    private Protocol.Sender sender;
    private Protocol.FirmwareVersion firmwareVersion;
    private Protocol.HostDesiredState desiredState = Protocol.HostDesiredState.builder()
        .sequence(0)
        .memoryId(-1)
        .flags(DEFAULT_DESIRED_FLAGS)
        .bw(DRA818_25K)
        .freqTx(0.0f)
        .freqRx(0.0f)
        .ctcssTx((byte) 0)
        .squelch((byte) 0)
        .ctcssRx((byte) 0)
        .build();
    private int updateDepth = 0;
    private Protocol.HostDesiredState lastDesiredStateSent;
    private Protocol.DeviceState lastDeviceState;
    private boolean lastPhysPttDown = false;
    private boolean appliedStateInSync = false;
    private boolean transportReady = false;
    private int desiredStateRetries = 0;

    synchronized void attachSender(Protocol.Sender sender) {
        this.sender = sender;
        transportReady = false;
        lastDesiredStateSent = desiredState;
        desiredStateRetries = 0;
    }

    synchronized void markTransportReady() {
        transportReady = true;
        flushDesiredState();
    }

    synchronized void detachSender() {
        sender = null;
        transportReady = false;
        lastDesiredStateSent = null;
        lastDeviceState = null;
        firmwareVersion = null;
        appliedStateInSync = false;
        desiredStateRetries = 0;
    }

    synchronized void seedFirmwareVersion(Protocol.FirmwareVersion version) {
        firmwareVersion = version;
    }

    synchronized void clearFirmwareVersion() {
        firmwareVersion = null;
    }

    synchronized void seedFromDeviceState(Protocol.DeviceState state) {
        lastDeviceState = state;
        lastPhysPttDown = isPhysPttDown();
        desiredState = desiredFromDeviceState(state);
        lastDesiredStateSent = desiredState;
        appliedStateInSync = isDeviceStateInSyncWithDesired(state, lastDesiredStateSent);
        desiredStateRetries = 0;
    }

    synchronized void pttDown() {
        setDesiredFlag(Protocol.HOST_STATE_PTT_REQUESTED, true);
    }

    synchronized void pttUp() {
        setDesiredFlag(Protocol.HOST_STATE_PTT_REQUESTED, false);
    }

    public synchronized void beginUpdate() {
        updateDepth++;
    }

    public synchronized void endUpdate() {
        if (updateDepth == 0) {
            return;
        }
        updateDepth--;
        if (updateDepth == 0) {
            sendDesiredStateIfChanged();
        }
    }

    synchronized void setBandwidth(byte bandwidth) {
        updateRadioConfig(state -> state.withBw(bandwidth));
    }

    public synchronized void setBandwidth(String bandwidth) {
        setBandwidth("25kHz".equals(bandwidth) ? DRA818_25K : DRA818_12K5);
    }

    synchronized void setTxFrequency(float txFrequency) {
        updateRadioConfig(state -> state.withFreqTx(txFrequency));
    }

    synchronized void setRxFrequency(float rxFrequency) {
        updateRadioConfig(state -> state.withFreqRx(rxFrequency));
    }

    synchronized void setMemoryId(int memoryId) {
        updateRadioConfig(state -> state.withMemoryId(memoryId));
    }

    synchronized void setTxTone(byte txTone) {
        updateRadioConfig(state -> state.withCtcssTx(txTone));
    }

    synchronized void setSquelch(byte squelch) {
        updateRadioConfig(state -> state.withSquelch(squelch));
    }

    public synchronized void setSquelch(int squelch) {
        setSquelch((byte) squelch);
    }

    synchronized void seedDesiredSquelch(int squelch) {
        desiredState = desiredState.withSquelch((byte) squelch);
    }

    synchronized void setRxTone(byte rxTone) {
        updateRadioConfig(state -> state.withCtcssRx(rxTone));
    }

    public synchronized void setFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        int nextFlags = desiredState.getFlags() & ~(Protocol.HOST_STATE_FILTER_PRE | Protocol.HOST_STATE_FILTER_HIGH | Protocol.HOST_STATE_FILTER_LOW);
        if (emphasis) nextFlags |= Protocol.HOST_STATE_FILTER_PRE;
        if (highpass) nextFlags |= Protocol.HOST_STATE_FILTER_HIGH;
        if (lowpass) nextFlags |= Protocol.HOST_STATE_FILTER_LOW;
        int flags = nextFlags;
        updateDesiredState(state -> state.withFlags(flags));
    }

    synchronized void stop() {
        clearDesiredFlags(Protocol.HOST_STATE_RX_AUDIO_OPEN | Protocol.HOST_STATE_PTT_REQUESTED);
    }

    synchronized void openAudio() {
        setDesiredFlag(Protocol.HOST_STATE_RX_AUDIO_OPEN, true);
    }

    synchronized void closeAudio() {
        clearDesiredFlags(Protocol.HOST_STATE_RX_AUDIO_OPEN | Protocol.HOST_STATE_PTT_REQUESTED);
    }

    public synchronized void setHighPower(boolean isHighPower) {
        setDesiredFlag(Protocol.HOST_STATE_HIGH_POWER, isHighPower);
    }

    synchronized void setRssi(boolean on) {
        setDesiredFlag(Protocol.HOST_STATE_RSSI_ENABLED, on);
    }

    public synchronized void setTxAllowed(boolean allowed) {
        setDesiredFlag(Protocol.HOST_STATE_TX_ALLOWED, allowed);
    }

    synchronized void updateDeviceState(Protocol.DeviceState state) {
        lastPhysPttDown = isPhysPttDown();
        lastDeviceState = state;
        appliedStateInSync = isDeviceStateInSyncWithDesired(state, lastDesiredStateSent);
        if (appliedStateInSync) {
            desiredStateRetries = 0;
        } else {
            retryDesiredStateIfNeeded();
        }
    }

    synchronized boolean isAppliedStateInSync() {
        return appliedStateInSync;
    }

    public synchronized boolean isHighPowerEnabled() {
        return hasDesiredFlag(Protocol.HOST_STATE_HIGH_POWER);
    }

    synchronized boolean isRssiEnabled() {
        return hasDesiredFlag(Protocol.HOST_STATE_RSSI_ENABLED);
    }

    public synchronized boolean isTxAllowed() {
        return hasDesiredFlag(Protocol.HOST_STATE_TX_ALLOWED);
    }

    public synchronized int getDesiredSquelch() {
        return desiredState.getSquelch() & 0xFF;
    }

    public synchronized String getBandwidthLabel() {
        return desiredState.getBw() == DRA818_25K ? "25kHz" : "12.5kHz";
    }

    public synchronized boolean isPreEmphasisEnabled() {
        return hasDesiredFlag(Protocol.HOST_STATE_FILTER_PRE);
    }

    public synchronized boolean isHighpassEnabled() {
        return hasDesiredFlag(Protocol.HOST_STATE_FILTER_HIGH);
    }

    public synchronized boolean isLowpassEnabled() {
        return hasDesiredFlag(Protocol.HOST_STATE_FILTER_LOW);
    }

    public synchronized int getFirmwareVersionNumber() {
        return firmwareVersion != null ? firmwareVersion.getVer() : -1;
    }

    public synchronized Protocol.RfModuleType getRfModuleType() {
        return firmwareVersion != null ? firmwareVersion.getModuleType() : null;
    }

    public synchronized boolean hasHighLowPowerSwitch() {
        return firmwareVersion != null && firmwareVersion.isHasHl();
    }

    public synchronized boolean hasPhysPttButton() {
        return firmwareVersion != null && firmwareVersion.isHasPhysPtt();
    }

    public synchronized float getMinRadioFreq() {
        return firmwareVersion != null ? firmwareVersion.getMinRadioFreq() : 0.0f;
    }

    public synchronized float getMaxRadioFreq() {
        return firmwareVersion != null ? firmwareVersion.getMaxRadioFreq() : 999.0f;
    }

    synchronized float getHalfBandwidthMhz() {
        return (desiredState.getBw() == DRA818_25K ? 0.025f : 0.0125f) / 2.0f;
    }

    public synchronized boolean hasRadioConfig() {
        return lastDeviceState != null && lastDeviceState.hasRadioConfig();
    }

    public synchronized int getMemoryId() {
        return lastDeviceState != null ? lastDeviceState.getMemoryId() : -1;
    }

    public synchronized float getTxFrequency() {
        return lastDeviceState != null ? lastDeviceState.getFreqTx() : 0.0f;
    }

    public synchronized float getRxFrequency() {
        return lastDeviceState != null ? lastDeviceState.getFreqRx() : 0.0f;
    }

    public synchronized int getTxTone() {
        return lastDeviceState != null ? lastDeviceState.getCtcssTx() & 0xFF : 0;
    }

    public synchronized int getRxTone() {
        return lastDeviceState != null ? lastDeviceState.getCtcssRx() & 0xFF : 0;
    }

    synchronized int getSMeter9Value() {
        return Protocol.calculateSMeter9Value(lastDeviceState != null ? lastDeviceState.getLatestRssi() : 0);
    }

    synchronized boolean isPhysPttDown() {
        return hasDeviceFlag(Protocol.DEVICE_STATE_PHYS_PTT_DOWN);
    }

    synchronized boolean isSquelched() {
        return hasDeviceFlag(Protocol.DEVICE_STATE_SQUELCHED);
    }

    synchronized boolean isDeviceTxActive() {
        return lastDeviceState != null && Protocol.DeviceMode.DEVICE_MODE_TX.equals(lastDeviceState.getMode());
    }

    synchronized boolean didPhysPttChange() {
        return lastPhysPttDown != isPhysPttDown();
    }

    synchronized void flushDesiredState() {
        sendDesiredStateIfChanged();
    }

    private Protocol.HostDesiredState desiredFromDeviceState(Protocol.DeviceState state) {
        if (!state.hasRadioConfig()) {
            return Protocol.HostDesiredState.builder()
                .sequence(state.getAppliedSequence())
                .memoryId(-1)
                .flags(DEFAULT_DESIRED_FLAGS)
                .bw(DRA818_25K)
                .freqTx(0.0f)
                .freqRx(0.0f)
                .ctcssTx((byte) 0)
                .squelch((byte) 0)
                .ctcssRx((byte) 0)
                .build();
        }
        return Protocol.HostDesiredState.builder()
            .sequence(state.getAppliedSequence())
            .memoryId(state.getMemoryId())
            .flags(state.getFlags() & DESIRED_DEVICE_FLAGS_MASK & ~(Protocol.HOST_STATE_PTT_REQUESTED | Protocol.HOST_STATE_RX_AUDIO_OPEN))
            .bw(state.getBw())
            .freqTx(state.getFreqTx())
            .freqRx(state.getFreqRx())
            .ctcssTx(state.getCtcssTx())
            .squelch(state.getSquelch())
            .ctcssRx(state.getCtcssRx())
            .build();
    }

    private boolean isDeviceStateInSyncWithDesired(Protocol.DeviceState deviceState, Protocol.HostDesiredState desiredState) {
        if (deviceState == null || desiredState == null || deviceState.getLastError() != 0) {
            return false;
        }
        if (deviceState.getAppliedSequence() != desiredState.getSequence()) {
            return false;
        }
        if ((deviceState.getFlags() & DESIRED_DEVICE_FLAGS_MASK) != (desiredState.getFlags() & DESIRED_DEVICE_FLAGS_MASK)) {
            return false;
        }
        if ((desiredState.getFlags() & Protocol.HOST_STATE_RADIO_CONFIG_VALID) == 0) {
            return true;
        }
        return deviceState.getBw() == desiredState.getBw()
            && deviceState.getMemoryId() == desiredState.getMemoryId()
            && Float.compare(deviceState.getFreqTx(), desiredState.getFreqTx()) == 0
            && Float.compare(deviceState.getFreqRx(), desiredState.getFreqRx()) == 0
            && deviceState.getCtcssTx() == desiredState.getCtcssTx()
            && deviceState.getSquelch() == desiredState.getSquelch()
            && deviceState.getCtcssRx() == desiredState.getCtcssRx();
    }

    private void updateRadioConfig(DesiredStateChange change) {
        updateDesiredState(state -> {
            Protocol.HostDesiredState next = change.apply(state);
            return next.equals(state) ? state : next.withFlags(next.getFlags() | Protocol.HOST_STATE_RADIO_CONFIG_VALID);
        });
    }

    private void setDesiredFlag(int flag, boolean enabled) {
        updateDesiredState(state -> state.withFlags(enabled ? state.getFlags() | flag : state.getFlags() & ~flag));
    }

    private void clearDesiredFlags(int flags) {
        updateDesiredState(state -> state.withFlags(state.getFlags() & ~flags));
    }

    private void updateDesiredState(DesiredStateChange change) {
        Protocol.HostDesiredState next = change.apply(desiredState);
        if (!next.equals(desiredState)) {
            desiredState = next;
            sendDesiredStateIfChanged();
        }
    }

    private boolean hasDesiredFlag(int flag) {
        return (desiredState.getFlags() & flag) != 0;
    }

    private boolean hasDeviceFlag(int flag) {
        return lastDeviceState != null && (lastDeviceState.getFlags() & flag) != 0;
    }

    private void sendDesiredStateIfChanged() {
        if (updateDepth == 0 && sender != null && transportReady && !desiredState.equals(lastDesiredStateSent)) {
            sendDesiredState();
        }
    }

    private void sendDesiredState() {
        desiredState = desiredState.withSequence(desiredState.getSequence() + 1);
        lastDesiredStateSent = desiredState;
        desiredStateRetries = 0;
        appliedStateInSync = false;
        sender.sendDesiredState(desiredState);
    }

    private void retryDesiredStateIfNeeded() {
        if (sender == null || !transportReady || !desiredState.equals(lastDesiredStateSent) || desiredStateRetries >= MAX_DESIRED_STATE_RETRIES) {
            return;
        }
        desiredStateRetries++;
        sender.sendDesiredState(lastDesiredStateSent);
    }
}
