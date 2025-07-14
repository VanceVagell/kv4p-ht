package com.vagell.kv4pht.radio;

import lombok.Getter;

public enum MicGainBoost {
    NONE(1.0f, "None"),
    LOW(1.5f, "Low"),
    MED(2.0f, "Med"),
    HIGH(2.5f, "High");

    @Getter
    private final float gain;
    private final String label;

    MicGainBoost(float gain, String label) {
        this.gain = gain;
        this.label = label;
    }

    public static MicGainBoost parse(String str) {
        for (MicGainBoost value : values()) {
            if (value.label.equalsIgnoreCase(str)) {
                return value;
            }
        }
        return NONE;
    }
}
