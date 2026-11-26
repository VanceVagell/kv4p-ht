package com.vagell.kv4pht.radio;

import java.util.Objects;

/** A Bluetooth radio the user has chosen to remember for automatic reconnection. */
public final class PairedRadio {
    private final String address;
    private final String name;

    public PairedRadio(String address, String name) {
        this.address = address;
        this.name = name == null || name.isEmpty() ? address : name;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
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
        return Objects.equals(address, that.address) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address, name);
    }
}