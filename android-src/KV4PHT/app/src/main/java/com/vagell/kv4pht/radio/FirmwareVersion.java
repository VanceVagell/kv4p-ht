package com.vagell.kv4pht.radio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FirmwareVersion {
    private final short ver;  // equivalent to uint16_t
    private final char radioModuleStatus;  // equivalent to char
    private final HardwareVersion hardwareVersion;
    public static Optional<FirmwareVersion> from(final byte[] param) {
        return Optional.ofNullable(param)
                .filter(p -> p.length == 4)
                .map(ByteBuffer::wrap)
                .map(b -> b.order(ByteOrder.LITTLE_ENDIAN))
                .map(b -> FirmwareVersion.builder()
                        .ver(b.getShort())
                        .radioModuleStatus((char) b.get())
                        .hardwareVersion(HardwareVersion.fromValue(b.get() & 0xFF))
                        .build());
    }
}
