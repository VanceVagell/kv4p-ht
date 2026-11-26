package com.vagell.kv4pht.radio;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Pure connection policy. USB always wins, only one radio is active at a time, and Bluetooth
 * radios are auto-connected only when the user has paired them.
 */
public final class ConnectionPolicy {
    private ConnectionPolicy() {
    }

    public static final class Input {
        public boolean bluetoothReady;
        public boolean scanning;
        public boolean connecting;
        public RadioLinkType connectingLink = RadioLinkType.NONE;
        public RadioDeviceInfo activeDevice;
        public boolean activeDeviceUnusable;
        public List<RadioDeviceInfo> usbDevices = Collections.emptyList();
        public List<RadioDeviceInfo> visibleBleDevices = Collections.emptyList();
        public Set<String> pairedAddresses = Collections.emptySet();
        public Set<String> suppressedAddresses = Collections.emptySet();
    }

    public static final class Decision {
        public boolean startScan;
        public boolean stopScan;
        public boolean cancelBleAttempt;
        public String connectUsbId;
        public String connectBleAddress;

        public boolean hasAction() {
            return startScan || stopScan || cancelBleAttempt || connectUsbId != null
                    || connectBleAddress != null;
        }
    }

    public static Decision decide(Input input) {
        Decision decision = new Decision();
        boolean hasUsb = input.usbDevices != null && !input.usbDevices.isEmpty();

        if (input.connecting) {
            // A USB radio always preempts an in-flight Bluetooth attempt.
            if (hasUsb && input.connectingLink == RadioLinkType.BLE) {
                decision.cancelBleAttempt = true;
                decision.stopScan = input.scanning;
                decision.connectUsbId = input.usbDevices.get(0).getId();
            }
            return decision;
        }

        if (hasUsb) {
            decision.stopScan = input.scanning;
            if (input.activeDevice != null
                    && input.activeDevice.getKind() == RadioDeviceInfo.Kind.BLE) {
                decision.cancelBleAttempt = true;
                decision.connectUsbId = input.usbDevices.get(0).getId();
            } else if (input.activeDevice == null) {
                decision.connectUsbId = input.usbDevices.get(0).getId();
            }
            return decision;
        }

        if (input.activeDevice != null) {
            if (input.activeDeviceUnusable) {
                // A radio with unusable firmware is still connected, but the app can keep looking
                // for a usable radio without auto-connecting anything else.
                decision.startScan = !input.scanning;
                return decision;
            }
            decision.stopScan = input.scanning;
            return decision;
        }

        // Keep the scanner enabled even when Bluetooth is unavailable so it can report a useful
        // error to the Connect radio screen and recover automatically once Bluetooth is turned on.
        decision.startScan = !input.scanning;
        if (!input.bluetoothReady) {
            return decision;
        }
        if (input.scanning && input.visibleBleDevices != null) {
            for (RadioDeviceInfo device : input.visibleBleDevices) {
                if (device.isPaired()
                        && !input.suppressedAddresses.contains(device.getId())) {
                    decision.connectBleAddress = device.getId();
                    break;
                }
            }
        }
        return decision;
    }
}