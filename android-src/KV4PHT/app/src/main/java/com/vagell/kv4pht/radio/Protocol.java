package com.vagell.kv4pht.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.vagell.kv4pht.javAX25.ax25.Arrays;

import android.util.Log;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

public final class Protocol {
    private static final String TAG = Protocol.class.getSimpleName();

    // Delimiter must match ESP32 code
    static final byte[] COMMAND_DELIMITER = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, (byte) 0xDE,
        (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

    public static final byte DRA818_25K = 0x01;
    public static final byte DRA818_12K5 = 0x00;

    public static final int PROTO_MTU = 0xFF; // Maximum length of the frame

    private Protocol() {
    }

    @Getter
    public enum SndCommand {
        COMMAND_SND_UNKNOWN(0x00),
        COMMAND_HOST_PTT_DOWN(0x01), // [COMMAND_HOST_PTT_DOWN()]
        COMMAND_HOST_PTT_UP(0x02),   // [COMMAND_HOST_PTT_UP()]
        COMMAND_HOST_GROUP(0x03),    // [COMMAND_HOST_GROUP(Group)]
        COMMAND_HOST_FILTERS(0x04),  // [COMMAND_HOST_FILTERS(Filters)]
        COMMAND_HOST_STOP(0x05),     // [COMMAND_HOST_STOP()]
        COMMAND_HOST_CONFIG(0x06),   // [COMMAND_HOST_CONFIG(Config)] -> [COMMAND_VERSION(Version)]
        COMMAND_HOST_TX_AUDIO(0x07); // [COMMAND_HOST_TX_AUDIO(byte[])]
        private final int value;
        SndCommand(int value) {
            this.value = value;
        }
    }

    @Getter
    public enum RcvCommand {
        COMMAND_RCV_UNKNOWN(0x00),
        COMMAND_SMETER_REPORT( 0x53),   // [COMMAND_SMETER_REPORT(Rssi)]
        COMMAND_PHYS_PTT_DOWN(0x44),    // [COMMAND_PHYS_PTT_DOWN()]
        COMMAND_PHYS_PTT_UP(0x55),      // [COMMAND_PHYS_PTT_UP()]
        COMMAND_DEBUG_INFO(0x01),       // [COMMAND_DEBUG_INFO(char[])]
        COMMAND_DEBUG_ERROR(0x02),      // [COMMAND_DEBUG_ERROR(char[])]
        COMMAND_DEBUG_WARN(0x03),       // [COMMAND_DEBUG_WARN(char[])]
        COMMAND_DEBUG_DEBUG(0x04),      // [COMMAND_DEBUG_DEBUG(char[])]
        COMMAND_DEBUG_TRACE(0x05),      // [COMMAND_DEBUG_TRACE(char[])]
        COMMAND_HELLO(0x06),            // [COMMAND_HELLO()]
        COMMAND_RX_AUDIO(0x07),         // [COMMAND_RX_AUDIO(int8_t[])]
        COMMAND_VERSION(0x08);          // [COMMAND_VERSION(Version)]
        private final int value;
        RcvCommand(int value) {
            this.value = value;
        }
        public static RcvCommand fromValue(int value) {
            for (RcvCommand type : RcvCommand.values()) {
                if (type.getValue() == value) {
                    return type;
                }
            }
            return COMMAND_RCV_UNKNOWN;
        }
    }

    @Getter
    public enum RadioStatus {
        RADIO_STATUS_UNKNOWN('u'),
        RADIO_STATUS_NOT_FOUND('x'),
        RADIO_STATUS_FOUND('f');
        private final char value;
        RadioStatus(char value) {
            this.value = value;
        }
        public static RadioStatus fromValue(char value) {
            for (RadioStatus status : RadioStatus.values()) {
                if (status.getValue() == value) {
                    return status;
                }
            }
            return RADIO_STATUS_UNKNOWN;
        }
    }

    @Getter
    public enum ModuleType {
        SA818_VHF(0x4),
        SA818_UHF(0x1 | 0x4);
        private final int value;
        ModuleType(int value) {
            this.value = value;
        }
        public static ModuleType fromValue(int value) {
            for (ModuleType version : ModuleType.values()) {
                if (version.getValue() == value) {
                    return version;
                }
            }
            throw new IllegalArgumentException("Unexpected value: " + value);
        }
    }

    @Data
    @Builder
    public static class Config {
        private final ModuleType moduleType;
        public byte[] toBytes() {
            byte[] bytes = new byte[1];
            bytes[0] = (byte) moduleType.getValue();
            return bytes;
        }
    }

    @Data
    @Builder
    public static class Filters {
        private final boolean pre;
        private final boolean high;
        private final boolean low;
        public byte[] toBytes() {
            byte result = 0;
            if (pre) result |= 0x01;
            if (high) result |= 0x02;
            if (low) result |= 0x04;
            return new byte[]{result};
        }
    }

    @Data
    @Builder
    public static class Group {
        private final byte bw;
        private final float freqTx;
        private final float freqRx;
        private final byte ctcssTx;
        private final byte squelch;
        private final byte ctcssRx;
        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(bw);
            buffer.putFloat(freqTx);
            buffer.putFloat(freqRx);
            buffer.put(ctcssTx);
            buffer.put(squelch);
            buffer.put(ctcssRx);
            return buffer.array();
        }
    }

