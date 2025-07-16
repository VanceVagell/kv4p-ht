/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.util;

import android.hardware.usb.UsbRequest;
import android.os.Process;
import android.util.Log;
import androidx.annotation.VisibleForTesting;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Utility class which services a {@link UsbSerialPort} for reading and writing in background threads.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialInputOutputManager {

    public enum State {
        STOPPED,
        STARTING,
        STARTING_STAGE2,
        RUNNING,
        STOPPING
    }

    public static boolean DEBUG = false;

    private static final String TAG = SerialInputOutputManager.class.getSimpleName();
    private static final int BUFSIZ = 4096;

    private int mWriteTimeout = 0;

    private final Object mWriteBufferLock = new Object();

    private int mReadBufferSize; // default size = getReadEndpoint().getMaxPacketSize()
    private int mReadBufferCount = 4;
    private ByteBuffer mWriteBuffer = ByteBuffer.allocate(BUFSIZ);

    private int mThreadPriority = Process.THREAD_PRIORITY_URGENT_AUDIO;
    private final AtomicReference<State> mState = new AtomicReference<>(State.STOPPED);
    private CountDownLatch mStartuplatch = new CountDownLatch(2);
    private CountDownLatch mShutdownlatch = new CountDownLatch(2);
    private Listener mListener; // Synchronized by 'this'
    private final UsbSerialPort mSerialPort;
    private Supplier<UsbRequest> mRequestSupplier = UsbRequest::new;

    public interface Listener {
        /**
         * Called when new incoming data is available.
         */
        void onNewData(byte[] data);

        /**
         * Called when service thread  aborts due to an error.
         */
        void onRunError(Exception e);
    }

    public SerialInputOutputManager(UsbSerialPort serialPort) {
        mSerialPort = serialPort;
        mReadBufferSize = serialPort.getReadEndpoint().getMaxPacketSize();
    }

    public SerialInputOutputManager(UsbSerialPort serialPort, Listener listener) {
        mSerialPort = serialPort;
        mListener = listener;
        mReadBufferSize = serialPort.getReadEndpoint().getMaxPacketSize();
    }

    public synchronized void setListener(Listener listener) {
        mListener = listener;
    }

    public synchronized Listener getListener() {
        return mListener;
    }

    /**
     * setThreadPriority. By default a higher priority than UI thread is used to prevent data loss
     *
     * @param threadPriority  see {@link Process#setThreadPriority(int)}
     * */
    public void setThreadPriority(int threadPriority) {
        if (!mState.compareAndSet(State.STOPPED, State.STOPPED)) {
            throw new IllegalStateException("threadPriority only configurable before SerialInputOutputManager is started");
        }
        mThreadPriority = threadPriority;
    }

    /**
     * read buffer count
     */
    public int getReadBufferCount() {
        return mReadBufferCount;
    }

    /**
     * read buffer count
     */
    public void setReadBufferCount(int mReadBuffeCount) {
        if (!mState.compareAndSet(State.STOPPED, State.STOPPED)) {
            throw new IllegalStateException("ReadBufferCount only configurable before SerialInputOutputManager is started");
        }
        this.mReadBufferCount = mReadBuffeCount;
    }

    public void setWriteTimeout(int timeout) {
        mWriteTimeout = timeout;
    }

    public int getWriteTimeout() {
        return mWriteTimeout;
    }

    /**
     * read/write buffer size
     */
    public void setReadBufferSize(int bufferSize) {
        if (getReadBufferSize() != bufferSize) {
            if (!mState.compareAndSet(State.STOPPED, State.STOPPED)) {
                throw new IllegalStateException("ReadBuffeCount only configurable before SerialInputOutputManager is started");
            }
            mReadBufferSize = bufferSize;
        }
    }

    public int getReadBufferSize() {
        return mReadBufferSize;
    }

    @VisibleForTesting
    void setRequestSupplier(Supplier<UsbRequest> mRequestSupplier) {
        this.mRequestSupplier = mRequestSupplier;
    }

    public void setWriteBufferSize(int bufferSize) {
        if(getWriteBufferSize() == bufferSize)
            return;
        synchronized (mWriteBufferLock) {
            ByteBuffer newWriteBuffer = ByteBuffer.allocate(bufferSize);
            if(mWriteBuffer.position() > 0)
                newWriteBuffer.put(mWriteBuffer.array(), 0, mWriteBuffer.position());
            mWriteBuffer = newWriteBuffer;
        }
    }

    public int getWriteBufferSize() {
        return mWriteBuffer.capacity();
    }

    /**
     * when using writeAsync, it is recommended to use readTimeout != 0,
     * else the write will be delayed until read data is available
     */
    public void writeAsync(byte[] data) {
        synchronized (mWriteBufferLock) {
            while (mWriteBuffer.remaining() < data.length) {
                try {
                    mWriteBufferLock.wait(); // Block until space is available in the buffer
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Restore the interrupt flag
                    return; // Exit gracefully
                }
            }
            mWriteBuffer.put(data);
            mWriteBufferLock.notifyAll(); // Notify waiting threads
        }
    }

    /**
     * start SerialInputOutputManager in separate threads
     */
    public void start() {
        if(mState.compareAndSet(State.STOPPED, State.STARTING)) {
            mStartuplatch = new CountDownLatch(2);
            mShutdownlatch = new CountDownLatch(2);
            new ServiceReadThread(this.getClass().getSimpleName() + "_read").start();
            new ServiceWriteThread(this.getClass().getSimpleName() + "_write").start();
            try {
                mStartuplatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            throw new IllegalStateException("already started");
        }
    }

    /**
     * stop SerialInputOutputManager threads
     * when using readTimeout == 0 (default), additionally use usbSerialPort.close() to
     * interrupt blocking read
     */
    public void stop() {
        if(mState.compareAndSet(State.RUNNING, State.STOPPING)) {
            synchronized (mWriteBufferLock) {
                mWriteBufferLock.notifyAll(); // Wake up any waiting thread to check the stop condition
            }
            Log.i(TAG, "Stop requested");
        }
    }

    public State getState() {
        return mState.get();
    }

    abstract class ServiceThread extends Thread {

        ServiceThread(String name) {
            setName(name);
        }

        private boolean isStillRunning() {
            SerialInputOutputManager.State state = mState.get();
            return ((state == SerialInputOutputManager.State.RUNNING) || (state == SerialInputOutputManager.State.STARTING) || (state == SerialInputOutputManager.State.STARTING_STAGE2))
                && (mShutdownlatch.getCount() == 2)
                && !Thread.currentThread().isInterrupted();
        }

        private void notifyErrorListener(Throwable e) {
            if ((getListener() != null) && (mState.get() == SerialInputOutputManager.State.RUNNING)) {
                try {
                    getListener().onRunError(e instanceof Exception ? (Exception) e : new Exception(e));
                } catch (Throwable t) {
                    Log.w(TAG, "Exception in onRunError: " + t.getMessage(), t);
                }
            }
        }

        private void startThread() {
            if (mThreadPriority != Process.THREAD_PRIORITY_DEFAULT) {
                Process.setThreadPriority(mThreadPriority);
            }
            if (!mState.compareAndSet(SerialInputOutputManager.State.STARTING, SerialInputOutputManager.State.STARTING_STAGE2)) {
                if (mState.compareAndSet(SerialInputOutputManager.State.STARTING_STAGE2, SerialInputOutputManager.State.RUNNING)) {
                    Log.i(TAG, getName() + ": Started mState=" + mState.get());
                }
            }
            mStartuplatch.countDown();
        }

        private void finalizeThread() {
            if (!mState.compareAndSet(SerialInputOutputManager.State.RUNNING, SerialInputOutputManager.State.STOPPING)) {
                if (mState.compareAndSet(SerialInputOutputManager.State.STOPPING, SerialInputOutputManager.State.STOPPED)) {
                    Log.i(TAG, getName() + ": Stopped mState=" + mState.get());
                }
            }
            mShutdownlatch.countDown();
        }

        abstract void init();
        abstract void step() throws IOException, InterruptedException;

        @Override
        public void run() {
            try {
                startThread();
                init();
                do {
                    step();
                } while (isStillRunning());
                Log.i(TAG, getName() + ": Stopping mState=" + mState.get());
            } catch (Throwable e) {
                if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "Thread interrupted, stopping " + getName());
                } else {
                    Log.w(TAG,  getName() + " ending due to exception: " + e.getMessage(), e);
                    notifyErrorListener(e);
                }
            } finally {
                finalizeThread();
            }
        }
    }

    class ServiceReadThread extends ServiceThread {

        private final List<UsbRequest> mReadPool = new ArrayList<>();

        ServiceReadThread(String name) {
            super(name);
        }

        @Override
        void init() {
            // Initialize buffers and requests
            for (int i = 0; i < mReadBufferCount; i++) {
                ByteBuffer buffer = ByteBuffer.allocate(mReadBufferSize);
                UsbRequest request = new UsbRequest();
                request.setClientData(buffer);
                request.initialize(mSerialPort.getConnection(), mSerialPort.getReadEndpoint());
                request.queue(buffer);
                mReadPool.add(request);
            }
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                // Clean up read pool
                for (UsbRequest request : mReadPool) {
                    request.cancel();
                    request.close();
                }
            }
        }

        @Override
        void step() throws IOException {
            // Wait for the request to complete
            final UsbRequest completedRequest = mSerialPort.getConnection().requestWait();
            if (completedRequest != null) {
                final ByteBuffer completedBuffer = (ByteBuffer) completedRequest.getClientData();
                completedBuffer.flip(); // Prepare for reading
                final byte[] data = new byte[completedBuffer.remaining()];
                completedBuffer.get(data);
                if ((getListener() != null) && (data.length > 0)) {
                    getListener().onNewData(data); // Handle data
                }
                completedBuffer.clear(); // Prepare for reuse
                // Requeue the buffer and handle potential failures
                if (!completedRequest.queue(completedBuffer)) {
                    Log.e(TAG, "Failed to requeue the buffer");
                    throw new IOException("Failed to requeue the buffer");
                }
            } else {
                Log.e(TAG, "Error waiting for request");
                throw new IOException("Error waiting for request");
            }
        }
    }

    class ServiceWriteThread extends ServiceThread {

        ServiceWriteThread(String name) {
            super(name);
        }

        @Override
        void init() {
        }

        @Override
        void step() throws IOException, InterruptedException {
            // Handle outgoing data.
            byte[] buffer = null;
            synchronized (mWriteBufferLock) {
                int len = mWriteBuffer.position();
                if (len > 0) {
                    buffer = new byte[len];
                    mWriteBuffer.rewind();
                    mWriteBuffer.get(buffer, 0, len);
                    mWriteBuffer.clear();
                    mWriteBufferLock.notifyAll(); // Notify writeAsync that there is space in the buffer
                } else {
                    mWriteBufferLock.wait();
                }
            }
            if (buffer != null) {
                if (DEBUG) {
                    Log.d(TAG, "Writing data len=" + buffer.length);
                }
                mSerialPort.write(buffer, mWriteTimeout);
            }
        }
    }
}
