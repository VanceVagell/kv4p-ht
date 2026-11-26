package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioDeviceInfoTest {

    @Test public void appendsTransportShorthandToDisplayName() {
        RadioDeviceInfo usb = new RadioDeviceInfo(RadioDeviceInfo.Kind.USB, "usb-1",
                "KV4PHT-ABC123", "", false, true, false, true, FirmwareStatus.OK, 0);
        RadioDeviceInfo ble = new RadioDeviceInfo(RadioDeviceInfo.Kind.BLE, "AA:BB",
                "KV4PHT-XYZ987", "", true, true, false, true, FirmwareStatus.OK, -40);

        assertEquals("KV4PHT-ABC123 (USB)", usb.displayNameWithTransport());
        assertEquals("KV4PHT-XYZ987 (BT)", ble.displayNameWithTransport());
    }

    @Test public void fallsBackToIdWhenNameIsMissing() {
        RadioDeviceInfo device = new RadioDeviceInfo(RadioDeviceInfo.Kind.USB, "1A2B:3C4D",
                null, "", false, false, false, true, FirmwareStatus.UNKNOWN, 0);

        assertEquals("1A2B:3C4D", device.getName());
        assertEquals("1A2B:3C4D (USB)", device.displayNameWithTransport());
    }

    @Test public void flagsOutdatedAndMissingFirmware() {
        assertTrue(deviceWith(FirmwareStatus.OUTDATED).isFirmwareUpdateRequired());
        assertTrue(deviceWith(FirmwareStatus.MISSING).isFirmwareUpdateRequired());
        assertFalse(deviceWith(FirmwareStatus.OK).isFirmwareUpdateRequired());
        assertFalse(deviceWith(FirmwareStatus.UNKNOWN).isFirmwareUpdateRequired());
    }

    private static RadioDeviceInfo deviceWith(FirmwareStatus status) {
        return new RadioDeviceInfo(RadioDeviceInfo.Kind.BLE, "AA:BB", "radio", "", true,
                true, false, true, status, -40);
    }
}