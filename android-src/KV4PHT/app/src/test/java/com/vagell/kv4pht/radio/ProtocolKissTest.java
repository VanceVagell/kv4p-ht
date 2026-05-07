package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

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
        byte[] frame = Protocol.buildKissFrame(
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
        byte[] frame = Protocol.buildKissFrame(
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
        byte[] vendorPayload = Protocol.buildKv4pVendorPayload(
            Protocol.RcvCommand.COMMAND_RX_AUDIO.getValue(), new byte[]{0x33, 0x44});
        byte[] dataFrame = Protocol.buildKissFrame(Protocol.KISS_CMD_DATA, dataPayload);
        byte[] vendorFrame = Protocol.buildKissFrame(Protocol.KISS_CMD_SETHARDWARE, vendorPayload);
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
        newParser().processBytes(Protocol.buildKissFrame(
            0x10 | Protocol.KISS_CMD_DATA, new byte[]{0x11, 0x22}));

        assertFalse(ax25Called);
        assertFalse(called);
    }

    @Test
    public void parserIgnoresUnknownKissCommand() {
        newParser().processBytes(Protocol.buildKissFrame(0x02, new byte[]{0x11, 0x22}));

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
        parser.processBytes(Protocol.buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            new byte[]{'B', 'A', 'D', '!', Protocol.KV4P_PROTOCOL_VERSION, (byte) Protocol.RcvCommand.COMMAND_HELLO.getValue()}));

        assertFalse(called);

        parser = newParser();
        parser.processBytes(Protocol.buildKissFrame(
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
        byte[] valid = Protocol.buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            Protocol.buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_HELLO.getValue(), new byte[]{0x01}));
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
        byte[] frame = Protocol.buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            Protocol.buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_DEVICE_STATE.getValue(), commandPayload));

        newParser().processBytes(frame);

        assertTrue(called);
        assertEquals(Protocol.RcvCommand.COMMAND_DEVICE_STATE, command);
        assertEquals(commandPayload.length, payloadLen);
        assertArrayEquals(commandPayload, payload);
    }

    @Test
    public void rxAudioVendorFrameUnescapesAndDispatchesPayload() {
        byte[] audioPayload = new byte[]{0x11, (byte) Protocol.KISS_FEND, 0x22, (byte) Protocol.KISS_FESC};
        byte[] frame = Protocol.buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            Protocol.buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_RX_AUDIO.getValue(), audioPayload));

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

        java.util.Optional<Protocol.FirmwareVersion> parsed = Protocol.FirmwareVersion.from(versionPayload.array(), versionPayload.array().length);

        assertTrue(parsed.isPresent());
        assertEquals(16, parsed.get().getVer());
        assertEquals(2048, parsed.get().getWindowSize());
        assertEquals(Protocol.RfModuleType.RF_SA818_UHF, parsed.get().getModuleType());
        assertEquals(400.0f, parsed.get().getMinRadioFreq(), 0.0001f);
        assertEquals(480.0f, parsed.get().getMaxRadioFreq(), 0.0001f);
        assertTrue(parsed.get().isHasHl());
        assertTrue(parsed.get().isHasPhysPtt());
        assertNull(parsed.get().getDeviceState());
    }

    @Test
    public void helloCarriesFirmwareVersionAndInitialDeviceStatePayload() {
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
        byte[] frame = Protocol.buildKissFrame(
            Protocol.KISS_CMD_SETHARDWARE,
            Protocol.buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_HELLO.getValue(), helloPayload.array()));

        newParser().processBytes(frame);

        assertTrue(called);
        assertEquals(Protocol.RcvCommand.COMMAND_HELLO, command);
        assertEquals(helloPayload.array().length, payloadLen);
        java.util.Optional<Protocol.FirmwareVersion> parsed = Protocol.FirmwareVersion.from(payload, payloadLen);
        assertTrue(parsed.isPresent());
        assertEquals(16, parsed.get().getVer());
        assertEquals(400.0f, parsed.get().getMinRadioFreq(), 0.0001f);
        assertEquals(480.0f, parsed.get().getMaxRadioFreq(), 0.0001f);
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

        assertFalse(Protocol.DeviceState.from(deviceStatePayload, 25).isPresent());
        assertFalse(Protocol.DeviceState.from(deviceStatePayload, 27).isPresent());
        assertFalse(Protocol.DeviceState.from(deviceStatePayload, null).isPresent());
    }

    @Test
    public void firmwareVersionParsesVersionOnlyAndVersionWithDeviceState() {
        java.nio.ByteBuffer versionOnly = java.nio.ByteBuffer.allocate(17).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        versionOnly.putShort((short) 16);
        versionOnly.put((byte) 'f');
        versionOnly.putInt(2048);
        versionOnly.put((byte) 1);
        versionOnly.putFloat(400.0f);
        versionOnly.putFloat(480.0f);
        versionOnly.put((byte) 0x03);

        java.util.Optional<Protocol.FirmwareVersion> parsedVersionOnly =
            Protocol.FirmwareVersion.from(versionOnly.array(), versionOnly.array().length);

        assertTrue(parsedVersionOnly.isPresent());
        assertNull(parsedVersionOnly.get().getDeviceState());

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

        java.util.Optional<Protocol.FirmwareVersion> parsedWithDeviceState =
            Protocol.FirmwareVersion.from(withDeviceState.array(), withDeviceState.array().length);

        assertTrue(parsedWithDeviceState.isPresent());
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

        java.util.Optional<Protocol.DeviceState> parsed = Protocol.DeviceState.from(deviceStatePayload.array(), deviceStatePayload.array().length);

        assertTrue(parsed.isPresent());
        assertEquals(9, parsed.get().getAppliedSequence());
        assertEquals(42, parsed.get().getMemoryId());
        assertEquals(Protocol.DeviceMode.DEVICE_MODE_TX, parsed.get().getMode());
        assertEquals(Protocol.RadioStatus.RADIO_STATUS_FOUND, parsed.get().getRadioModuleStatus());
        assertEquals(123, parsed.get().getLatestRssi());
    }

    @Test
    public void firmwareVersionRejectsMismatchedLengthWithoutThrowing() {
        byte[] shortPayload = new byte[]{
            0x10, 0x00,
            'f',
            0x00, 0x08, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
        };

        assertFalse(Protocol.FirmwareVersion.from(shortPayload, 12).isPresent());
    }

    private Protocol.KissParser newParser() {
        return new Protocol.KissParser((cmd, param, len) -> {
            called = true;
            commandCallCount++;
            command = cmd;
            payloadLen = len;
            payload = java.util.Arrays.copyOf(param, len);
        }, (param, len) -> {
            ax25Called = true;
            ax25CallCount++;
            ax25PayloadLen = len;
            ax25Payload = java.util.Arrays.copyOf(param, len);
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
        private final java.util.List<Protocol.HostDesiredState> sentStates = new java.util.ArrayList<>();

        CapturingSender() {
            super(null);
        }

        @Override
        public void sendDesiredState(Protocol.HostDesiredState state) {
            sentStates.add(state.copy());
        }
    }
}
