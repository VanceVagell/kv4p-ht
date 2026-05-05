package com.vagell.kv4pht.radio;

import static com.vagell.kv4pht.radio.Protocol.DRA818_25K;

/**
 * Owns the Android-side desired/applied radio state.
 *
 * Protocol.Sender only knows how to write frames; this class decides what the
 * next desired-state snapshot should contain.
 */
class RadioModuleController {
    private static final int DESIRED_DEVICE_FLAGS_MASK =
        Protocol.HOST_STATE_RADIO_CONFIG_VALID
            | Protocol.HOST_STATE_PTT_REQUESTED
            | Protocol.HOST_STATE_RX_AUDIO_OPEN
            | Protocol.HOST_STATE_HIGH_POWER
            | Protocol.HOST_STATE_RSSI_ENABLED
            | Protocol.HOST_STATE_FILTER_PRE
            | Protocol.HOST_STATE_FILTER_HIGH
            | Protocol.HOST_STATE_FILTER_LOW;

    private final Protocol.Sender sender;
    private final Protocol.HostDesiredState desiredState = Protocol.HostDesiredState.builder()
        .sequence(0)
        .flags(Protocol.HOST_STATE_HIGH_POWER | Protocol.HOST_STATE_RSSI_ENABLED)
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

    RadioModuleController(Protocol.Sender sender) {
        this.sender = sender;
    }

    synchronized void seedFromDeviceState(Protocol.DeviceState state) {
        lastDeviceState = state;
        lastPhysPttDown = isPhysPttDown();
        desiredState.setSequence(state.getAppliedSequence());
        desiredState.setFlags(state.getFlags() & DESIRED_DEVICE_FLAGS_MASK
            & ~(Protocol.HOST_STATE_PTT_REQUESTED | Protocol.HOST_STATE_RX_AUDIO_OPEN));
        desiredState.setBw(state.getBw());
        desiredState.setFreqTx(state.getFreqTx());
        desiredState.setFreqRx(state.getFreqRx());
        desiredState.setCtcssTx(state.getCtcssTx());
        desiredState.setSquelch(state.getSquelch());
        desiredState.setCtcssRx(state.getCtcssRx());
        lastDesiredStateSent = desiredState.copy();
        appliedStateInSync = isDeviceStateInSyncWithDesired(state, lastDesiredStateSent);
    }

    synchronized void pttDown() {
        setDesiredFlags(desiredState.getFlags() | Protocol.HOST_STATE_PTT_REQUESTED);
    }

    synchronized void pttUp() {
        setDesiredFlags(desiredState.getFlags() & ~Protocol.HOST_STATE_PTT_REQUESTED);
    }

    synchronized void beginUpdate() {
        updateDepth++;
    }

    synchronized void endUpdate() {
        if (updateDepth == 0) {
            return;
        }
        updateDepth--;
        if (updateDepth == 0) {
            sendDesiredStateIfChanged();
        }
    }

    synchronized void setBandwidth(byte bandwidth) {
        if (desiredState.getBw() == bandwidth) {
            return;
        }
        desiredState.setBw(bandwidth);
        setRadioConfigValidAndSend();
    }

    synchronized void setTxFrequency(float txFrequency) {
        if (Float.compare(desiredState.getFreqTx(), txFrequency) == 0) {
            return;
        }
        desiredState.setFreqTx(txFrequency);
        setRadioConfigValidAndSend();
    }

    synchronized void setRxFrequency(float rxFrequency) {
        if (Float.compare(desiredState.getFreqRx(), rxFrequency) == 0) {
            return;
        }
        desiredState.setFreqRx(rxFrequency);
        setRadioConfigValidAndSend();
    }

    synchronized void setTxTone(byte txTone) {
        if (desiredState.getCtcssTx() == txTone) {
            return;
        }
        desiredState.setCtcssTx(txTone);
        setRadioConfigValidAndSend();
    }

    synchronized void setSquelch(byte squelch) {
        if (desiredState.getSquelch() == squelch) {
            return;
        }
        desiredState.setSquelch(squelch);
        setRadioConfigValidAndSend();
    }

    synchronized void setRxTone(byte rxTone) {
        if (desiredState.getCtcssRx() == rxTone) {
            return;
        }
        desiredState.setCtcssRx(rxTone);
        setRadioConfigValidAndSend();
    }

