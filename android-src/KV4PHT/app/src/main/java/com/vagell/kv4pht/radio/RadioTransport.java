package com.vagell.kv4pht.radio;

interface RadioTransport extends AutoCloseable {
    interface Listener {
        void onBytes(byte[] bytes);
        void onReady();
        void onDisconnected();
        void onError(Exception error);
    }

    void start(Listener listener);

    @Override
    void close();

    boolean isReady();
    void writeAsync(byte[] bytes);
    boolean supportsFirmwareFlashing();
    boolean prepareForFirmwareFlashing();
    String getName();
}
