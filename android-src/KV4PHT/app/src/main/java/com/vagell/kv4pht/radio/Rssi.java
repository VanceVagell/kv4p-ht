package com.vagell.kv4pht.radio;

import java.util.Optional;

public class Rssi {

    private final int sMeter9Value;
    
    public Rssi(final byte[] param) {
        this.sMeter9Value = Optional.ofNullable(param)
            .filter(p -> p.length == 1)
            .map(p -> p[0] & 0xFF)
            .map(this::calculateSMeter9Value)
            .orElse(0);
    }

    private int calculateSMeter9Value(int sMeter255Value) {
        double result = 9.73 * Math.log(0.0297 * sMeter255Value) - 1.88;
        return Math.max(1, Math.min(9, (int) Math.round(result)));
    }

    public int getSMeter9Value() {
        return sMeter9Value;
    }
}