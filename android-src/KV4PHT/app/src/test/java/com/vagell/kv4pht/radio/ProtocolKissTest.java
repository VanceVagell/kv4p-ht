package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class ProtocolKissTest {
    private boolean called;
    private Protocol.RcvCommand command;
    private byte[] payload;
    private int payloadLen;
    private boolean ax25Called;
    private byte[] ax25Payload;
    private int ax25PayloadLen;

    @Before
    public void setUp() {
        called = false;
        command = Protocol.RcvCommand.COMMAND_RCV_UNKNOWN;
        payload = new byte[0];
        payloadLen = 0;
        ax25Called = false;
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
        java.nio.ByteBuffer payload = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        payload.putShort((short) 16);
        payload.put((byte) 'f');
        payload.putInt(2048);
        payload.putInt(1);
        payload.putFloat(400.0f);
        payload.putFloat(480.0f);
        payload.put((byte) 0x03);

        java.util.Optional<Protocol.FirmwareVersion> parsed = Protocol.FirmwareVersion.from(payload.array(), payload.array().length);

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
        java.nio.ByteBuffer helloPayload = java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        helloPayload.putShort((short) 16);
        helloPayload.put((byte) 'f');
        helloPayload.putInt(2048);
        helloPayload.putInt(1);
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
        assertTrue(parsed.get().getDeviceState() != null);
        assertEquals(9, parsed.get().getDeviceState().getAppliedSequence());
        assertEquals(42, parsed.get().getDeviceState().getMemoryId());
        assertEquals(123, parsed.get().getDeviceState().getLatestRssi());
    }

    @Test
    public void hostDesiredStateSerializesAsPackedFirmwareStruct() {
        byte[] payload = Protocol.HostDesiredState.builder()
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

        java.nio.ByteBuffer parsed = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(22, payload.length);
        assertEquals(7, parsed.getInt());
        assertEquals(42, parsed.getInt());
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
        assertTrue((sent.getFlags() & Protocol.HOST_STATE_RADIO_CONFIG_VALID) != 0);
        assertTrue((sent.getFlags() & Protocol.HOST_STATE_RX_AUDIO_OPEN) != 0);
        assertTrue((sent.getFlags() & Protocol.HOST_STATE_HIGH_POWER) != 0);
        assertTrue((sent.getFlags() & Protocol.HOST_STATE_RSSI_ENABLED) != 0);
        assertEquals(Protocol.DRA818_12K5, sent.getBw());
        assertEquals(42, sent.getMemoryId());
        assertEquals(144.3900f, sent.getFreqTx(), 0.0001f);
        assertEquals(144.3900f, sent.getFreqRx(), 0.0001f);
        assertEquals(4, sent.getCtcssTx());
        assertEquals(5, sent.getSquelch());
        assertEquals(6, sent.getCtcssRx());
    }

    @Test
    public void deviceStateParsesPackedFirmwareStruct() {
        java.nio.ByteBuffer payload = java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        payload.putInt(9);
        payload.putInt(42);
        payload.putShort((short) (Protocol.HOST_STATE_RADIO_CONFIG_VALID | Protocol.DEVICE_STATE_TX_ACTIVE));
        payload.put(Protocol.DRA818_12K5);
        payload.putFloat(144.3900f);
        payload.putFloat(144.3900f);
        payload.put((byte) 4);
        payload.put((byte) 5);
        payload.put((byte) 6);
        payload.put((byte) 'f');
        payload.put((byte) Protocol.DeviceMode.DEVICE_MODE_TX.getValue());
        payload.put((byte) 0);
        payload.put((byte) 123);

        java.util.Optional<Protocol.DeviceState> parsed = Protocol.DeviceState.from(payload.array(), payload.array().length);

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
            command = cmd;
            payloadLen = len;
            payload = java.util.Arrays.copyOf(param, len);
        }, (param, len) -> {
            ax25Called = true;
            ax25PayloadLen = len;
            ax25Payload = java.util.Arrays.copyOf(param, len);
        });
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
