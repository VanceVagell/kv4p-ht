package com.vagell.kv4pht.firmware.bearconsole;

public enum UploadSTM32Errors {
    // reference
    // http://stackoverflow.com/questions/446663/best-way-to-define-error-codes-strings-in-java
    AVR_CHIPTYPE    (1,     "Unexpected AVR chip type."),
    FILE_OPEN       (2,     "Cannot open file."),
    HEX_FILE_OPEN   (3,     "Illegal .hex file."),
    CHIP_INIT       (4,     "Cannot initialize a chip."),
    SIGNATURE       (5,     "Incorrect chip type."),
    PAGE_WRITE      (6,     "An unexpected error occurred while writing"),
    OPEN_DEVICE     (7,     "Cannot open device."),
    NO_ERROR        (0, "");

    private final int code;
    private final String description;

    private UploadSTM32Errors(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public int getCode() {
        return code;
    }

    @Override
    public String toString() {
        return description;
    }
}