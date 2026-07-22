package com.vagell.kv4pht.radio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

final class BleKissRadioTransport implements RadioTransport {
    interface Listener {
        void onBytes(byte[] bytes);
        void onConnected();
        void onDisconnected();
        void onError(Exception error);
    }

    private static final String TAG = BleKissRadioTransport.class.getSimpleName();
    private static final UUID SERVICE_UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb");
    private static final UUID TX_CHAR_UUID = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb");
    private static final UUID RX_CHAR_UUID = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Low-latency scans normally receive an advertising radio within one interval.
    // Keep a few seconds of margin, then let the periodic reconciler start a new window.
    private static final int SCAN_TIMEOUT_MS = 3_000;
    private static final int REQUESTED_MTU = 247;
    private static final int WRITE_RETRY_DELAY_MS = 8;

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final ArrayDeque<byte[]> pendingWrites = new ArrayDeque<>();
    private final ArrayDeque<byte[]> pendingNotifications = new ArrayDeque<>();
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txCharacteristic;
    private boolean scanning = false;
    private boolean ready = false;
    private boolean writeInFlight = false;
    private boolean drainScheduled = false;
    private boolean useWriteNoResponse = false;
    private final Runnable scanTimeout;
    private int attPayloadSize = 20;
    private int writeStartFailures = 0;

    BleKissRadioTransport(Context context, Handler handler, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
        this.scanTimeout = () -> {
            if (scanning) {
                stopScan();
                listener.onDisconnected();
            }
        };
    }

    @SuppressLint("MissingPermission")
    void connect() {
        if (!hasBluetoothPermissions()) {
            listener.onError(new SecurityException("Missing Bluetooth permissions"));
            return;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            listener.onError(new IllegalStateException("Bluetooth is not available or enabled"));
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError(new IllegalStateException("Bluetooth LE scanner is unavailable"));
            return;
        }
        ScanFilter filter = new ScanFilter.Builder()
            .setServiceUuid(new ParcelUuid(SERVICE_UUID))
            .build();
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();
        scanning = true;
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (SecurityException error) {
            scanning = false;
            listener.onError(error);
            return;
        }
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        Log.i(TAG, "Scanning for BLE KISS radio");
    }

