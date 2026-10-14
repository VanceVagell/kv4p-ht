package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothGatt;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BleKissRadioTransportTest {
    @Test public void mtuCallbackEnablesBatchedAudioOnlyWhenPayloadFitsAndCloseResetsIt() {
        BleKissRadioTransport transport = new BleKissRadioTransport(
                RuntimeEnvironment.getApplication(), new Handler(Looper.getMainLooper()));
        BluetoothGatt gatt = ShadowBluetoothGatt.newInstance(
                BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:00:00:00:00:01"));
        ShadowBluetoothGatt shadowGatt = Shadows.shadowOf(gatt);
        UUID serviceId = ReflectionHelpers.getStaticField(BleKissRadioTransport.class, "SERVICE_UUID");
        UUID rxId = ReflectionHelpers.getStaticField(BleKissRadioTransport.class, "RX_CHAR_UUID");
        UUID cccdId = ReflectionHelpers.getStaticField(BleKissRadioTransport.class, "CCCD_UUID");
        BluetoothGattService service = new BluetoothGattService(serviceId,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(rxId,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        rx.addDescriptor(new BluetoothGattDescriptor(cccdId,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(rx);
        shadowGatt.addDiscoverableService(service);
        shadowGatt.setGattCallback(new BluetoothGattCallback() { });
        assertTrue(gatt.discoverServices());
        shadowGatt.allowCharacteristicNotification(rx);
        ReflectionHelpers.setField(transport, "gatt", gatt);
        List<Exception> errors = new ArrayList<>();
        ReflectionHelpers.setField(transport, "listener", new RadioTransport.Listener() {
            @Override public void onBytes(byte[] bytes) { }
            @Override public void onReady() { }
            @Override public void onDisconnected() { }
            @Override public void onError(Exception error) { errors.add(error); }
        });
        BluetoothGattCallback callback = ReflectionHelpers.getField(transport, "gattCallback");

        try {
            callback.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS);
            assertEquals(20, (int) ReflectionHelpers.getField(transport, "attPayloadSize"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(244, (int) ReflectionHelpers.getField(transport, "attPayloadSize"));
            assertFalse(transport.supportsBatchedTxAudio());

            callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(512, (int) ReflectionHelpers.getField(transport, "attPayloadSize"));
            assertTrue(transport.supportsBatchedTxAudio());
            assertTrue(errors.toString(), errors.isEmpty());
        } finally {
            transport.close();
        }
        assertEquals(20, (int) ReflectionHelpers.getField(transport, "attPayloadSize"));
        assertFalse(transport.supportsBatchedTxAudio());
    }
}
