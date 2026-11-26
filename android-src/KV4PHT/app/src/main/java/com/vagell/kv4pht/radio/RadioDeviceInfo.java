package com.vagell.kv4pht.radio;

import java.util.Objects;

/** Immutable description of one radio that can be shown in the Connect radio screen. */
public final class RadioDeviceInfo {
    public enum Kind { USB, BLE }

    private final Kind kind;
    private final String id;
    private final String name;
    private final String detail;
    private final boolean paired;
    private final boolean connected;
    private final boolean connecting;
    private final boolean visible;
    private final FirmwareStatus firmwareStatus;
    private final int rssi;

    public RadioDeviceInfo(
            Kind kind,
            String id,
            String name,
            String detail,
            boolean paired,
            boolean connected,
            boolean connecting,
            boolean visible,
            FirmwareStatus firmwareStatus,
            int rssi) {
        this.kind = kind;
        this.id = id == null ? "" : id;
        this.name = name == null || name.isEmpty() ? this.id : name;
        this.detail = detail == null ? "" : detail;
        this.paired = paired;
        this.connected = connected;
        this.connecting = connecting;
        this.visible = visible;
        this.firmwareStatus = firmwareStatus == null ? FirmwareStatus.UNKNOWN : firmwareStatus;
        this.rssi = rssi;
    }

    public Kind getKind() {
        return kind;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isPaired() {
        return paired;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isConnecting() {
        return connecting;
    }

    public boolean isVisible() {
        return visible;
    }

    public FirmwareStatus getFirmwareStatus() {
        return firmwareStatus;
    }

    public int getRssi() {
        return rssi;
    }

    public boolean isFirmwareUpdateRequired() {
        return firmwareStatus == FirmwareStatus.OUTDATED || firmwareStatus == FirmwareStatus.MISSING;
    }

    public String displayNameWithTransport() {
        return name + (kind == Kind.USB ? " (USB)" : " (BT)");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RadioDeviceInfo)) {
            return false;
        }
        RadioDeviceInfo that = (RadioDeviceInfo) other;
        // RSSI is intentionally excluded: it changes constantly while scanning and is not shown
        // in the UI, so treating it as a state change would rebind the list several times a second.
        return paired == that.paired
                && connected == that.connected
                && connecting == that.connecting
                && visible == that.visible
                && kind == that.kind
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(detail, that.detail)
                && firmwareStatus == that.firmwareStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, id, name, detail, paired, connected, connecting, visible,
                firmwareStatus);
    }
}