package com.vagell.kv4pht.radio;

import lombok.Builder;
import lombok.Data;
import java.util.Optional;

@Data
@Builder
public class Rssi {
    private final int sMeter9Value;
    public static Optional<Rssi> from(final byte[] param) {
        return Optional.ofNullable(param)
            .filter(p -> p.length == 1)
            .map(p -> p[0] & 0xFF)
            .map(Rssi::calculateSMeter9Value)
            .map(rssi -> Rssi.builder().sMeter9Value(rssi).build());
    }
    private static int calculateSMeter9Value(int sMeter255Value) {
        double result = 9.73 * Math.log(0.0297 * sMeter255Value) - 1.88;
        return Math.max(1, Math.min(9, (int) Math.round(result)));
    }
}