    synchronized void setFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        int nextFlags = desiredState.getFlags() & ~(Protocol.HOST_STATE_FILTER_PRE | Protocol.HOST_STATE_FILTER_HIGH | Protocol.HOST_STATE_FILTER_LOW);
        if (emphasis) nextFlags |= Protocol.HOST_STATE_FILTER_PRE;
        if (highpass) nextFlags |= Protocol.HOST_STATE_FILTER_HIGH;
        if (lowpass) nextFlags |= Protocol.HOST_STATE_FILTER_LOW;
        setDesiredFlags(nextFlags);
    }

    synchronized void stop() {
        setDesiredFlags(desiredState.getFlags() & ~(Protocol.HOST_STATE_RX_AUDIO_OPEN | Protocol.HOST_STATE_PTT_REQUESTED));
    }

    synchronized void openAudio() {
        setDesiredFlags(desiredState.getFlags() | Protocol.HOST_STATE_RX_AUDIO_OPEN);
    }

    synchronized void closeAudio() {
        setDesiredFlags(desiredState.getFlags() & ~(Protocol.HOST_STATE_RX_AUDIO_OPEN | Protocol.HOST_STATE_PTT_REQUESTED));
    }

    synchronized void setHighPower(boolean isHighPower) {
        int nextFlags;
        if (isHighPower) {
            nextFlags = desiredState.getFlags() | Protocol.HOST_STATE_HIGH_POWER;
        } else {
            nextFlags = desiredState.getFlags() & ~Protocol.HOST_STATE_HIGH_POWER;
        }
        setDesiredFlags(nextFlags);
    }

    synchronized void setRssi(boolean on) {
        int nextFlags;
        if (on) {
            nextFlags = desiredState.getFlags() | Protocol.HOST_STATE_RSSI_ENABLED;
        } else {
            nextFlags = desiredState.getFlags() & ~Protocol.HOST_STATE_RSSI_ENABLED;
        }
        setDesiredFlags(nextFlags);
    }

    synchronized void updateDeviceState(Protocol.DeviceState state) {
        lastPhysPttDown = isPhysPttDown();
        lastDeviceState = state;
        appliedStateInSync = isDeviceStateInSyncWithDesired(state, lastDesiredStateSent);
    }

    synchronized Protocol.DeviceState getLastDeviceState() {
        return lastDeviceState;
    }

    synchronized Protocol.HostDesiredState getLastDesiredStateSent() {
        return lastDesiredStateSent;
    }

    synchronized boolean isAppliedStateInSync() {
        return appliedStateInSync;
    }

    synchronized boolean hasPendingDesiredState() {
        return lastDesiredStateSent != null && !appliedStateInSync;
    }

    synchronized boolean isHighPowerEnabled() {
        return (desiredState.getFlags() & Protocol.HOST_STATE_HIGH_POWER) != 0;
    }

    synchronized boolean isRssiEnabled() {
        return (desiredState.getFlags() & Protocol.HOST_STATE_RSSI_ENABLED) != 0;
    }

    synchronized byte getBandwidth() {
        return lastDeviceState != null ? lastDeviceState.getBw() : DRA818_25K;
    }

    synchronized float getTxFrequency() {
        return lastDeviceState != null ? lastDeviceState.getFreqTx() : 0.0f;
    }

    synchronized float getRxFrequency() {
        return lastDeviceState != null ? lastDeviceState.getFreqRx() : 0.0f;
    }

    synchronized byte getTxTone() {
        return lastDeviceState != null ? lastDeviceState.getCtcssTx() : 0;
    }

    synchronized byte getSquelch() {
        return lastDeviceState != null ? lastDeviceState.getSquelch() : 0;
    }

    synchronized byte getRxTone() {
        return lastDeviceState != null ? lastDeviceState.getCtcssRx() : 0;
    }

    synchronized int getSMeter9Value() {
        return Protocol.calculateSMeter9Value(lastDeviceState != null ? lastDeviceState.getLatestRssi() : 0);
    }

    synchronized boolean isPhysPttDown() {
        return lastDeviceState != null
            && (lastDeviceState.getFlags() & Protocol.DEVICE_STATE_PHYS_PTT_DOWN) != 0;
    }

    synchronized boolean isSquelched() {
        return lastDeviceState != null
            && (lastDeviceState.getFlags() & Protocol.DEVICE_STATE_SQUELCHED) != 0;
    }

    synchronized boolean didPhysPttChange() {
        return lastPhysPttDown != isPhysPttDown();
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
            && Float.compare(deviceState.getFreqTx(), desiredState.getFreqTx()) == 0
            && Float.compare(deviceState.getFreqRx(), desiredState.getFreqRx()) == 0
            && deviceState.getCtcssTx() == desiredState.getCtcssTx()
            && deviceState.getSquelch() == desiredState.getSquelch()
            && deviceState.getCtcssRx() == desiredState.getCtcssRx();
    }

    private void setRadioConfigValidAndSend() {
        desiredState.setFlags(desiredState.getFlags() | Protocol.HOST_STATE_RADIO_CONFIG_VALID);
        sendDesiredStateIfChanged();
    }

    private void setDesiredFlags(int nextFlags) {
        if (desiredState.getFlags() == nextFlags) {
            return;
        }
        desiredState.setFlags(nextFlags);
        sendDesiredStateIfChanged();
    }

    private void sendDesiredStateIfChanged() {
        if (updateDepth == 0 && !desiredState.equals(lastDesiredStateSent)) {
            sendDesiredState();
        }
    }

    private void sendDesiredState() {
        desiredState.setSequence(desiredState.getSequence() + 1);
        Protocol.HostDesiredState state = desiredState.copy();
        lastDesiredStateSent = state;
        appliedStateInSync = false;
        sender.sendDesiredState(state);
    }
}
