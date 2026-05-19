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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class ProtocolKissTest {
    private boolean called;
    private int commandCallCount;
    private Protocol.RcvCommand command;
    private byte[] payload;
    private int payloadLen;
    private boolean ax25Called;
    private int ax25CallCount;
    private byte[] ax25Payload;
    private int ax25PayloadLen;

    private static byte[] buildKissFrame(int kissCommand, byte[] payload) {
        return buildKissFrame(kissCommand, payload, payload != null ? payload.length : 0);
    }

    private static byte[] buildKissFrame(int kissCommand, byte[] payload, int len) {
        int payloadLen = boundedPayloadLen(payload, len);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(payloadLen + 3);
        buffer.write(Protocol.KISS_FEND);
        buffer.write(kissCommand & 0xFF);
        for (int i = 0; i < payloadLen; i++) {
            int b = payload[i] & 0xFF;
            if (b == Protocol.KISS_FEND) {
                buffer.write(Protocol.KISS_FESC);
                buffer.write(Protocol.KISS_TFEND);
            } else if (b == Protocol.KISS_FESC) {
                buffer.write(Protocol.KISS_FESC);
                buffer.write(Protocol.KISS_TFESC);
            } else {
                buffer.write(b);
            }
        }
        buffer.write(Protocol.KISS_FEND);
        return buffer.toByteArray();
    }

    private static byte[] buildKv4pVendorPayload(int kv4pCommand, byte[] param) {
        return buildKv4pVendorPayload(kv4pCommand, param, param != null ? param.length : 0);
    }

    private static byte[] buildKv4pVendorPayload(int kv4pCommand, byte[] param, int len) {
        int paramLen = boundedPayloadLen(param, len);
        ByteBuffer payload = ByteBuffer.allocate(Protocol.KV4P_VENDOR_HEADER_LEN + paramLen);
        payload.put(Protocol.KV4P_VENDOR_PREFIX);
        payload.put((byte) Protocol.KV4P_PROTOCOL_VERSION);
        payload.put((byte) kv4pCommand);
        if (paramLen > 0) {
            payload.put(param, 0, paramLen);
        }
        return payload.array();
    }

    private static int boundedPayloadLen(byte[] payload, int len) {
        if (payload == null || len <= 0) {
            return 0;
        }
        return Math.min(len, Math.min(payload.length, Protocol.PROTO_MTU));
    }

    @Before
    public void setUp() {
        called = false;
        commandCallCount = 0;
        command = Protocol.RcvCommand.COMMAND_RCV_UNKNOWN;
        payload = new byte[0];
        payloadLen = 0;
        ax25Called = false;
        ax25CallCount = 0;
        ax25Payload = new byte[0];
        ax25PayloadLen = 0;
    }

    @Test
    public void encoderEscapesFendAndFesc() {
        byte[] frame = buildKissFrame(
            Protocol.KISS_CMD_DATA,
            new byte[]{0x11, (byte) Protocol.KISS_FEND, 0x22, (byte) Protocol.KISS_FESC});

        assertArrayEquals(new byte[]{
            (byte) Protocol.KISS_FEND,
            Protocol.KISS_CMD_DATA,
            0x11,
            (byte) Protocol.KISS_FESC,
            (byte) Protocol.KISS_TFEND,
            0x22,
            (byte) Protocol.KISS_FESC,
            (byte) Protocol.KISS_TFESC,
            (byte) Protocol.KISS_FEND,
        }, frame);
    }

    @Test
    public void encoderUsesOnlyProvidedPayloadLength() {
        byte[] frame = buildKissFrame(
            Protocol.KISS_CMD_DATA,
            new byte[]{0x11, 0x22, (byte) Protocol.KISS_FEND, (byte) Protocol.KISS_FESC},
            2);

        assertArrayEquals(new byte[]{
            (byte) Protocol.KISS_FEND,
            Protocol.KISS_CMD_DATA,
            0x11,
            0x22,
            (byte) Protocol.KISS_FEND,
        }, frame);
    }

    @Test
    public void parserUnescapesDataFrameAndDispatchesAx25() {
        Protocol.KissParser parser = newParser();
        parser.processBytes(new byte[]{
            (byte) Protocol.KISS_FEND,
            Protocol.KISS_CMD_DATA,
            0x11,
            (byte) Protocol.KISS_FESC,
            (byte) Protocol.KISS_TFEND,
            0x22,
            (byte) Protocol.KISS_FESC,
            (byte) Protocol.KISS_TFESC,
            (byte) Protocol.KISS_FEND,
        });

        assertFalse(called);
        assertTrue(ax25Called);
        assertEquals(4, ax25PayloadLen);
        assertArrayEquals(new byte[]{0x11, (byte) Protocol.KISS_FEND, 0x22, (byte) Protocol.KISS_FESC}, ax25Payload);
    }

    @Test
    public void parserHandlesMultipleFramesInOneProcessBytesCall() {
        byte[] dataPayload = new byte[]{0x11, 0x22};
        byte[] vendorPayload = buildKv4pVendorPayload(
            Protocol.RcvCommand.COMMAND_RX_AUDIO.getValue(), new byte[]{0x33, 0x44});
        byte[] dataFrame = buildKissFrame(Protocol.KISS_CMD_DATA, dataPayload);
        byte[] vendorFrame = buildKissFrame(Protocol.KISS_CMD_SETHARDWARE, vendorPayload);
        byte[] combined = new byte[dataFrame.length + vendorFrame.length];
        System.arraycopy(dataFrame, 0, combined, 0, dataFrame.length);
        System.arraycopy(vendorFrame, 0, combined, dataFrame.length, vendorFrame.length);

        newParser().processBytes(combined);

        assertEquals(1, ax25CallCount);
        assertEquals(1, commandCallCount);
        assertArrayEquals(dataPayload, ax25Payload);
        assertEquals(Protocol.RcvCommand.COMMAND_RX_AUDIO, command);
        assertArrayEquals(new byte[]{0x33, 0x44}, payload);
    }

    @Test
    public void parserDispatchesSplitFrameOnlyAfterFinalFend() {
        Protocol.KissParser parser = newParser();

        parser.processBytes(new byte[]{(byte) Protocol.KISS_FEND, Protocol.KISS_CMD_DATA});
        parser.processBytes(new byte[]{0x11, 0x22});

        assertFalse(ax25Called);

        parser.processBytes(new byte[]{(byte) Protocol.KISS_FEND});

        assertTrue(ax25Called);
        assertArrayEquals(new byte[]{0x11, 0x22}, ax25Payload);
    }

    @Test
    public void parserIgnoresNonZeroKissPort() {
        newParser().processBytes(buildKissFrame(
            0x10 | Protocol.KISS_CMD_DATA, new byte[]{0x11, 0x22}));

        assertFalse(ax25Called);
        assertFalse(called);
    }

    @Test
    public void parserIgnoresUnknownKissCommand() {
        newParser().processBytes(buildKissFrame(0x02, new byte[]{0x11, 0x22}));

        assertFalse(ax25Called);
        assertFalse(called);
    }

    @Test
    public void parserIgnoresMultipleFendBytes() {
        Protocol.KissParser parser = newParser();
        parser.processBytes(new byte[]{
            (byte) Protocol.KISS_FEND,
            (byte) Protocol.KISS_FEND,
            Protocol.KISS_CMD_SETHARDWARE,
            'K', 'V', '4', 'P',
            Protocol.KV4P_PROTOCOL_VERSION,
            (byte) Protocol.RcvCommand.COMMAND_HELLO.getValue(),
            (byte) Protocol.KISS_FEND,
        });

        assertTrue(called);
        assertEquals(Protocol.RcvCommand.COMMAND_HELLO, command);
        assertEquals(0, payloadLen);
    }

    @Test
    public void parserValidatesVendorPrefixAndVersion() {
        Protocol.KissParser parser = newParser();
        parser.processBytes(buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            new byte[]{'B', 'A', 'D', '!', Protocol.KV4P_PROTOCOL_VERSION, (byte) Protocol.RcvCommand.COMMAND_HELLO.getValue()}));

        assertFalse(called);

        parser = newParser();
        parser.processBytes(buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            new byte[]{'K', 'V', '4', 'P', 0x02, (byte) Protocol.RcvCommand.COMMAND_HELLO.getValue()}));

        assertFalse(called);
    }

    @Test
    public void parserDropsOverMtuDataFrame() {
        Protocol.KissParser parser = newParser();
        byte[] frame = new byte[Protocol.PROTO_MTU + 4];
        frame[0] = (byte) Protocol.KISS_FEND;
        frame[1] = Protocol.KISS_CMD_DATA;
        for (int i = 2; i < frame.length - 1; i++) {
            frame[i] = 0x55;
        }
        frame[frame.length - 1] = (byte) Protocol.KISS_FEND;

        parser.processBytes(frame);

        assertFalse(called);
    }

    @Test
    public void parserDropsOversizedVendorFrameAndRecoversOnNextGoodFrame() {
        byte[] oversized = new byte[Protocol.KISS_MAX_FRAME_SIZE + 3];
        oversized[0] = (byte) Protocol.KISS_FEND;
        oversized[1] = Protocol.KISS_CMD_SETHARDWARE;
        java.util.Arrays.fill(oversized, 2, oversized.length - 1, (byte) 0x55);
        oversized[oversized.length - 1] = (byte) Protocol.KISS_FEND;
        byte[] valid = buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_HELLO.getValue(), new byte[]{0x01}));
        byte[] combined = new byte[oversized.length + valid.length];
        System.arraycopy(oversized, 0, combined, 0, oversized.length);
        System.arraycopy(valid, 0, combined, oversized.length, valid.length);

        newParser().processBytes(combined);

        assertEquals(1, commandCallCount);
        assertEquals(Protocol.RcvCommand.COMMAND_HELLO, command);
        assertArrayEquals(new byte[]{0x01}, payload);
    }

    @Test
    public void parserIgnoresEmptyDataFrame() {
        newParser().processBytes(new byte[]{
            (byte) Protocol.KISS_FEND,
            Protocol.KISS_CMD_DATA,
            (byte) Protocol.KISS_FEND,
        });

        assertFalse(ax25Called);
        assertFalse(called);
    }

    @Test
    public void parserDropsUnknownEscape() {
        Protocol.KissParser parser = newParser();
        parser.processBytes(new byte[]{
            (byte) Protocol.KISS_FEND,
            Protocol.KISS_CMD_DATA,
            0x11,
            (byte) Protocol.KISS_FESC,
            (byte) 0x99,
            0x22,
            (byte) Protocol.KISS_FEND,
        });

        assertFalse(called);
    }

    @Test
    public void vendorFrameRoundTripsCommandPayloadWithoutTopLevelLength() {
        byte[] commandPayload = new byte[]{0x01, 0x02, 0x03};
        byte[] frame = buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_DEVICE_STATE.getValue(), commandPayload));

        newParser().processBytes(frame);

        assertTrue(called);
        assertEquals(Protocol.RcvCommand.COMMAND_DEVICE_STATE, command);
        assertEquals(commandPayload.length, payloadLen);
        assertArrayEquals(commandPayload, payload);
    }

    @Test
    public void rxAudioVendorFrameUnescapesAndDispatchesPayload() {
        byte[] audioPayload = new byte[]{0x11, (byte) Protocol.KISS_FEND, 0x22, (byte) Protocol.KISS_FESC};
        byte[] frame = buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_RX_AUDIO.getValue(), audioPayload));

        newParser().processBytes(frame);

        assertTrue(called);
        assertEquals(Protocol.RcvCommand.COMMAND_RX_AUDIO, command);
        assertEquals(audioPayload.length, payloadLen);
        assertArrayEquals(audioPayload, payload);
    }

    @Test
    public void firmwareVersionParsesSingleFeaturesByte() {
        java.nio.ByteBuffer versionPayload = java.nio.ByteBuffer.allocate(17).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        versionPayload.putShort((short) 16);
        versionPayload.put((byte) 'f');
        versionPayload.putInt(2048);
        versionPayload.put((byte) 1);
        versionPayload.putFloat(400.0f);
        versionPayload.putFloat(480.0f);
        versionPayload.put((byte) 0x03);

        java.util.Optional<Protocol.FirmwareVersion> parsed = Protocol.FirmwareVersion.from(versionPayload, 0, versionPayload.array().length);

        assertTrue(parsed.isPresent());
        assertEquals(16, parsed.get().getVer());
        assertEquals(2048, parsed.get().getWindowSize());
        assertEquals(Protocol.RfModuleType.RF_SA818_UHF, parsed.get().getModuleType());
        assertEquals(400.0f, parsed.get().getMinRadioFreq(), 0.0001f);
        assertEquals(480.0f, parsed.get().getMaxRadioFreq(), 0.0001f);
        assertTrue(parsed.get().isHasHl());
        assertTrue(parsed.get().isHasPhysPtt());
    }

    @Test
    public void helloCarriesVersionAndInitialDeviceStatePayload() {
        java.nio.ByteBuffer helloPayload = java.nio.ByteBuffer.allocate(43).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        helloPayload.putShort((short) 16);
        helloPayload.put((byte) 'f');
        helloPayload.putInt(2048);
        helloPayload.put((byte) 1);
        helloPayload.putFloat(400.0f);
        helloPayload.putFloat(480.0f);
        helloPayload.put((byte) 0x07);
        helloPayload.putInt(9);
        helloPayload.putInt(42);
        helloPayload.putShort((short) Protocol.HOST_STATE_RADIO_CONFIG_VALID);
        helloPayload.put(Protocol.DRA818_12K5);
        helloPayload.putFloat(144.3900f);
        helloPayload.putFloat(144.3900f);
        helloPayload.put((byte) 4);
        helloPayload.put((byte) 5);
        helloPayload.put((byte) 6);
        helloPayload.put((byte) 'f');
        helloPayload.put((byte) Protocol.DeviceMode.DEVICE_MODE_STOPPED.getValue());
        helloPayload.put((byte) 0);
        helloPayload.put((byte) 123);
        byte[] frame = buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_HELLO.getValue(), helloPayload.array()));

        newParser().processBytes(frame);

        assertTrue(called);
        assertEquals(Protocol.RcvCommand.COMMAND_HELLO, command);
        assertEquals(helloPayload.array().length, payloadLen);
        java.util.Optional<Protocol.Hello> parsed =
            Protocol.Hello.from(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN), 0, payloadLen);
        assertTrue(parsed.isPresent());
        assertEquals(16, parsed.get().getVersion().getVer());
        assertEquals(400.0f, parsed.get().getVersion().getMinRadioFreq(), 0.0001f);
        assertEquals(480.0f, parsed.get().getVersion().getMaxRadioFreq(), 0.0001f);
        assertNotNull(parsed.get().getDeviceState());
        assertEquals(9, parsed.get().getDeviceState().getAppliedSequence());
        assertEquals(42, parsed.get().getDeviceState().getMemoryId());
        assertEquals(123, parsed.get().getDeviceState().getLatestRssi());
    }

    @Test
    public void hostDesiredStateSerializesAsPackedFirmwareStruct() {
        byte[] desiredStatePayload = Protocol.HostDesiredState.builder()
            .sequence(7)
            .memoryId(42)
            .flags(Protocol.HOST_STATE_RADIO_CONFIG_VALID | Protocol.HOST_STATE_RX_AUDIO_OPEN)
            .bw(Protocol.DRA818_25K)
            .freqTx(146.5200f)
            .freqRx(146.5200f)
            .ctcssTx((byte) 1)
            .squelch((byte) 2)
            .ctcssRx((byte) 3)
            .build()
            .toBytes();

        java.nio.ByteBuffer parsed = java.nio.ByteBuffer.wrap(desiredStatePayload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(22, desiredStatePayload.length);
        assertEquals(7, parsed.getInt());
        assertEquals(42, parsed.getInt());
        assertEquals(Protocol.HOST_STATE_RADIO_CONFIG_VALID | Protocol.HOST_STATE_RX_AUDIO_OPEN, parsed.getShort() & 0xFFFF);
        assertEquals(Protocol.DRA818_25K, parsed.get());
        assertEquals(146.5200f, parsed.getFloat(), 0.0001f);
        assertEquals(146.5200f, parsed.getFloat(), 0.0001f);
        assertEquals(1, parsed.get());
        assertEquals(2, parsed.get());
        assertEquals(3, parsed.get());
    }

    @Test
    public void deviceStateRejectsWrongLengthsWithoutThrowing() {
        byte[] deviceStatePayload = new byte[26];
        ByteBuffer deviceStateBuffer = ByteBuffer.wrap(deviceStatePayload).order(ByteOrder.LITTLE_ENDIAN);

        assertFalse(Protocol.DeviceState.from(deviceStateBuffer, 0, 25).isPresent());
        assertFalse(Protocol.DeviceState.from(deviceStateBuffer, 0, 27).isPresent());
        assertFalse(Protocol.DeviceState.from(deviceStateBuffer, 0, null).isPresent());
    }

    @Test
    public void helloRequiresVersionAndDeviceState() {
        java.nio.ByteBuffer versionOnly = java.nio.ByteBuffer.allocate(17).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        versionOnly.putShort((short) 16);
        versionOnly.put((byte) 'f');
        versionOnly.putInt(2048);
        versionOnly.put((byte) 1);
        versionOnly.putFloat(400.0f);
        versionOnly.putFloat(480.0f);
        versionOnly.put((byte) 0x03);

        assertTrue(Protocol.FirmwareVersion.from(versionOnly, 0, versionOnly.array().length).isPresent());
        assertFalse(Protocol.Hello.from(versionOnly, 0, versionOnly.array().length).isPresent());

        java.nio.ByteBuffer withDeviceState = java.nio.ByteBuffer.allocate(43).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        withDeviceState.put(versionOnly.array());
        withDeviceState.putInt(9);
        withDeviceState.putInt(42);
        withDeviceState.putShort((short) Protocol.HOST_STATE_RADIO_CONFIG_VALID);
        withDeviceState.put(Protocol.DRA818_12K5);
        withDeviceState.putFloat(144.3900f);
        withDeviceState.putFloat(144.3900f);
        withDeviceState.put((byte) 4);
        withDeviceState.put((byte) 5);
        withDeviceState.put((byte) 6);
        withDeviceState.put((byte) 'f');
        withDeviceState.put((byte) Protocol.DeviceMode.DEVICE_MODE_STOPPED.getValue());
        withDeviceState.put((byte) 0);
        withDeviceState.put((byte) 123);

        java.util.Optional<Protocol.Hello> parsedWithDeviceState =
            Protocol.Hello.from(withDeviceState, 0, withDeviceState.array().length);

        assertTrue(parsedWithDeviceState.isPresent());
        assertEquals(16, parsedWithDeviceState.get().getVersion().getVer());
        assertNotNull(parsedWithDeviceState.get().getDeviceState());
        assertEquals(42, parsedWithDeviceState.get().getDeviceState().getMemoryId());
    }

    @Test
    public void radioModuleControllerSeedsInitialDeviceStateBeforeFirstSend() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        Protocol.DeviceState initialState = Protocol.DeviceState.builder()
            .appliedSequence(7)
            .memoryId(42)
            .flags(Protocol.HOST_STATE_RADIO_CONFIG_VALID
                | Protocol.HOST_STATE_HIGH_POWER
                | Protocol.HOST_STATE_RSSI_ENABLED)
            .bw(Protocol.DRA818_12K5)
            .freqTx(144.3900f)
            .freqRx(144.3900f)
            .ctcssTx((byte) 4)
            .squelch((byte) 5)
            .ctcssRx((byte) 6)
            .radioModuleStatus(Protocol.RadioStatus.RADIO_STATUS_FOUND)
            .mode(Protocol.DeviceMode.DEVICE_MODE_STOPPED)
            .lastError(0)
            .latestRssi(123)
            .build();

        controller.seedFromDeviceState(initialState);
        controller.markTransportReady();
        controller.openAudio();

        assertEquals(1, sender.sentStates.size());
        Protocol.HostDesiredState sent = sender.sentStates.get(0);
        assertEquals(8, sent.getSequence());
        assertNotEquals(0, sent.getFlags() & Protocol.HOST_STATE_RADIO_CONFIG_VALID);
        assertNotEquals(0, sent.getFlags() & Protocol.HOST_STATE_RX_AUDIO_OPEN);
        assertNotEquals(0, sent.getFlags() & Protocol.HOST_STATE_HIGH_POWER);
        assertNotEquals(0, sent.getFlags() & Protocol.HOST_STATE_RSSI_ENABLED);
        assertEquals(Protocol.DRA818_12K5, sent.getBw());
        assertEquals(42, sent.getMemoryId());
        assertEquals(144.3900f, sent.getFreqTx(), 0.0001f);
        assertEquals(144.3900f, sent.getFreqRx(), 0.0001f);
        assertEquals(4, sent.getCtcssTx());
        assertEquals(5, sent.getSquelch());
        assertEquals(6, sent.getCtcssRx());
    }

    @Test
    public void radioModuleControllerRetriesLastDesiredStateWhenDeviceStateDoesNotMatch() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        Protocol.DeviceState initialState = Protocol.DeviceState.builder()
            .appliedSequence(7)
            .memoryId(42)
            .flags(Protocol.HOST_STATE_RADIO_CONFIG_VALID
                | Protocol.HOST_STATE_HIGH_POWER
                | Protocol.HOST_STATE_RSSI_ENABLED)
            .bw(Protocol.DRA818_25K)
            .freqTx(146.5200f)
            .freqRx(146.5200f)
            .ctcssTx((byte) 0)
            .squelch((byte) 1)
            .ctcssRx((byte) 0)
            .radioModuleStatus(Protocol.RadioStatus.RADIO_STATUS_FOUND)
            .mode(Protocol.DeviceMode.DEVICE_MODE_STOPPED)
            .lastError(0)
            .latestRssi(0)
            .build();

        controller.seedFromDeviceState(initialState);
        controller.markTransportReady();
        controller.openAudio();

        assertEquals(1, sender.sentStates.size());
        Protocol.HostDesiredState firstSend = sender.sentStates.get(0);
        controller.updateDeviceState(initialState);

        assertEquals(2, sender.sentStates.size());
        Protocol.HostDesiredState retry = sender.sentStates.get(1);
        assertEquals(firstSend.getSequence(), retry.getSequence());
        assertEquals(firstSend, retry);
    }

    @Test
    public void radioModuleControllerCoalescesChangesInsideUpdateBlock() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        controller.markTransportReady();

        controller.beginUpdate();
        controller.setMemoryId(7);
        controller.setTxFrequency(146.5200f);
        controller.setRxFrequency(146.5200f);
        controller.setTxTone((byte) 1);
        controller.setRxTone((byte) 2);
        controller.endUpdate();

        assertEquals(1, sender.sentStates.size());
        assertEquals(7, sender.sentStates.get(0).getMemoryId());
        assertEquals(146.5200f, sender.sentStates.get(0).getFreqTx(), 0.0001f);
        assertEquals(146.5200f, sender.sentStates.get(0).getFreqRx(), 0.0001f);
        assertEquals(1, sender.sentStates.get(0).getCtcssTx());
        assertEquals(2, sender.sentStates.get(0).getCtcssRx());
    }

    @Test
    public void radioModuleControllerDoesNotSendBeforeTransportReady() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);

        controller.setMemoryId(7);
        controller.setTxFrequency(146.5200f);

        assertEquals(0, sender.sentStates.size());

        controller.markTransportReady();

        assertEquals(1, sender.sentStates.size());
    }

    @Test
    public void matchingDeviceStateMarksControllerInSyncAndDoesNotRetry() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        controller.markTransportReady();
        controller.beginUpdate();
        controller.setMemoryId(7);
        controller.setTxFrequency(146.5200f);
        controller.setRxFrequency(146.5200f);
        controller.setSquelch(3);
        controller.endUpdate();
        Protocol.HostDesiredState sent = sender.sentStates.get(0);

        controller.updateDeviceState(deviceStateMatching(sent, 0));

        assertTrue(controller.isAppliedStateInSync());
        assertEquals(1, sender.sentStates.size());
    }

    @Test
    public void deviceStateWithErrorTriggersBoundedRetries() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        controller.markTransportReady();
        controller.setTxFrequency(146.5200f);
        Protocol.HostDesiredState sent = sender.sentStates.get(0);
        Protocol.DeviceState errorState = deviceStateMatching(sent, 1);

        for (int i = 0; i < 10; i++) {
            controller.updateDeviceState(errorState);
        }

        assertFalse(controller.isAppliedStateInSync());
        assertEquals(4, sender.sentStates.size());
        assertEquals(sent, sender.sentStates.get(1));
        assertEquals(sent, sender.sentStates.get(2));
        assertEquals(sent, sender.sentStates.get(3));
    }

    @Test
    public void changedDesiredStateSendsNewSequenceAfterRetry() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        controller.markTransportReady();
        controller.setTxFrequency(146.5200f);
        Protocol.HostDesiredState first = sender.sentStates.get(0);

        controller.updateDeviceState(deviceStateMatching(first, 1));
        controller.setSquelch(4);

        assertEquals(3, sender.sentStates.size());
        assertEquals(first, sender.sentStates.get(1));
        Protocol.HostDesiredState changed = sender.sentStates.get(2);
        assertEquals(first.getSequence() + 1, changed.getSequence());
        assertEquals(4, changed.getSquelch());
    }

    @Test
    public void seedFromDeviceStateDoesNotCarryTransientHostFlags() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        Protocol.DeviceState initialState = Protocol.DeviceState.builder()
            .appliedSequence(7)
            .memoryId(42)
            .flags(Protocol.HOST_STATE_RADIO_CONFIG_VALID
                | Protocol.HOST_STATE_PTT_REQUESTED
                | Protocol.HOST_STATE_RX_AUDIO_OPEN
                | Protocol.HOST_STATE_HIGH_POWER)
            .bw(Protocol.DRA818_25K)
            .freqTx(146.5200f)
            .freqRx(146.5200f)
            .ctcssTx((byte) 0)
            .squelch((byte) 1)
            .ctcssRx((byte) 0)
            .radioModuleStatus(Protocol.RadioStatus.RADIO_STATUS_FOUND)
            .mode(Protocol.DeviceMode.DEVICE_MODE_STOPPED)
            .lastError(0)
            .latestRssi(0)
            .build();

        controller.seedFromDeviceState(initialState);
        controller.markTransportReady();
        controller.setSquelch(2);

        assertEquals(1, sender.sentStates.size());
        int flags = sender.sentStates.get(0).getFlags();
        assertEquals(0, flags & Protocol.HOST_STATE_PTT_REQUESTED);
        assertEquals(0, flags & Protocol.HOST_STATE_RX_AUDIO_OPEN);
        assertNotEquals(0, flags & Protocol.HOST_STATE_HIGH_POWER);
    }

    @Test
    public void seedFromDeviceStateWithoutRadioConfigKeepsControllerDefaults() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        controller.setMemoryId(99);
        controller.setBandwidth(Protocol.DRA818_12K5);
        controller.setTxFrequency(446.0f);
        controller.setRxFrequency(445.5f);
        controller.setTxTone((byte) 4);
        controller.setSquelch(5);
        controller.setRxTone((byte) 6);
        Protocol.DeviceState emptyNvsState = Protocol.DeviceState.builder()
            .appliedSequence(7)
            .memoryId(0)
            .flags(0)
            .bw(Protocol.DRA818_12K5)
            .freqTx(0.0f)
            .freqRx(0.0f)
            .ctcssTx((byte) 0)
            .squelch((byte) 0)
            .ctcssRx((byte) 0)
            .radioModuleStatus(Protocol.RadioStatus.RADIO_STATUS_FOUND)
            .mode(Protocol.DeviceMode.DEVICE_MODE_STOPPED)
            .lastError(0)
            .latestRssi(0)
            .build();

        controller.seedFromDeviceState(emptyNvsState);
        controller.markTransportReady();
        controller.openAudio();

        assertEquals(1, sender.sentStates.size());
        Protocol.HostDesiredState sent = sender.sentStates.get(0);
        assertEquals(Protocol.DRA818_25K, sent.getBw());
        assertEquals(-1, sent.getMemoryId());
        assertEquals(0.0f, sent.getFreqTx(), 0.0001f);
        assertEquals(0.0f, sent.getFreqRx(), 0.0001f);
        assertEquals(0, sent.getCtcssTx());
        assertEquals(0, sent.getSquelch());
        assertEquals(0, sent.getCtcssRx());
        assertEquals(0, sent.getFlags() & Protocol.HOST_STATE_RADIO_CONFIG_VALID);
        assertNotEquals(0, sent.getFlags() & Protocol.HOST_STATE_HIGH_POWER);
        assertNotEquals(0, sent.getFlags() & Protocol.HOST_STATE_RSSI_ENABLED);
        assertNotEquals(0, sent.getFlags() & Protocol.HOST_STATE_RX_AUDIO_OPEN);
        assertEquals(0, sent.getFlags() & Protocol.HOST_STATE_TX_ALLOWED);
    }

    @Test
    public void radioModuleControllerCanSetTxAllowed() {
        CapturingSender sender = new CapturingSender();
        RadioModuleController controller = new RadioModuleController();
        controller.attachSender(sender);
        controller.markTransportReady();

        controller.setTxAllowed(true);

        assertEquals(1, sender.sentStates.size());
        Protocol.HostDesiredState sent = sender.sentStates.get(0);
        assertNotEquals(0, sent.getFlags() & Protocol.HOST_STATE_TX_ALLOWED);
        assertTrue(controller.isTxAllowed());
    }

    @Test
    public void deviceStateParsesPackedFirmwareStruct() {
        java.nio.ByteBuffer deviceStatePayload = java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        deviceStatePayload.putInt(9);
        deviceStatePayload.putInt(42);
        deviceStatePayload.putShort((short) (Protocol.HOST_STATE_RADIO_CONFIG_VALID | Protocol.DEVICE_STATE_TX_ACTIVE));
        deviceStatePayload.put(Protocol.DRA818_12K5);
        deviceStatePayload.putFloat(144.3900f);
        deviceStatePayload.putFloat(144.3900f);
        deviceStatePayload.put((byte) 4);
        deviceStatePayload.put((byte) 5);
        deviceStatePayload.put((byte) 6);
        deviceStatePayload.put((byte) 'f');
        deviceStatePayload.put((byte) Protocol.DeviceMode.DEVICE_MODE_TX.getValue());
        deviceStatePayload.put((byte) 0);
        deviceStatePayload.put((byte) 123);

        java.util.Optional<Protocol.DeviceState> parsed = Protocol.DeviceState.from(deviceStatePayload, 0, deviceStatePayload.array().length);

        assertTrue(parsed.isPresent());
        assertEquals(9, parsed.get().getAppliedSequence());
        assertEquals(42, parsed.get().getMemoryId());
        assertEquals(Protocol.DeviceMode.DEVICE_MODE_TX, parsed.get().getMode());
        assertEquals(Protocol.RadioStatus.RADIO_STATUS_FOUND, parsed.get().getRadioModuleStatus());
        assertEquals(123, parsed.get().getLatestRssi());
    }

    @Test
    public void structParsersTreatDefaultOrderBuffersAsLittleEndian() {
        ByteBuffer deviceStatePayload = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        deviceStatePayload.putInt(9);
        deviceStatePayload.putInt(42);
        deviceStatePayload.putShort((short) Protocol.HOST_STATE_RADIO_CONFIG_VALID);
        deviceStatePayload.put(Protocol.DRA818_12K5);
        deviceStatePayload.putFloat(144.3900f);
        deviceStatePayload.putFloat(146.5200f);
        deviceStatePayload.put((byte) 4);
        deviceStatePayload.put((byte) 5);
        deviceStatePayload.put((byte) 6);
        deviceStatePayload.put((byte) 'f');
        deviceStatePayload.put((byte) Protocol.DeviceMode.DEVICE_MODE_RX.getValue());
        deviceStatePayload.put((byte) 0);
        deviceStatePayload.put((byte) 123);

        ByteBuffer defaultOrderDeviceState = ByteBuffer.wrap(deviceStatePayload.array());
        java.util.Optional<Protocol.DeviceState> parsedDeviceState =
            Protocol.DeviceState.from(defaultOrderDeviceState, 0, deviceStatePayload.array().length);

        assertTrue(parsedDeviceState.isPresent());
        assertEquals(9, parsedDeviceState.get().getAppliedSequence());
        assertEquals(42, parsedDeviceState.get().getMemoryId());
        assertEquals(144.3900f, parsedDeviceState.get().getFreqTx(), 0.0001f);
        assertEquals(146.5200f, parsedDeviceState.get().getFreqRx(), 0.0001f);

        ByteBuffer windowUpdatePayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        windowUpdatePayload.putInt(2048);

        java.util.Optional<Protocol.WindowUpdate> parsedWindowUpdate =
            Protocol.WindowUpdate.from(ByteBuffer.wrap(windowUpdatePayload.array()), 0, windowUpdatePayload.array().length);

        assertTrue(parsedWindowUpdate.isPresent());
        assertEquals(2048, parsedWindowUpdate.get().getSize());
    }

    @Test
    public void helloRejectsMismatchedLengthWithoutThrowing() {
        byte[] shortPayload = new byte[]{
            0x10, 0x00,
            'f',
            0x00, 0x08, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
        };

        assertFalse(Protocol.Hello.from(ByteBuffer.wrap(shortPayload).order(ByteOrder.LITTLE_ENDIAN), 0, 12).isPresent());
    }

    private Protocol.KissParser newParser() {
        return new Protocol.KissParser((cmd, param, offset, len) -> {
            called = true;
            commandCallCount++;
            command = cmd;
            payloadLen = len;
            payload = java.util.Arrays.copyOfRange(param.array(), offset, offset + len);
        }, (param, offset, len) -> {
            ax25Called = true;
            ax25CallCount++;
            ax25PayloadLen = len;
            ax25Payload = java.util.Arrays.copyOfRange(param.array(), offset, offset + len);
        });
    }

    private Protocol.DeviceState deviceStateMatching(Protocol.HostDesiredState desiredState, int lastError) {
        return Protocol.DeviceState.builder()
            .appliedSequence(desiredState.getSequence())
            .memoryId(desiredState.getMemoryId())
            .flags(desiredState.getFlags())
            .bw(desiredState.getBw())
            .freqTx(desiredState.getFreqTx())
            .freqRx(desiredState.getFreqRx())
            .ctcssTx(desiredState.getCtcssTx())
            .squelch(desiredState.getSquelch())
            .ctcssRx(desiredState.getCtcssRx())
            .radioModuleStatus(Protocol.RadioStatus.RADIO_STATUS_FOUND)
            .mode(Protocol.DeviceMode.DEVICE_MODE_RX)
            .lastError(lastError)
            .latestRssi(0)
            .build();
    }

    private static class CapturingSender extends Protocol.Sender {
        private final List<Protocol.HostDesiredState> sentStates = new ArrayList<>();

        CapturingSender() {
            super(null);
        }

        @Override
        public void sendDesiredState(@NonNull Protocol.HostDesiredState state) {
            sentStates.add(state);
        }
    }
}
