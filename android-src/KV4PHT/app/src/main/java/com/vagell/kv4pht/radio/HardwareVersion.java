package com.vagell.kv4pht.radio;

public enum HardwareVersion {

    HW_VER_V1(0x00),
    HW_VER_V2_0C(0xFF),
    HW_VER_V2_0D(0xF0);

    private final int value;

    HardwareVersion(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static HardwareVersion fromValue(int value) {
        for (HardwareVersion version : HardwareVersion.values()) {
            if (version.getValue() == value) {
                return version;
            }
        }
        throw new IllegalArgumentException("Unexpected value: " + value);
    }
}
