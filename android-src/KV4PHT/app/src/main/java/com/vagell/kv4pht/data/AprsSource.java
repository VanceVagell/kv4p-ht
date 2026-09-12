package com.vagell.kv4pht.data;

/** Transport direction/source values stored with physical APRS packet records. */
public final class AprsSource {
    public static final String UNKNOWN = "UNKNOWN";
    public static final String RX_RF = "RX_RF";
    public static final String TX_RF = "TX_RF";
    public static final String RX_APRS_IS = "RX_APRS_IS";
    public static final String TX_APRS_IS = "TX_APRS_IS";

    private AprsSource() {
    }
}
