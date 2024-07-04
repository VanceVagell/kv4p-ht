package com.vagell.kv4pht.encoding;

public class CircularBuffer {
    private final byte[] buffer;
    private int writePos = 0;
    private int size = 0;

    public CircularBuffer(int capacity) {
        buffer = new byte[capacity];
    }

    public void write(byte[] data) {
        for (byte b : data) {
            buffer[writePos] = b;
            writePos = (writePos + 1) % buffer.length;
            if (size < buffer.length) {
                size++;
            }
        }
    }

    public byte[] read(int length) {
        if (length > size) {
            length = size;
        }
        byte[] result = new byte[length];
        int readPos = (writePos - size + buffer.length) % buffer.length;
        for (int i = 0; i < length; i++) {
            result[i] = buffer[(readPos + i) % buffer.length];
        }
        size -= length;
        return result;
    }

    public int getSize() {
        return size > 0 ? size : 0; // TODO figure out why size is sometimes negative (some logic bug?)
    }

    public int getCapacity() {
        return buffer.length;
    }

    public void reset() {
        writePos = 0;
        size = 0;
    }
}
