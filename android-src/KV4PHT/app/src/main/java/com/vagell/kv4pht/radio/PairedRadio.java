package com.vagell.kv4pht.radio;

import java.util.Objects;

/** A Bluetooth radio the user has chosen to remember for automatic reconnection. */
public final class PairedRadio {
    private final String address;
    private final String name;
    private final long chipId;

    public PairedRadio(String address, String name) {
        this(address, name, 0);
    }

    public PairedRadio(String address, String name, long chipId) {
        this.address = address;
        this.name = name == null || name.isEmpty() ? address : name;
        this.chipId = chipId;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    /** Stable chip identity reported by the radio, or 0 when it is not known yet. */
    public long getChipId() {
        return chipId;
    }

    public boolean hasChipId() {
        return chipId != 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PairedRadio)) {
            return false;
        }
        PairedRadio that = (PairedRadio) other;
        return chipId == that.chipId
                && Objects.equals(address, that.address)
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, name, chipId);
    }
}