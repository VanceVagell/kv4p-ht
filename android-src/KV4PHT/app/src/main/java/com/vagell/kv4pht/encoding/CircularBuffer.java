package com.vagell.kv4pht.encoding;

import org.apache.commons.lang3.ArrayUtils;

import java.util.concurrent.LinkedBlockingQueue;

public class CircularBuffer {
    private final byte[] buffer;
    private int writePos = 0;
    private int size = 0;
    private final LinkedBlockingQueue<Byte[]> incomingBytes; // Temporary hold of incoming bytes
    private final Thread processingThread;

    public CircularBuffer(int capacity) {
        buffer = new byte[capacity];
        incomingBytes = new LinkedBlockingQueue<>();
        final CircularBuffer circularBuffer = this;

        // We offload copying data to a separate thread so we can just accept the data and move on,
        // since copying takes a non-trivial amount of time and would block unacceptably.
        processingThread = new Thread() {
            @Override
            public void run() {
                super.run();

                while (true) {
                    if (incomingBytes.isEmpty()) {
                        try {
                            sleep(1000);
                        } catch (InterruptedException e) {
                        }
                    } else {
                        // By processing these in bulk we can catch up more quickly than
                        // spawning a separate thread to handle every array.
                        while (!incomingBytes.isEmpty()) {
                            Byte[] byteObjs = incomingBytes.poll();
                            byte[] bytes = ArrayUtils.toPrimitive(byteObjs);

                            if (bytes == null) {
                                continue;
                            }

                            synchronized (circularBuffer) {
                                for (byte b : bytes) {
                                    buffer[writePos] = b;
                                    writePos = (writePos + 1) % buffer.length;
                                    if (size < buffer.length) {
                                        size++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        };
        processingThread.start();
    }

    public void write(byte[] data) {
        // We store the byte array in a temporary structure to avoid blocking on write().
        incomingBytes.offer(ArrayUtils.toObject(data));
        processingThread.interrupt();
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
        incomingBytes.clear();
    }
}
