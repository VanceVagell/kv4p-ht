package com.vagell.kv4pht.radio;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Snapshot of connection state consumed by the Connect radio screen. */
public final class RadioConnectionState {
    private final RadioDeviceInfo activeDevice;
    private final List<RadioDeviceInfo> usbDevices;
    private final List<RadioDeviceInfo> pairedBleDevices;
    private final List<RadioDeviceInfo> discoveredBleDevices;
    private final boolean scanning;
    private final boolean bluetoothReady;
    private final String bluetoothError;
    private final String connectionError;

    public RadioConnectionState(
            RadioDeviceInfo activeDevice,
            List<RadioDeviceInfo> usbDevices,
            List<RadioDeviceInfo> pairedBleDevices,
            List<RadioDeviceInfo> discoveredBleDevices,
            boolean scanning,
            boolean bluetoothReady,
            String bluetoothError,
            String connectionError) {
        this.activeDevice = activeDevice;
        this.usbDevices = immutable(usbDevices);
        this.pairedBleDevices = immutable(pairedBleDevices);
        this.discoveredBleDevices = immutable(discoveredBleDevices);
        this.scanning = scanning;
        this.bluetoothReady = bluetoothReady;
        this.bluetoothError = bluetoothError;
        this.connectionError = connectionError;
    }

    public static RadioConnectionState empty() {
        return new RadioConnectionState(null, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), false, false, null, null);
    }

    private static List<RadioDeviceInfo> immutable(List<RadioDeviceInfo> devices) {
        return devices == null ? Collections.emptyList()
                : Collections.unmodifiableList(devices);
    }

    public RadioDeviceInfo getActiveDevice() {
        return activeDevice;
    }

    public List<RadioDeviceInfo> getUsbDevices() {
        return usbDevices;
    }

    public List<RadioDeviceInfo> getPairedBleDevices() {
        return pairedBleDevices;
    }

    public List<RadioDeviceInfo> getDiscoveredBleDevices() {
        return discoveredBleDevices;
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isBluetoothReady() {
        return bluetoothReady;
    }

    public String getBluetoothError() {
        return bluetoothError;
    }

    public String getConnectionError() {
        return connectionError;
    }

    public boolean hasActiveDevice() {
        return activeDevice != null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RadioConnectionState)) {
            return false;
        }
        RadioConnectionState that = (RadioConnectionState) other;
        return scanning == that.scanning
                && bluetoothReady == that.bluetoothReady
                && Objects.equals(activeDevice, that.activeDevice)
                && Objects.equals(usbDevices, that.usbDevices)
                && Objects.equals(pairedBleDevices, that.pairedBleDevices)
                && Objects.equals(discoveredBleDevices, that.discoveredBleDevices)
                && Objects.equals(bluetoothError, that.bluetoothError)
                && Objects.equals(connectionError, that.connectionError);
    }

    @Override
    public int hashCode() {
        return Objects.hash(activeDevice, usbDevices, pairedBleDevices, discoveredBleDevices,
                scanning, bluetoothReady, bluetoothError, connectionError);
    }
}