package com.vagell.kv4pht.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
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

    public static final int PROTO_MTU = 2048; // Maximum length of the frame

    // KV4P KISS transport. Standard KISS DATA frames carry AX.25 packets.
    // kv4p-specific commands are carried in KISS SETHARDWARE vendor frames:
    // FEND 0x06 "KV4P" 0x01 <kv4pCommand> <payload...> FEND.
    static final int KISS_FEND = 0xC0;
    static final int KISS_FESC = 0xDB;
    static final int KISS_TFEND = 0xDC;
    static final int KISS_TFESC = 0xDD;
    static final int KISS_CMD_DATA = 0x00;
    static final int KISS_CMD_SETHARDWARE = 0x06;
    static final int KISS_PORT_0 = 0x00;
    static final int KV4P_PROTOCOL_VERSION = 0x01;
    static final byte[] KV4P_VENDOR_PREFIX = new byte[]{'K', 'V', '4', 'P'};
    static final int KV4P_VENDOR_HEADER_LEN = KV4P_VENDOR_PREFIX.length + 2; // "KV4P" + version + kv4pCommand
    static final int KISS_MAX_FRAME_SIZE = PROTO_MTU + 1 + KV4P_VENDOR_HEADER_LEN;

    public static final byte DRA818_25K = 0x01;
    public static final byte DRA818_12K5 = 0x00;


    private Protocol() {
    }

    private static int boundedPayloadLen(byte[] payload, int len) {
        if (payload == null || len <= 0) {
            return 0;
        }
        return Math.min(len, Math.min(payload.length, PROTO_MTU));
    }

    static byte[] buildKissFrame(int kissCommand, byte[] payload) {
        return buildKissFrame(kissCommand, payload, payload != null ? payload.length : 0);
    }

    static byte[] buildKissFrame(int kissCommand, byte[] payload, int len) {
        int payloadLen = boundedPayloadLen(payload, len);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(payloadLen + 3);
        buffer.write(KISS_FEND);
        buffer.write(kissCommand & 0xFF);
        for (int i = 0; i < payloadLen; i++) {
            int b = payload[i] & 0xFF;
            if (b == KISS_FEND) {
                buffer.write(KISS_FESC);
                buffer.write(KISS_TFEND);
            } else if (b == KISS_FESC) {
                buffer.write(KISS_FESC);
                buffer.write(KISS_TFESC);
            } else {
                buffer.write(b);
            }
        }
        buffer.write(KISS_FEND);
        return buffer.toByteArray();
    }

    static byte[] buildKv4pVendorPayload(int kv4pCommand, byte[] param) {
        return buildKv4pVendorPayload(kv4pCommand, param, param != null ? param.length : 0);
    }

    static byte[] buildKv4pVendorPayload(int kv4pCommand, byte[] param, int len) {
        int paramLen = boundedPayloadLen(param, len);
        ByteBuffer payload = ByteBuffer.allocate(KV4P_VENDOR_HEADER_LEN + paramLen);
        payload.put(KV4P_VENDOR_PREFIX);
        payload.put((byte) KV4P_PROTOCOL_VERSION);
        payload.put((byte) kv4pCommand);
        if (paramLen > 0) {
            payload.put(param, 0, paramLen);
        }
        return payload.array();
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
        COMMAND_HOST_RSSI(0x09),     // [COMMAND_HOST_RSSI(ON)]
        COMMAND_HOST_TX_AX25(0x0A);  // Internal dispatch for KISS DATA AX.25 frames.
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
        COMMAND_WINDOW_UPDATE(0x09),    // [COMMAND_WINDOW_UPDATE()]
        COMMAND_RX_AX25_PACKET(0x0A);   // Internal dispatch for KISS DATA AX.25 frames.
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
                .filter(p -> len != null && len == 1 && p.length >= len)
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
        public static Optional<FirmwareVersion> from(final byte[] param, Integer len) {
            return Optional.ofNullable(param)
                .filter(p -> len != null && len == 12 && p.length >= len)
                .map(ByteBuffer::wrap)
                .map(b -> b.order(ByteOrder.LITTLE_ENDIAN))
                .map(b -> {
                    short ver = b.getShort();
                    RadioStatus radioModuleStatus = RadioStatus.fromValue((char) b.get());
                    int windowSize = b.getInt();
                    RfModuleType moduleType = RfModuleType.fromValue(b.getInt());
                    int features = b.get() & 0xFF;
                    return FirmwareVersion.builder()
                        .ver(ver)
                        .radioModuleStatus(radioModuleStatus)
                        .windowSize(windowSize)
                        .moduleType(moduleType)
                        .hasHl((features & 0x01) != 0)
                        .hasPhysPtt((features & 0x02) != 0)
                        .build();
                });
        }
    }

    @Data
    @Builder
    public static class WindowUpdate {
        private final int size;
        public static Optional<WindowUpdate> from(final byte[] param, Integer len) {
            return Optional.ofNullable(param)
                .filter(p -> len != null && len == 4 && p.length >= len)
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

        private void sendKissFrame(int kissCommand, byte[] payload) {
            sendKissFrame(kissCommand, payload, payload != null ? payload.length : 0);
        }

        private void sendKissFrame(int kissCommand, byte[] payload, int len) {
            byte[] frame = Protocol.buildKissFrame(kissCommand, payload, len);
            int frameSize = frame.length;
            waitUntilCanSend(frameSize);
            usbIoManager.writeAsync(frame);
            flowControlWindow.addAndGet(-frameSize);
        }

        private void sendKv4pVendorFrame(SndCommand commandType, byte[] param) {
            sendKv4pVendorFrame(commandType, param, param != null ? param.length : 0);
        }

        private void sendKv4pVendorFrame(SndCommand commandType, byte[] param, int len) {
            sendKissFrame(KISS_CMD_SETHARDWARE, buildKv4pVendorPayload(commandType.getValue(), param, len));
        }

        private void sendKissDataFrame(byte[] ax25Bytes) {
            byte[] payload = ax25Bytes == null ? new byte[0] : Arrays.copyOf(ax25Bytes, Math.min(ax25Bytes.length, PROTO_MTU));
            sendKissFrame(KISS_CMD_DATA, payload);
        }

        public void pttDown() {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_PTT_DOWN, null);
        }

        public void pttUp() {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_PTT_UP, null);
        }

        public void group(Group group) {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_GROUP, group.toBytes());
        }

        public void filters(Filters filters) {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_FILTERS, filters.toBytes());
        }

        public void stop() {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_STOP, null);
        }

        public void config(Config config) {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_CONFIG, config.toBytes());
        }

        public void txAudio(byte[] audio) {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_TX_AUDIO, audio);
        }

        public void txAudio(byte[] audio, int len) {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_TX_AUDIO, audio, len);
        }

        public void txAx25(byte[] ax25Bytes) {
            sendKissDataFrame(ax25Bytes);
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
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_HL, state.toBytes());
        }

        public void setRssi(RSSIState state) {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_RSSI, state.toBytes());
        }
    }

    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    public static class KissParser {

        private final byte[] frame = new byte[KISS_MAX_FRAME_SIZE];
        private final byte[] commandParams = new byte[PROTO_MTU];
        private int frameLen = 0;
        private boolean escape = false;
        private boolean dropFrame = false;
        private boolean inFrame = false;
        private final TriConsumer<RcvCommand, byte[], Integer> onCommand;

        public KissParser(TriConsumer<RcvCommand, byte[], Integer> onCommand) {
            this.onCommand = onCommand;
        }

        public void processBytes(byte[] newData) {
            for (byte b : newData) {
                processByte(b);
            }
        }

        private void processByte(byte b) {
            int value = b & 0xFF;
            if (value == KISS_FEND) {
                if (frameLen > 0 && !dropFrame) {
                    processFrame();
                }
                resetParser();
                inFrame = true;
            } else if (!inFrame) {
                return;
            } else if (dropFrame) {
                return;
            } else if (escape) {
                if (value == KISS_TFEND) {
                    appendByte((byte) KISS_FEND);
                } else if (value == KISS_TFESC) {
                    appendByte((byte) KISS_FESC);
                } else {
                    // Unknown KISS escape: drop this frame and wait for the next FEND.
                    dropFrame = true;
                }
                escape = false;
            } else if (value == KISS_FESC) {
                escape = true;
            } else {
                appendByte(b);
            }
        }

        private void appendByte(byte b) {
            if (frameLen >= KISS_MAX_FRAME_SIZE) {
                dropFrame = true;
                return;
            }
            frame[frameLen++] = b;
        }

        private void processFrame() {
            int kissCommandByte = frame[0] & 0xFF;
            int kissPort = kissCommandByte >> 4;
            int kissCommand = kissCommandByte & 0x0F;
            int payloadLen = frameLen - 1;

            if (kissPort != KISS_PORT_0) {
                return;
            }
            if (kissCommand == KISS_CMD_DATA) {
                if (payloadLen > 0 && payloadLen <= PROTO_MTU) {
                    onCommand.accept(
                        RcvCommand.COMMAND_RX_AX25_PACKET,
                        Arrays.copyOfRange(frame, 1, frameLen),
                        payloadLen);
                }
            } else if (kissCommand == KISS_CMD_SETHARDWARE) {
                processVendorFrame(payloadLen);
            } else {
                Log.w(TAG, "Unknown KISS cmd received from ESP32: 0x" + Integer.toHexString(kissCommand));
            }
        }

        private void processVendorFrame(int payloadLen) {
            if (payloadLen < KV4P_VENDOR_HEADER_LEN) {
                return;
            }
            int payloadOffset = 1;
            for (int i = 0; i < KV4P_VENDOR_PREFIX.length; i++) {
                if (frame[payloadOffset + i] != KV4P_VENDOR_PREFIX[i]) {
                    return;
                }
            }
            if ((frame[payloadOffset + 4] & 0xFF) != KV4P_PROTOCOL_VERSION) {
                return;
            }

            int command = frame[payloadOffset + 5] & 0xFF;
            int commandPayloadOffset = payloadOffset + KV4P_VENDOR_HEADER_LEN;
            int commandPayloadLen = payloadLen - KV4P_VENDOR_HEADER_LEN;
            if (commandPayloadLen > commandParams.length) {
                return;
            }
            RcvCommand cmd = RcvCommand.fromValue(command);
            if (cmd == RcvCommand.COMMAND_RCV_UNKNOWN) {
                Log.w(TAG, "Unknown KV4P vendor cmd received from ESP32: 0x" + Integer.toHexString(command) + " paramLen=" + commandPayloadLen);
                return;
            }
            System.arraycopy(frame, commandPayloadOffset, commandParams, 0, commandPayloadLen);
            onCommand.accept(cmd, commandParams, commandPayloadLen);
        }

        private void resetParser() {
            frameLen = 0;
            escape = false;
            dropFrame = false;
            inFrame = false;
        }
    }
}
