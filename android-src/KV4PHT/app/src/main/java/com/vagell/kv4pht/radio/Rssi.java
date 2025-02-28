package com.vagell.kv4pht.radio;

public class Rssi {

    private final int sMeter9Value;

    public Rssi(final byte[] param) {
        if (param.length == 1) {
            int sMeter255Value = param[0] & 0xFF;
            this.sMeter9Value = calculateSMeter9Value(sMeter255Value);
        } else {
            this.sMeter9Value = 0;
        }
    }

    private int calculateSMeter9Value(int sMeter255Value) {
        if (sMeter255Value >= 101) return 9;
        if (sMeter255Value >= 87) return 8;
        if (sMeter255Value >= 76) return 7;
        if (sMeter255Value >= 68) return 6;
        if (sMeter255Value >= 61) return 5;
        if (sMeter255Value >= 55) return 4;
        if (sMeter255Value >= 50) return 3;
        if (sMeter255Value >= 46) return 2;
        return 1;
    }

    public int getSMeter9Value() {
        return sMeter9Value;
    }
}