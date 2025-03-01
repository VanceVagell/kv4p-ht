package com.vagell.kv4pht.radio;

import static com.vagell.kv4pht.radio.RadioAudioService.COMMAND_DELIMITER;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.vagell.kv4pht.javAX25.ax25.Arrays;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

public class HostToEsp32 {

    public static final byte DRA818_25K = 0x01;
    public static final byte DRA818_12K5 = 0x00;

    private final SerialInputOutputManager usbIoManager;

    public HostToEsp32(SerialInputOutputManager usbIoManager) {
        this.usbIoManager = usbIoManager;
    }

    @Getter
    public enum CommandType {
        COMMAND_HOST_UNKNOWN(0x00),
        COMMAND_HOST_PTT_DOWN(1), // [COMMAND_HOST_PTT_DOWN()]
        COMMAND_HOST_PTT_UP(2),   // [COMMAND_HOST_PTT_UP()]
        COMMAND_HOST_GROUP(3),    // [COMMAND_HOST_GROUP(Group)]
        COMMAND_HOST_FILTERS(4),  // [COMMAND_HOST_FILTERS(Filters)]
        COMMAND_HOST_STOP(5),     // [COMMAND_HOST_STOP()]
        COMMAND_HOST_CONFIG(6),   // [COMMAND_HOST_CONFIG(Config)] -> [COMMAND_VERSION(Version)]
        COMMAND_HOST_TX_AUDIO(7); // [COMMAND_HOST_TX_AUDIO(byte[])]
        private final int value;
        CommandType(int value) {
            this.value = value;
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

    private void sendCommand(CommandType commandType, byte[] param) {
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
        sendCommand(CommandType.COMMAND_HOST_PTT_DOWN, null);
    }

    public void pttUp() {
        sendCommand(CommandType.COMMAND_HOST_PTT_UP, null);
    }

    public void group(Group group) {
        sendCommand(CommandType.COMMAND_HOST_GROUP, group.toBytes());
    }

    public void filters(Filters filters) {
        sendCommand(CommandType.COMMAND_HOST_FILTERS, filters.toBytes());
    }

    public void stop() {
        sendCommand(CommandType.COMMAND_HOST_STOP, null);
    }

    public void config(Config config) {
        sendCommand(CommandType.COMMAND_HOST_CONFIG, config.toBytes());
    }

    public void txAudio(byte[] audio) {
        int offset = 0;
        while (offset < audio.length) {
            int chunkSize = Math.min(255, audio.length - offset);
            byte[] chunk = Arrays.copyOfRange(audio, offset, offset + chunkSize);
            sendCommand(CommandType.COMMAND_HOST_TX_AUDIO, chunk);
            offset += chunkSize;
        }
    }
}