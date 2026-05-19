package com.vagell.kv4pht.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import androidx.annotation.NonNull;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import android.util.Log;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Value;
import lombok.With;

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
    static final int KISS_MAX_ENCODED_FRAME_SIZE = 3 + (2 * (PROTO_MTU + KV4P_VENDOR_HEADER_LEN));

    public static final byte DRA818_25K = 0x01;
    public static final byte DRA818_12K5 = 0x00;


    private Protocol() {
    }

    private static ByteBuffer littleEndianView(ByteBuffer buffer) {
        return buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    @Getter
    public enum SndCommand {
        COMMAND_SND_UNKNOWN(0x00),
        COMMAND_HOST_TX_AUDIO(0x07), // [COMMAND_HOST_TX_AUDIO(byte[])]
        COMMAND_HOST_DESIRED_STATE(0x0D);
        private final int value;
        SndCommand(int value) {
            this.value = value;
        }
    }

    @Getter
    public enum RcvCommand {
        COMMAND_RCV_UNKNOWN(0x00),
        COMMAND_DEBUG_INFO(0x01),       // [COMMAND_DEBUG_INFO(char[])]
        COMMAND_DEBUG_ERROR(0x02),      // [COMMAND_DEBUG_ERROR(char[])]
        COMMAND_DEBUG_WARN(0x03),       // [COMMAND_DEBUG_WARN(char[])]
        COMMAND_DEBUG_DEBUG(0x04),      // [COMMAND_DEBUG_DEBUG(char[])]
        COMMAND_DEBUG_TRACE(0x05),      // [COMMAND_DEBUG_TRACE(char[])]
        COMMAND_HELLO(0x06),            // [COMMAND_HELLO(Hello)]
        COMMAND_RX_AUDIO(0x07),         // [COMMAND_RX_AUDIO(int8_t[])]
        COMMAND_WINDOW_UPDATE(0x09),    // [COMMAND_WINDOW_UPDATE()]
        COMMAND_DEVICE_STATE(0x0B);
        private static final RcvCommand[] VALUES = values();
        private final int value;
        RcvCommand(int value) {
            this.value = value;
        }
        public static RcvCommand fromValue(int value) {
            for (RcvCommand type : VALUES) {
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

    static final int HOST_STATE_RADIO_CONFIG_VALID = 1;
    static final int HOST_STATE_PTT_REQUESTED = 1 << 1;
    static final int HOST_STATE_RX_AUDIO_OPEN = 1 << 2;
    static final int HOST_STATE_HIGH_POWER = 1 << 3;
    static final int HOST_STATE_RSSI_ENABLED = 1 << 4;
    static final int HOST_STATE_FILTER_PRE = 1 << 5;
    static final int HOST_STATE_FILTER_HIGH = 1 << 6;
    static final int HOST_STATE_FILTER_LOW = 1 << 7;
    static final int DEVICE_STATE_PHYS_PTT_DOWN = 1 << 8;
    static final int DEVICE_STATE_TX_ACTIVE = 1 << 9;
    static final int DEVICE_STATE_SQUELCHED = 1 << 10;
    static final int HOST_STATE_TX_ALLOWED = 1 << 11;

    @Getter
    public enum DeviceMode {
        DEVICE_MODE_TX(0),
        DEVICE_MODE_RX(1),
        DEVICE_MODE_STOPPED(2),
        DEVICE_MODE_UNKNOWN(255);
        private final int value;
        DeviceMode(int value) {
            this.value = value;
        }
        public static DeviceMode fromValue(int value) {
            for (DeviceMode mode : DeviceMode.values()) {
                if (mode.getValue() == value) {
                    return mode;
                }
            }
            return DEVICE_MODE_UNKNOWN;
        }
    }

    @Value
    @Builder
    @With
    public static class HostDesiredState {
        static final int BYTE_LEN = 22;
        int sequence;
        int memoryId;
        int flags;
        byte bw;
        float freqTx;
        float freqRx;
        byte ctcssTx;
        byte squelch;
        byte ctcssRx;

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(BYTE_LEN).order(ByteOrder.LITTLE_ENDIAN);
            writeTo(buffer);
            return buffer.array();
        }

        void writeTo(ByteBuffer buffer) {
            buffer.putInt(sequence);
            buffer.putInt(memoryId);
            buffer.putShort((short) flags);
            buffer.put(bw);
            buffer.putFloat(freqTx);
            buffer.putFloat(freqRx);
            buffer.put(ctcssTx);
            buffer.put(squelch);
            buffer.put(ctcssRx);
        }
    }

    @Data
    @Builder
    public static class DeviceState {
        static final int BYTE_LEN = 26;
        private final int appliedSequence;
        private final int memoryId;
        private final int flags;
        private final byte bw;
        private final float freqTx;
        private final float freqRx;
        private final byte ctcssTx;
        private final byte squelch;
        private final byte ctcssRx;
        private final RadioStatus radioModuleStatus;
        private final DeviceMode mode;
        private final int lastError;
        private final int latestRssi;

        public boolean hasRadioConfig() {
            return (flags & HOST_STATE_RADIO_CONFIG_VALID) != 0;
        }

        public static Optional<DeviceState> from(final ByteBuffer buffer, int offset, Integer len) {
            return Optional.ofNullable(buffer)
                .filter(b -> len != null && len == BYTE_LEN && offset >= 0 && b.limit() >= offset + len)
                .map(Protocol::littleEndianView)
                .map(b -> DeviceState.builder()
                    .appliedSequence(b.getInt(offset))
                    .memoryId(b.getInt(offset + 4))
                    .flags(b.getShort(offset + 8) & 0xFFFF)
                    .bw(b.get(offset + 10))
                    .freqTx(b.getFloat(offset + 11))
                    .freqRx(b.getFloat(offset + 15))
                    .ctcssTx(b.get(offset + 19))
                    .squelch(b.get(offset + 20))
                    .ctcssRx(b.get(offset + 21))
                    .radioModuleStatus(RadioStatus.fromValue((char) b.get(offset + 22)))
                    .mode(DeviceMode.fromValue(b.get(offset + 23) & 0xFF))
                    .lastError(b.get(offset + 24) & 0xFF)
                    .latestRssi(b.get(offset + 25) & 0xFF)
                    .build());
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

    public static int calculateSMeter9Value(int sMeter255Value) {
        double result = 9.73 * Math.log(0.0297 * sMeter255Value) - 1.88;
        return Math.max(1, Math.min(9, (int) Math.round(result)));
    }

    @Data
    @Builder
    public static class FirmwareVersion {
        private static final int BYTE_LEN = 17;
        private final short ver;  // equivalent to uint16_t
        private final RadioStatus radioModuleStatus;  // equivalent to char
        private final int windowSize; // equivalent to uint32_t
        private final RfModuleType moduleType;
        private final float minRadioFreq;
        private final float maxRadioFreq;
        private final boolean hasHl;
        private final boolean hasPhysPtt;
        public static Optional<FirmwareVersion> from(final ByteBuffer buffer, int offset, Integer len) {
            return Optional.ofNullable(buffer)
                .filter(b -> len != null && len == BYTE_LEN && offset >= 0 && b.limit() >= offset + len)
                .map(Protocol::littleEndianView)
                .map(b -> {
                    int features = b.get(offset + 16) & 0xFF;
                    return FirmwareVersion.builder()
                        .ver(b.getShort(offset))
                        .radioModuleStatus(RadioStatus.fromValue((char) b.get(offset + 2)))
                        .windowSize(b.getInt(offset + 3))
                        .moduleType(RfModuleType.fromValue(b.get(offset + 7) & 0xFF))
                        .minRadioFreq(b.getFloat(offset + 8))
                        .maxRadioFreq(b.getFloat(offset + 12))
                        .hasHl((features & 0x01) != 0)
                        .hasPhysPtt((features & 0x02) != 0)
                        .build();
                });
        }
    }

    @Data
    @Builder
    public static class Hello {
        private static final int BYTE_LEN = FirmwareVersion.BYTE_LEN + DeviceState.BYTE_LEN;
        private final FirmwareVersion version;
        private final DeviceState deviceState;
        public static Optional<Hello> from(final ByteBuffer buffer, int offset, Integer len) {
            return Optional.ofNullable(buffer)
                .filter(b -> len != null && len == BYTE_LEN && offset >= 0 && b.limit() >= offset + len)
                .flatMap(b -> FirmwareVersion.from(b, offset, FirmwareVersion.BYTE_LEN)
                    .flatMap(version -> DeviceState.from(b, offset + FirmwareVersion.BYTE_LEN, DeviceState.BYTE_LEN)
                        .map(deviceState -> Hello.builder()
                            .version(version)
                            .deviceState(deviceState)
                            .build())));
        }
    }

    @Data
    @Builder
    public static class WindowUpdate {
        private final int size;
        public static Optional<WindowUpdate> from(final ByteBuffer buffer, int offset, Integer len) {
            return Optional.ofNullable(buffer)
                .filter(b -> len != null && len == 4 && offset >= 0 && b.limit() >= offset + len)
                .map(Protocol::littleEndianView)
                .map(b -> WindowUpdate.builder().size(b.getInt(offset)).build());
        }
    }

    public static class Sender {

        private final AtomicInteger flowControlWindow = new AtomicInteger(1024);
        private final SerialInputOutputManager usbIoManager;
        private final Lock lock = new ReentrantLock();
        private final Condition canSendCondition = lock.newCondition();
        private final byte[] kissEncodeBuffer = new byte[KISS_MAX_ENCODED_FRAME_SIZE];
        private final ByteBuffer desiredStateBuffer =
            ByteBuffer.allocate(HostDesiredState.BYTE_LEN).order(ByteOrder.LITTLE_ENDIAN);

        public Sender(SerialInputOutputManager usbIoManager) {
            this.usbIoManager = usbIoManager;
        }

        private synchronized void sendKissFrame(int kissCommand, byte[] payload, int len) {
            int frameSize = encodeKissFrame(kissCommand, payload, len);
            writeEncodedFrame(frameSize);
        }

        private synchronized void sendKv4pVendorFrame(SndCommand commandType, byte[] param, int len) {
            int frameSize = encodeKv4pVendorFrame(commandType.getValue(), param, len);
            writeEncodedFrame(frameSize);
        }

        private void sendKissDataFrame(byte[] ax25Bytes) {
            sendKissFrame(KISS_CMD_DATA, ax25Bytes, ax25Bytes != null ? ax25Bytes.length : 0);
        }

        public void txAudio(byte[] audio, int len) {
            sendKv4pVendorFrame(SndCommand.COMMAND_HOST_TX_AUDIO, audio, len);
        }

        public void txAx25(byte[] ax25Bytes) {
            sendKissDataFrame(ax25Bytes);
        }

        private int encodeKissFrame(int kissCommand, byte[] payload, int len) {
            int payloadLen = boundedPayloadLen(payload, len);
            int pos = beginKissFrame(kissCommand);
            for (int i = 0; i < payloadLen; i++) {
                pos = putEscaped(pos, payload[i] & 0xFF);
            }
            return endKissFrame(pos);
        }

        private int encodeKv4pVendorFrame(int kv4pCommand, byte[] payload, int len) {
            int payloadLen = boundedPayloadLen(payload, len);
            int pos = beginKv4pVendorFrame(kv4pCommand);
            for (int i = 0; i < payloadLen; i++) {
                pos = putEscaped(pos, payload[i] & 0xFF);
            }
            return endKissFrame(pos);
        }

        private int encodeKv4pVendorFrame(int kv4pCommand, ByteBuffer payload, int offset, int len) {
            int payloadLen = boundedPayloadLen(payload, offset, len);
            int pos = beginKv4pVendorFrame(kv4pCommand);
            for (int i = 0; i < payloadLen; i++) {
                pos = putEscaped(pos, payload.get(offset + i) & 0xFF);
            }
            return endKissFrame(pos);
        }

        private int beginKv4pVendorFrame(int kv4pCommand) {
            int pos = beginKissFrame(KISS_CMD_SETHARDWARE);
            for (byte prefixByte : KV4P_VENDOR_PREFIX) {
                pos = putEscaped(pos, prefixByte & 0xFF);
            }
            pos = putEscaped(pos, KV4P_PROTOCOL_VERSION);
            return putEscaped(pos, kv4pCommand);
        }

        private int beginKissFrame(int kissCommand) {
            kissEncodeBuffer[0] = (byte) KISS_FEND;
            kissEncodeBuffer[1] = (byte) (kissCommand & 0xFF);
            return 2;
        }

        private int putEscaped(int pos, int value) {
            if (value == KISS_FEND) {
                kissEncodeBuffer[pos++] = (byte) KISS_FESC;
                kissEncodeBuffer[pos++] = (byte) KISS_TFEND;
            } else if (value == KISS_FESC) {
                kissEncodeBuffer[pos++] = (byte) KISS_FESC;
                kissEncodeBuffer[pos++] = (byte) KISS_TFESC;
            } else {
                kissEncodeBuffer[pos++] = (byte) value;
            }
            return pos;
        }

        private int endKissFrame(int pos) {
            kissEncodeBuffer[pos++] = (byte) KISS_FEND;
            return pos;
        }

        private void writeEncodedFrame(int frameSize) {
            if (waitUntilCanSend(frameSize)) {
                usbIoManager.writeAsync(Arrays.copyOf(kissEncodeBuffer, frameSize));
                flowControlWindow.addAndGet(-frameSize);
            }
        }

        private int boundedPayloadLen(byte[] payload, int len) {
            if (payload == null || len <= 0) {
                return 0;
            }
            return Math.min(len, Math.min(payload.length, PROTO_MTU));
        }

        private int boundedPayloadLen(ByteBuffer payload, int offset, int len) {
            if (payload == null || offset < 0 || len <= 0 || payload.limit() < offset) {
                return 0;
            }
            return Math.min(len, Math.min(payload.limit() - offset, PROTO_MTU));
        }
        
        // Waits until it can send (windowSize > 0)
        private boolean waitUntilCanSend(int size) {
            lock.lock();
            try {
                while (flowControlWindow.get() < size) {
                    try {
                        canSendCondition.await();  // Waits until the flow-control window has enough space for this encoded frame.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            } finally {
                lock.unlock();
            }
            return true;
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

        public synchronized void sendDesiredState(@NonNull HostDesiredState state) {
            desiredStateBuffer.clear();
            state.writeTo(desiredStateBuffer);
            desiredStateBuffer.flip();
            int frameSize = encodeKv4pVendorFrame(SndCommand.COMMAND_HOST_DESIRED_STATE.getValue(), desiredStateBuffer, 0, HostDesiredState.BYTE_LEN);
            writeEncodedFrame(frameSize);
        }
    }

    @FunctionalInterface
    public interface CommandBufferConsumer {
        void accept(RcvCommand command, ByteBuffer buffer, int offset, int len);
    }

    @FunctionalInterface
    public interface BufferConsumer {
        void accept(ByteBuffer buffer, int offset, int len);
    }

    public static class KissParser {

        private final ByteBuffer frameBuffer = ByteBuffer.allocate(KISS_MAX_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        private int frameLen = 0;
        private boolean escape = false;
        private boolean dropFrame = false;
        private boolean inFrame = false;
        private final CommandBufferConsumer onCommand;
        private final BufferConsumer onAx25;

        public KissParser(CommandBufferConsumer onCommand, BufferConsumer onAx25) {
            this.onCommand = onCommand;
            this.onAx25 = onAx25;
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
            } else if (inFrame && !dropFrame && escape) {
                if (value == KISS_TFEND) {
                    appendByte((byte) KISS_FEND);
                } else if (value == KISS_TFESC) {
                    appendByte((byte) KISS_FESC);
                } else {
                    // Unknown KISS escape: drop this frame and wait for the next FEND.
                    dropFrame = true;
                }
                escape = false;
            } else if (inFrame && !dropFrame && value == KISS_FESC) {
                escape = true;
            } else if (inFrame && !dropFrame) {
                appendByte(b);
            }
        }

        private void appendByte(byte b) {
            if (frameLen >= KISS_MAX_FRAME_SIZE) {
                dropFrame = true;
                return;
            }
            frameBuffer.put(frameLen++, b);
        }

        private void processFrame() {
            prepareFrameBuffer();
            int kissCommandByte = frameBuffer.get(0) & 0xFF;
            int kissPort = kissCommandByte >> 4;
            int kissCommand = kissCommandByte & 0x0F;
            int payloadLen = frameLen - 1;

            if (kissPort != KISS_PORT_0) {
                return;
            }
            if (kissCommand == KISS_CMD_DATA) {
                if (payloadLen > 0 && payloadLen <= PROTO_MTU) {
                    onAx25.accept(frameBuffer, 1, payloadLen);
                }
            } else if (kissCommand == KISS_CMD_SETHARDWARE) {
                processVendorFrame(payloadLen);
            }
        }

        private void processVendorFrame(int payloadLen) {
            if (payloadLen < KV4P_VENDOR_HEADER_LEN) {
                return;
            }
            int payloadOffset = 1;
            for (int i = 0; i < KV4P_VENDOR_PREFIX.length; i++) {
                if (frameBuffer.get(payloadOffset + i) != KV4P_VENDOR_PREFIX[i]) {
                    return;
                }
            }
            if ((frameBuffer.get(payloadOffset + 4) & 0xFF) != KV4P_PROTOCOL_VERSION) {
                return;
            }

            int command = frameBuffer.get(payloadOffset + 5) & 0xFF;
            int commandPayloadOffset = payloadOffset + KV4P_VENDOR_HEADER_LEN;
            int commandPayloadLen = payloadLen - KV4P_VENDOR_HEADER_LEN;
            if (commandPayloadLen > PROTO_MTU) {
                return;
            }
            RcvCommand cmd = RcvCommand.fromValue(command);
            if (cmd == RcvCommand.COMMAND_RCV_UNKNOWN) {
                Log.w(TAG, "Unknown KV4P vendor cmd received from ESP32: 0x" + Integer.toHexString(command) + " paramLen=" + commandPayloadLen);
                return;
            }
            onCommand.accept(cmd, frameBuffer, commandPayloadOffset, commandPayloadLen);
        }

        private void prepareFrameBuffer() {
            frameBuffer.clear();
            frameBuffer.limit(frameLen);
            frameBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }

        private void resetParser() {
            frameLen = 0;
            frameBuffer.clear();
            frameBuffer.order(ByteOrder.LITTLE_ENDIAN);
            escape = false;
            dropFrame = false;
            inFrame = false;
        }
    }
}
