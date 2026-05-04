package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class ProtocolKissTest {
    private boolean called;
    private Protocol.RcvCommand command;
    private byte[] payload;
    private int payloadLen;

    @Before
    public void setUp() {
        called = false;
        command = Protocol.RcvCommand.COMMAND_RCV_UNKNOWN;
        payload = new byte[0];
        payloadLen = 0;
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

        assertTrue(called);
        assertEquals(Protocol.RcvCommand.COMMAND_RX_AX25_PACKET, command);
        assertEquals(4, payloadLen);
        assertArrayEquals(new byte[]{0x11, (byte) Protocol.KISS_FEND, 0x22, (byte) Protocol.KISS_FESC}, payload);
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
            Protocol.buildKv4pVendorPayload(Protocol.RcvCommand.COMMAND_SMETER_REPORT.getValue(), commandPayload));

        newParser().processBytes(frame);

        assertTrue(called);
        assertEquals(Protocol.RcvCommand.COMMAND_SMETER_REPORT, command);
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
        byte[] payload = new byte[]{
            0x10, 0x00,                  // firmware version 16
            'f',                         // radio module found
            0x00, 0x08, 0x00, 0x00,      // window size 2048
            0x01, 0x00, 0x00, 0x00,      // UHF module
            0x03,                        // has HL + physical PTT
        };

        java.util.Optional<Protocol.FirmwareVersion> parsed = Protocol.FirmwareVersion.from(payload, payload.length);

        assertTrue(parsed.isPresent());
        assertEquals(16, parsed.get().getVer());
        assertEquals(2048, parsed.get().getWindowSize());
        assertEquals(Protocol.RfModuleType.RF_SA818_UHF, parsed.get().getModuleType());
        assertTrue(parsed.get().isHasHl());
        assertTrue(parsed.get().isHasPhysPtt());
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
        });
    }
}
