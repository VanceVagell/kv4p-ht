package com.vagell.kv4pht.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.hoho.android.usbserial.util.SerialInputOutputManager;

import android.util.Log;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

public final class Protocol {
    private static final String TAG = Protocol.class.getSimpleName();

    // Delimiter must match ESP32 code
    static final byte[] COMMAND_DELIMITER = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

    public static final byte DRA818_25K = 0x01;
    public static final byte DRA818_12K5 = 0x00;

    public static final int PROTO_MTU = 2048; // Maximum length of the frame


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
        COMMAND_HOST_TX_AUDIO(0x07), // [COMMAND_HOST_TX_AUDIO(byte[])]
        COMMAND_HOST_HL(0x08),       // [COMMAND_HOST_HL(Hl)]
        COMMAND_HOST_RSSI(0x09);     // [COMMAND_HOST_RSSI(ON)]
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
        COMMAND_VERSION(0x08),          // [COMMAND_VERSION(Version)]
        COMMAND_WINDOW_UPDATE(0x09);    // [COMMAND_WINDOW_UPDATE()]
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

    @Data
    @Builder
    public static class Config {
        private final boolean isHigh;
        public byte[] toBytes() {
            byte[] bytes = new byte[1];
            bytes[0] = isHigh ? (byte) 0x01 : (byte) 0x00;
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

    @Data
    @Builder
    public static class HlState {
        private final boolean isHighPower;
        public byte[] toBytes() {
            byte result = isHighPower ? (byte) 0x01 : (byte) 0x00;
            return new byte[]{result};
        }
    }

    @Data
    @Builder
    public static class RSSIState {
        private final boolean on;
        public byte[] toBytes() {
            byte result = on ? (byte) 0x01 : (byte) 0x00;
            return new byte[]{result};
        }
    }

    @Getter
    public enum RfModuleType {
        RF_SA818_VHF(0),
        RF_SA818_UHF(1);
        private final int value;
        RfModuleType(int value) {
            this.value = value;
        }
        public static RfModuleType fromValue(int value) {
            for (RfModuleType moduleType : RfModuleType.values()) {
                if (moduleType.getValue() == value) {
                    return moduleType;
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
        private final int windowSize; // equivalent to size_t
        private final RfModuleType moduleType;
        private final boolean hasHl;
        private final boolean hasPhysPtt;
        private final String gitCommitId;
        private final String gitBranch;
        private final String gitCommitDate;
        private final String gitTag;
        private final boolean gitDirty;
        private final String chipModel;
        private final String buildTime;
        private final String sketchMd5;
        public static Optional<FirmwareVersion> from(final byte[] param, Integer len) {
            return Optional.ofNullable(param)
                .filter(p -> len == 126) // exact struct size
                .map(ByteBuffer::wrap)
                .map(b -> b.order(ByteOrder.LITTLE_ENDIAN))
                .map(b -> FirmwareVersion.builder()
                    .ver(b.getShort())
                    .radioModuleStatus(RadioStatus.fromValue((char) b.get()))
                    .windowSize(Short.toUnsignedInt(b.getShort()))
                    .moduleType(RfModuleType.fromValue(Byte.toUnsignedInt(b.get())))
                    .setFeatures(b.get())
                    .chipModel(readString(b, 16))
                    .buildTime(readString(b, 20))
                    .sketchMd5(readString(b, 33))
                    .gitCommitId(readString(b, 8))
                    .gitBranch(readString(b, 16))
                    .gitCommitDate(readString(b, 11))
                    .gitTag(readString(b, 16))
                    .gitDirty(b.get() != 0)
                    .build());
        }
        public static class FirmwareVersionBuilder {
            public FirmwareVersionBuilder setFeatures(byte features) {
                this.hasHl((features & 0x01) != 0);
                this.hasPhysPtt((features & 0x02) != 0);
                return this;
            }
        }
        private static String readString(ByteBuffer b, int length) {
            byte[] strBytes = new byte[length];
            b.get(strBytes);
            int zeroIndex = 0;
            while (zeroIndex < strBytes.length && strBytes[zeroIndex] != 0) zeroIndex++;
            return new String(strBytes, 0, zeroIndex).trim();
        }
    }

    @Data
    @Builder
    public static class WindowUpdate {
        private final int size;
        public static Optional<WindowUpdate> from(final byte[] param, Integer len) {
            return Optional.ofNullable(param)
                .filter(p -> len == 4)
                .map(ByteBuffer::wrap)
                .map(b -> b.order(ByteOrder.LITTLE_ENDIAN))
                .map(b -> WindowUpdate.builder().size(b.getInt()).build());
        }
    }

    public static class Sender {

        private final AtomicInteger flowControlWindow = new AtomicInteger(1024);
        private final SerialInputOutputManager usbIoManager;
        private final Lock lock = new ReentrantLock();
        private final Condition canSendCondition = lock.newCondition();

        public Sender(SerialInputOutputManager usbIoManager) {
            this.usbIoManager = usbIoManager;
        }

        private void sendCommand(SndCommand commandType, byte[] param) {
            int frameSize = COMMAND_DELIMITER.length + 1 + 2 + (param != null ? param.length : 0);
            waitUntilCanSend(frameSize);
            ByteBuffer buffer = ByteBuffer.allocate(frameSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(COMMAND_DELIMITER);
            buffer.put((byte) commandType.getValue());
            if (param != null) {
                buffer.putShort((short) param.length);
                buffer.put(param);
            } else {
                buffer.putShort((short) 0);
            }
            usbIoManager.writeAsync(buffer.array());
            flowControlWindow.addAndGet(-frameSize);
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
            sendCommand(SndCommand.COMMAND_HOST_TX_AUDIO, audio);
        }
        
        // Waits until it can send (windowSize > 0)
        private void waitUntilCanSend(int size) {
            lock.lock();
            try {
                while (flowControlWindow.get() <= size) {
                    try {
                        canSendCondition.await();  // Wait until signaled that windowSize > 0
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        public void setFlowControlWindow(int size) {
            lock.lock();
            try {
                flowControlWindow.set(size);
                canSendCondition.signalAll();  // Signal all waiting threads that they can proceed
            } finally {
                lock.unlock();
            }
        }

        public void enlargeFlowControlWindow(int size) {
            lock.lock();
            try {
                flowControlWindow.addAndGet(size);
                canSendCondition.signalAll();  // Signal all waiting threads that they can proceed
            } finally {
                lock.unlock();
            }
        }

        public void setHighPower(HlState state) {
            sendCommand(SndCommand.COMMAND_HOST_HL, state.toBytes());
        }

        public void setRssi(RSSIState state) {
            sendCommand(SndCommand.COMMAND_HOST_RSSI, state.toBytes());
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
                matchedDelimiterTokens++;
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length + 2) {
                commandParamLen = (b & 0xFF) << 8 | commandParamLen;
                paramIndex = 0;
                matchedDelimiterTokens++;
                if (commandParamLen == 0) {
                    processCommand();
                    resetParser();
                }
                if (commandParamLen > commandParams.length) {
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