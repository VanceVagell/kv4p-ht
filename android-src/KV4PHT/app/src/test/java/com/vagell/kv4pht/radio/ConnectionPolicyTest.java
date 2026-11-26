package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Verifies the single-active-radio policy that drives connection reconciliation. */
public class ConnectionPolicyTest {

    private static RadioDeviceInfo usb(String id, boolean connected) {
        return new RadioDeviceInfo(RadioDeviceInfo.Kind.USB, id, id, "", false, connected,
                false, true, FirmwareStatus.UNKNOWN, 0);
    }

    private static RadioDeviceInfo ble(String id, boolean paired, boolean connected,
            boolean visible) {
        return new RadioDeviceInfo(RadioDeviceInfo.Kind.BLE, id, id, "", paired, connected,
                false, visible, FirmwareStatus.UNKNOWN, -50);
    }

    private static ConnectionPolicy.Input input() {
        ConnectionPolicy.Input input = new ConnectionPolicy.Input();
        input.bluetoothReady = true;
        input.pairedAddresses = Collections.emptySet();
        input.suppressedAddresses = Collections.emptySet();
        input.usbDevices = Collections.emptyList();
        input.visibleBleDevices = Collections.emptyList();
        return input;
    }

    @Test public void usbIsConnectedWhenNothingIsActive() {
        ConnectionPolicy.Input input = input();
        input.usbDevices = Collections.singletonList(usb("usb-1", false));
        input.scanning = true;

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertEquals("usb-1", decision.connectUsbId);
        assertTrue(decision.stopScan);
        assertNull(decision.connectBleAddress);
    }

    @Test public void usbPreemptsActiveBluetoothRadio() {
        ConnectionPolicy.Input input = input();
        input.usbDevices = Collections.singletonList(usb("usb-1", false));
        input.activeDevice = ble("AA:BB", true, true, true);

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertEquals("usb-1", decision.connectUsbId);
        assertTrue(decision.cancelBleAttempt);
    }

    @Test public void usbPreemptsInFlightBluetoothAttempt() {
        ConnectionPolicy.Input input = input();
        input.connecting = true;
        input.connectingLink = RadioLinkType.BLE;
        input.usbDevices = Collections.singletonList(usb("usb-1", false));

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertEquals("usb-1", decision.connectUsbId);
        assertTrue(decision.cancelBleAttempt);
    }

    @Test public void activeUsbDoesNotReconnect() {
        ConnectionPolicy.Input input = input();
        input.usbDevices = Collections.singletonList(usb("usb-1", true));
        input.activeDevice = usb("usb-1", true);

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertNull(decision.connectUsbId);
        assertFalse(decision.cancelBleAttempt);
    }

    @Test public void activeBluetoothSuspendsScanning() {
        ConnectionPolicy.Input input = input();
        input.scanning = true;
        input.activeDevice = ble("AA:BB", true, true, true);

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertTrue(decision.stopScan);
        assertNull(decision.connectBleAddress);
        assertFalse(decision.startScan);
    }

    @Test public void unusableActiveRadioKeepsScanning() {
        ConnectionPolicy.Input input = input();
        input.activeDevice = ble("AA:BB", true, true, true);
        input.activeDeviceUnusable = true;

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertTrue(decision.startScan);
        assertNull(decision.connectBleAddress);
        assertFalse(decision.stopScan);
    }

    @Test public void bluetoothNotReadyStillStartsScannerForErrorReporting() {
        ConnectionPolicy.Input input = input();
        input.bluetoothReady = false;

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertTrue(decision.startScan);
        assertNull(decision.connectBleAddress);
    }

    @Test public void startsScanningWhenNothingIsConnected() {
        ConnectionPolicy.Input input = input();

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertTrue(decision.startScan);
        assertNull(decision.connectBleAddress);
    }

    @Test public void autoConnectsFirstVisiblePairedRadio() {
        ConnectionPolicy.Input input = input();
        input.scanning = true;
        input.visibleBleDevices = Arrays.asList(
                ble("unpaired", false, false, true),
                ble("paired-1", true, false, true),
                ble("paired-2", true, false, true));

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertEquals("paired-1", decision.connectBleAddress);
        assertFalse(decision.startScan);
    }

    @Test public void skipsSuppressedPairedRadio() {
        ConnectionPolicy.Input input = input();
        input.scanning = true;
        input.suppressedAddresses = new HashSet<>(Collections.singletonList("paired-1"));
        input.visibleBleDevices = Arrays.asList(
                ble("paired-1", true, false, true),
                ble("paired-2", true, false, true));

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertEquals("paired-2", decision.connectBleAddress);
    }

    @Test public void doesNotAutoConnectUnpairedRadio() {
        ConnectionPolicy.Input input = input();
        input.scanning = true;
        input.visibleBleDevices = Collections.singletonList(
                ble("unpaired", false, false, true));

        ConnectionPolicy.Decision decision = ConnectionPolicy.decide(input);

        assertNull(decision.connectBleAddress);
        assertFalse(decision.hasAction());
    }
}