package com.vagell.kv4pht.radio;

import android.os.Handler;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;

final class UsbSerialRadioTransport implements RadioTransport {
    private static final String TAG = UsbSerialRadioTransport.class.getSimpleName();

    private final UsbSerialPort serialPort;
    private final Handler handler;
    private SerialInputOutputManager ioManager;
    private Listener listener;

    UsbSerialRadioTransport(UsbSerialPort serialPort, Handler handler) {
        this.serialPort = serialPort;
        this.handler = handler;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        ioManager = new SerialInputOutputManager(serialPort, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                byte[] copy = data.clone();
                handler.post(() -> {
                    if (UsbSerialRadioTransport.this.listener != null) {
                        UsbSerialRadioTransport.this.listener.onBytes(copy);
                    }
                });
            }

            @Override
            public void onRunError(Exception error) {
                handler.post(() -> {
                    if (UsbSerialRadioTransport.this.listener != null) {
                        UsbSerialRadioTransport.this.listener.onError(error);
                    }
                });
            }
        });
        ioManager.setWriteBufferSize(90000);
        ioManager.setReadBufferSize(1024);
        ioManager.setReadBufferCount(16 * 2);
        ioManager.start();
        listener.onReady();
    }

    @Override
    public void close() {
        listener = null;
        if (ioManager != null) {
            try {
                ioManager.stop();
            } catch (Exception ignored) {
                // Best-effort teardown.
            }
            ioManager = null;
        }
        try {
            serialPort.close();
        } catch (Exception ignored) {
            // Best-effort teardown.
        }
    }

    @Override
    public boolean isReady() {
        return ioManager != null;
    }

    @Override
    public void writeAsync(byte[] bytes) {
        SerialInputOutputManager manager = ioManager;
        if (manager != null) {
            manager.writeAsync(bytes);
        }
    }

    @Override
    public boolean supportsFirmwareFlashing() {
        return true;
    }

    @Override
    public boolean prepareForFirmwareFlashing() {
        if (ioManager == null) {
            return false;
        }
        ioManager.stop();
        try {
            serialPort.setDTR(false);
            serialPort.setRTS(true);
            Thread.sleep(100);
            serialPort.setDTR(true);
            serialPort.setRTS(false);
            Thread.sleep(50);
            serialPort.setDTR(false);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            Log.e(TAG, "Error restarting ESP32 for firmware flashing", error);
        }
        return false;
    }

    UsbSerialPort getSerialPort() {
        return serialPort;
    }

    @Override
    public String getName() {
        return "USB serial";
    }
}
