package com.vagell.kv4pht.radio;

import lombok.Getter;

/**
 * Software gain applied to received audio before it's written to the AudioTrack. Useful when
 * the radio output is quieter than music apps (e.g. on Bluetooth headsets that use the media
 * volume curve for both). Samples are clamped to [-1, 1] after multiplication to avoid wrap.
 */
public enum RxGainBoost {
    NONE(1.0f, "None"),
    LOW(2.0f, "Low"),
    MED(4.0f, "Med"),
    HIGH(8.0f, "High");

    @Getter
    private final float gain;
    private final String label;

    RxGainBoost(float gain, String label) {
        this.gain = gain;
        this.label = label;
    }

    public static RxGainBoost parse(String str) {
        for (RxGainBoost value : values()) {
            if (value.label.equalsIgnoreCase(str)) {
                return value;
            }
        }
        return NONE;
    }
}
