package com.vagell.kv4pht.radio;

/** Firmware state reported for a radio after the protocol handshake. */
public enum FirmwareStatus {
    UNKNOWN,
    OK,
    OUTDATED,
    MISSING
}