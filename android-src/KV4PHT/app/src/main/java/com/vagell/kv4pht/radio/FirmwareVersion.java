package com.vagell.kv4pht.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FirmwareVersion {

    private final short ver;  // equivalent to uint16_t
    private final byte radioModuleStatus;  // equivalent to char
    private final HardwareVersion hardwareVersion;

    public FirmwareVersion(final byte[] param) {
        ByteBuffer buffer = ByteBuffer.wrap(param);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        this.ver = buffer.getShort();  // Read the 16-bit unsigned value
        this.radioModuleStatus = buffer.get();  // Read the 8-bit char (1 byte)
        this.hardwareVersion = HardwareVersion.fromValue(buffer.get() & 0xFF);  // Read the 8-bit char (1 byte)
    }

    public short getVer() {
        return ver;
    }

    public String getRadioModuleStatus() {
        return String.valueOf((char) radioModuleStatus);
    }

    public HardwareVersion getHardwareVersion() {
        return hardwareVersion;
    }
}
