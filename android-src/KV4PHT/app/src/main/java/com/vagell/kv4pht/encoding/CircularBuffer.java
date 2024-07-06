package com.vagell.kv4pht.encoding;

public class CircularBuffer {
    private final byte[] buffer;
    private int writePos = 0;
    private int size = 0;

    public CircularBuffer(int capacity) {
        buffer = new byte[capacity];
    }

    public synchronized void write(byte[] data) {
        for (byte b : data) {
            buffer[writePos] = b;
            writePos = (writePos + 1) % buffer.length;
            if (size < buffer.length) {
                size++;
            }
        }
    }

    public synchronized byte[] readAll() {
        // Unspool the circular buffer out into a linear representation ending at writePos.
        byte[] result = new byte[size];
        int readPos = (writePos - size + buffer.length) % buffer.length;
        for (int i = 0; i < size; i++) {
            result[i] = buffer[(readPos + i) % buffer.length];
        }
        return result;
    }

    public synchronized void reset() {
        writePos = 0;
        size = 0;
    }
}
