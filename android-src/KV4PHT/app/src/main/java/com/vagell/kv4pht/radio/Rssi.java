package com.vagell.kv4pht.radio;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Rssi {

    private final int sMeter9Value;
    private static final Map<Integer, Integer> sMeterMap = new HashMap<>();

    static {
        for (int i = 101; i <= 255; i++) sMeterMap.put(i, 9);
        for (int i = 87; i < 101; i++) sMeterMap.put(i, 8);
        for (int i = 76; i < 87; i++) sMeterMap.put(i, 7);
        for (int i = 68; i < 76; i++) sMeterMap.put(i, 6);
        for (int i = 61; i < 68; i++) sMeterMap.put(i, 5);
        for (int i = 55; i < 61; i++) sMeterMap.put(i, 4);
        for (int i = 50; i < 55; i++) sMeterMap.put(i, 3);
        for (int i = 46; i < 50; i++) sMeterMap.put(i, 2);
        for (int i = 0; i < 46; i++) sMeterMap.put(i, 1);
    }

    public Rssi(final byte[] param) {
        this.sMeter9Value = Optional.ofNullable(param)
            .filter(p -> p.length == 1)
            .map(p -> p[0] & 0xFF)
            .map(sMeterMap::get)
            .orElse(0);
    }

    public int getSMeter9Value() {
        return sMeter9Value;
    }
}