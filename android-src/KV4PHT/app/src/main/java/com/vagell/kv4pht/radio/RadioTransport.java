package com.vagell.kv4pht.radio;

interface RadioTransport {
    void close();
    boolean isReady();
    void writeAsync(byte[] bytes);
    String getName();
}