    @Override
    @SuppressLint("MissingPermission")
    public void close() {
        stopScan();
        ready = false;
        writeInFlight = false;
        drainScheduled = false;
        useWriteNoResponse = false;
        writeStartFailures = 0;
        handler.removeCallbacks(drainRunnable);
        pendingWrites.clear();
        pendingNotifications.clear();
        txCharacteristic = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
                // Best-effort teardown.
            }
            try {
                gatt.close();
            } catch (Exception ignored) {
                // Best-effort teardown.
            }
            gatt = null;
        }
    }

    @Override
    public boolean isReady() {
        return ready && gatt != null && txCharacteristic != null;
    }

    @Override
    public void writeAsync(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        handler.post(() -> enqueueWrite(copy));
    }

    private void enqueueWrite(byte[] bytes) {
        for (int offset = 0; offset < bytes.length; offset += attPayloadSize) {
            int end = Math.min(bytes.length, offset + attPayloadSize);
            pendingWrites.add(Arrays.copyOfRange(bytes, offset, end));
        }
        scheduleDrain(0);
    }

    @Override
    public String getName() {
        return "BLE KISS";
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handler.post(() -> connectToScanResult(result));
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.post(() -> {
                stopScan();
                listener.onError(new IllegalStateException("BLE scan failed: " + errorCode));
            });
        }
    };

    @SuppressLint("MissingPermission")
    private void connectToScanResult(ScanResult result) {
        if (!scanning) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        Log.i(TAG, "Found BLE KISS radio: " + safeDeviceName(device));
        stopScan();
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException error) {
            listener.onError(error);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            handler.post(() -> handleConnectionStateChange(gatt, status, newState));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            handler.post(() -> handleServicesDiscovered(gatt, status));
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            handler.post(() -> handleMtuChanged(gatt, mtu, status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> handleDescriptorWrite(gatt, descriptor, status));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            byte[] copy = value != null ? Arrays.copyOf(value, value.length) : new byte[0];
            handler.post(() -> handleNotification(gatt, characteristic.getUuid(), copy));
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value
        ) {
            byte[] copy = Arrays.copyOf(value, value.length);
            handler.post(() -> handleNotification(gatt, characteristic.getUuid(), copy));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> handleCharacteristicWrite(gatt, status));
        }
    };

    @SuppressLint("MissingPermission")
    private void handleConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
        if (callbackGatt != gatt) {
            return;
        }
        try {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError(new IllegalStateException("BLE connection status " + status));
                close();
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE KISS connected; discovering services");
                if (!gatt.discoverServices()) {
                    listener.onError(new IllegalStateException("BLE service discovery did not start"));
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                listener.onDisconnected();
            }
        } catch (SecurityException error) {
            listener.onError(error);
        }
    }

    @SuppressLint("MissingPermission")
    private void handleServicesDiscovered(BluetoothGatt callbackGatt, int status) {
        if (callbackGatt != gatt) {
            return;
        }
        try {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError(new IllegalStateException("BLE service discovery failed: " + status));
                return;
            }
            BluetoothGattService service = callbackGatt.getService(SERVICE_UUID);
            if (service == null) {
                listener.onError(new IllegalStateException("BLE KISS service not found"));
                return;
            }
            txCharacteristic = service.getCharacteristic(TX_CHAR_UUID);
            BluetoothGattCharacteristic rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID);
            if (txCharacteristic == null || rxCharacteristic == null) {
                listener.onError(new IllegalStateException("BLE KISS characteristics not found"));
                return;
            }
            useWriteNoResponse = (txCharacteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
            Log.i(TAG, "BLE KISS TX write mode=" + (useWriteNoResponse ? "no-response" : "with-response"));
            if (!callbackGatt.requestMtu(REQUESTED_MTU)) {
                subscribeToRx(callbackGatt, rxCharacteristic);
            }
        } catch (SecurityException error) {
            listener.onError(error);
        }
    }

    private void handleMtuChanged(BluetoothGatt callbackGatt, int mtu, int status) {
        if (callbackGatt != gatt) {
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            attPayloadSize = Math.max(20, mtu - 3);
            Log.i(TAG, "BLE KISS MTU=" + mtu + " payload=" + attPayloadSize);
        }
        BluetoothGattService service = callbackGatt.getService(SERVICE_UUID);
        BluetoothGattCharacteristic rxCharacteristic = service != null ? service.getCharacteristic(RX_CHAR_UUID) : null;
        if (rxCharacteristic != null) {
            subscribeToRx(callbackGatt, rxCharacteristic);
        } else {
            listener.onError(new IllegalStateException("BLE KISS RX characteristic missing after MTU"));
        }
    }

    private void handleDescriptorWrite(BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int status) {
        if (callbackGatt != gatt || !CCCD_UUID.equals(descriptor.getUuid())) {
            return;
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            ready = true;
            listener.onConnected();
            while (!pendingNotifications.isEmpty()) {
                listener.onBytes(pendingNotifications.remove());
            }
            scheduleDrain(0);
        } else {
            listener.onError(new IllegalStateException("BLE notification subscription failed: " + status));
        }
    }

    private void handleNotification(BluetoothGatt callbackGatt, UUID characteristicUuid, byte[] value) {
        if (callbackGatt != gatt || !RX_CHAR_UUID.equals(characteristicUuid)) {
            return;
        }
        if (!ready) {
            pendingNotifications.add(value);
            return;
        }
        listener.onBytes(value);
    }

    private void handleCharacteristicWrite(BluetoothGatt callbackGatt, int status) {
        if (callbackGatt != gatt) {
            return;
        }
        writeInFlight = false;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            listener.onError(new IllegalStateException("BLE write failed: " + status));
            return;
        }
        scheduleDrain(0);
    }

    @SuppressLint("MissingPermission")
    private void subscribeToRx(BluetoothGatt gatt, BluetoothGattCharacteristic rxCharacteristic) {
        if (!gatt.setCharacteristicNotification(rxCharacteristic, true)) {
            listener.onError(new IllegalStateException("BLE notification enable failed"));
            return;
        }
        BluetoothGattDescriptor descriptor = rxCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            listener.onError(new IllegalStateException("BLE notification descriptor missing"));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int status = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError(new IllegalStateException("BLE descriptor write failed: " + status));
            }
        } else {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!gatt.writeDescriptor(descriptor)) {
                listener.onError(new IllegalStateException("BLE descriptor write did not start"));
            }
        }
    }

    private final Runnable drainRunnable = () -> {
        drainScheduled = false;
        drainWritesOnHandler();
    };

    private void scheduleDrain(int delayMs) {
        if (drainScheduled) {
            return;
        }
        drainScheduled = true;
        if (delayMs <= 0) {
            handler.post(drainRunnable);
        } else {
            handler.postDelayed(drainRunnable, delayMs);
        }
    }

    @SuppressLint("MissingPermission")
    private void drainWritesOnHandler() {
        if (!isReady() || writeInFlight) {
            return;
        }
        byte[] next = pendingWrites.poll();
        if (next == null) {
            return;
        }
        int writeType = useWriteNoResponse
            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        writeInFlight = !useWriteNoResponse;
        txCharacteristic.setWriteType(writeType);
        boolean started;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int status = gatt.writeCharacteristic(txCharacteristic, next, writeType);
                started = status == BluetoothGatt.GATT_SUCCESS;
            } else {
                txCharacteristic.setValue(next);
                started = gatt.writeCharacteristic(txCharacteristic);
            }
        } catch (SecurityException error) {
            writeInFlight = false;
            listener.onError(error);
            return;
        }
        if (!started) {
            writeInFlight = false;
            pendingWrites.addFirst(next);
            writeStartFailures++;
            if (writeStartFailures % 25 == 1) {
                Log.w(TAG, "BLE write did not start; retrying after backoff count=" + writeStartFailures);
            }
            scheduleDrain(WRITE_RETRY_DELAY_MS);
            return;
        }
        writeStartFailures = 0;
        if (useWriteNoResponse) {
            scheduleDrain(0);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
                // Best-effort teardown.
            }
        }
        scanning = false;
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name != null ? name : device.getAddress();
        } catch (SecurityException e) {
            return "unknown";
        }
    }
}