    @Getter
    public enum HardwareVersion {
        HW_VER_V1(0x00),
        HW_VER_V2_0C(0xFF),
        HW_VER_V2_0D(0xF0);
        private final int value;
        HardwareVersion(int value) {
            this.value = value;
        }
        public static HardwareVersion fromValue(int value) {
            for (HardwareVersion version : HardwareVersion.values()) {
                if (version.getValue() == value) {
                    return version;
                }
            }
            throw new IllegalArgumentException("Unexpected value: " + value);
        }
    }

    @Data
    @Builder
    public static class Rssi {
        private final int sMeter9Value;
        public static Optional<Rssi> from(final byte[] param, Integer len) {
            return Optional.ofNullable(param)
                .filter(p -> len == 1)
                .map(p -> p[0] & 0xFF)
                .map(Rssi::calculateSMeter9Value)
                .map(rssi -> Rssi.builder().sMeter9Value(rssi).build());
        }
        private static int calculateSMeter9Value(int sMeter255Value) {
            double result = 9.73 * Math.log(0.0297 * sMeter255Value) - 1.88;
            return Math.max(1, Math.min(9, (int) Math.round(result)));
        }
    }

    @Data
    @Builder
    public static class FirmwareVersion {
        private final short ver;  // equivalent to uint16_t
        private final RadioStatus radioModuleStatus;  // equivalent to char
        private final HardwareVersion hardwareVersion;
        public static Optional<FirmwareVersion> from(final byte[] param, Integer len) {
            return Optional.ofNullable(param)
                .filter(p -> len == 4)
                .map(ByteBuffer::wrap)
                .map(b -> b.order(ByteOrder.LITTLE_ENDIAN))
                .map(b -> FirmwareVersion.builder()
                    .ver(b.getShort())
                    .radioModuleStatus(RadioStatus.fromValue((char) b.get()))
                    .hardwareVersion(HardwareVersion.fromValue(b.get() & 0xFF))
                    .build());
        }
    }

    public static class Sender {

        private final SerialInputOutputManager usbIoManager;

        public Sender(SerialInputOutputManager usbIoManager) {
            this.usbIoManager = usbIoManager;
        }

        private void sendCommand(SndCommand commandType, byte[] param) {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            buffer.put(COMMAND_DELIMITER);
            buffer.put((byte) commandType.getValue());
            if (param != null) {
                buffer.put((byte) param.length);
                buffer.put(param);
            } else {
                buffer.put((byte) 0);
            }
            byte[] command = new byte[buffer.position()];
            buffer.flip();
            buffer.get(command);
            usbIoManager.writeAsync(command);
        }

        public void pttDown() {
            sendCommand(SndCommand.COMMAND_HOST_PTT_DOWN, null);
        }

        public void pttUp() {
            sendCommand(SndCommand.COMMAND_HOST_PTT_UP, null);
        }

        public void group(Group group) {
            sendCommand(SndCommand.COMMAND_HOST_GROUP, group.toBytes());
        }

        public void filters(Filters filters) {
            sendCommand(SndCommand.COMMAND_HOST_FILTERS, filters.toBytes());
        }

        public void stop() {
            sendCommand(SndCommand.COMMAND_HOST_STOP, null);
        }

        public void config(Config config) {
            sendCommand(SndCommand.COMMAND_HOST_CONFIG, config.toBytes());
        }

        public void txAudio(byte[] audio) {
            int offset = 0;
            while (offset < audio.length) {
                int chunkSize = Math.min(PROTO_MTU, audio.length - offset);
                byte[] chunk = Arrays.copyOfRange(audio, offset, offset + chunkSize);
                sendCommand(SndCommand.COMMAND_HOST_TX_AUDIO, chunk);
                offset += chunkSize;
            }
        }
    }

    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    public static class FrameParser {

        private int matchedDelimiterTokens = 0;
        private byte command;
        private int commandParamLen;
        private final byte[] commandParams = new byte[PROTO_MTU];
        private int paramIndex;
        private final TriConsumer<RcvCommand, byte[], Integer> onCommand;

        public FrameParser(TriConsumer<RcvCommand, byte[], Integer> onCommand) {
            this.onCommand = onCommand;
        }

        public void processBytes(byte[] newData) {
            for (byte b : newData) {
                processByte(b);
            }
        }

        private void processByte(byte b) {
            if (matchedDelimiterTokens < COMMAND_DELIMITER.length) {
                matchedDelimiterTokens = (b == COMMAND_DELIMITER[matchedDelimiterTokens]) ? matchedDelimiterTokens + 1 : 0;
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length) {
                command = b;
                matchedDelimiterTokens++;
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length + 1) {
                commandParamLen = b & 0xFF;
                paramIndex = 0;
                matchedDelimiterTokens++;
                if (commandParamLen == 0) {
                    processCommand();
                    resetParser();
                }
            } else {
                if (paramIndex < commandParamLen) {
                    commandParams[paramIndex++] = b;
                }
                matchedDelimiterTokens++;
                if (paramIndex == commandParamLen) {
                    processCommand();
                    resetParser();
                }
            }
        }

        private void processCommand() {
            RcvCommand cmd = RcvCommand.fromValue(this.command);
            if (cmd != RcvCommand.COMMAND_RCV_UNKNOWN) {
                onCommand.accept(cmd, commandParams, commandParamLen);
            } else {
                Log.w(TAG, "Unknown cmd received from ESP32: 0x" + Integer.toHexString(this.command & 0xFF) + " paramLen=" + commandParamLen);
            }
        }

        private void resetParser() {
            matchedDelimiterTokens = 0;
            paramIndex = 0;
            commandParamLen = 0;
        }
    }
}