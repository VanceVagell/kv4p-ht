package com.vagell.kv4pht.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FirmwareVersion {
    private final short ver;  // equivalent to uint16_t
    private final String radioModuleStatus;  // equivalent to char
    private final HardwareVersion hardwareVersion;
    public static FirmwareVersion from(final byte[] param) {
        ByteBuffer buffer = ByteBuffer.wrap(param);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return FirmwareVersion.builder()
                .ver(buffer.getShort())
                .radioModuleStatus(String.valueOf((char) buffer.get()))
                .hardwareVersion(HardwareVersion.fromValue(buffer.get() & 0xFF))
                .build();
    }
}
