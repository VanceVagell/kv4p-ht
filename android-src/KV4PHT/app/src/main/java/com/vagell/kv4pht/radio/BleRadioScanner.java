package com.vagell.kv4pht.radio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scans for kv4p radios by BLE service UUID and caches their most recent advertisement. Scanning
 * runs in short windows while the Connect radio screen is closed, and continuously while it is
 * open. The scanner never connects to anything; the connection policy decides that.
 */
final class BleRadioScanner {
    private static final String TAG = BleRadioScanner.class.getSimpleName();
    private static final UUID SERVICE_UUID =
            UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb");
    private static final long WINDOW_MS = 3_000L;
    private static final long GAP_MS = 2_000L;
    private static final long RESULT_TTL_MS = 15_000L;

    interface Listener {
        void onScanResultsChanged(List<ScanEntry> entries);

        void onScanStateChanged(boolean enabled, boolean activelyScanning, String error);
    }

    static final class ScanEntry {
        private final BluetoothDevice device;
        private final String address;
        private final String name;
        private final int rssi;
        private final long lastSeenMs;

        ScanEntry(BluetoothDevice device, String address, String name, int rssi,
                long lastSeenMs) {
            this.device = device;
            this.address = address;
            this.name = name == null || name.isEmpty() ? address : name;
            this.rssi = rssi;
            this.lastSeenMs = lastSeenMs;
        }

        BluetoothDevice getDevice() {
            return device;
        }

        String getAddress() {
            return address;
        }

        String getName() {
            return name;
        }

        int getRssi() {
            return rssi;
        }

        long getLastSeenMs() {
            return lastSeenMs;
        }
    }

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final Map<String, ScanEntry> entries = new LinkedHashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean enabled;
    private boolean foreground;
    private boolean scanning;
    private String lastError;

    private final Runnable windowOff = this::finishScanWindow;
    private final Runnable windowOn = this::beginScanWindow;

    BleRadioScanner(Context context, Handler handler, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isScanning() {
        return scanning;
    }

    boolean isBluetoothReady() {
        return hasBluetoothPermissions() && getAdapter() != null && getAdapter().isEnabled();
    }

    String getLastError() {
        return lastError;
    }

    void start() {
        if (enabled) {
            return;
        }
        enabled = true;
        beginScanWindow();
    }

    void stop() {
        enabled = false;
        handler.removeCallbacks(windowOff);
        handler.removeCallbacks(windowOn);
        stopScanInternal();
        scanning = false;
        entries.clear();
        listener.onScanResultsChanged(Collections.emptyList());
        listener.onScanStateChanged(false, false, null);
    }

    void setForeground(boolean foreground) {
        if (this.foreground == foreground) {
            return;
        }
        this.foreground = foreground;
        if (!enabled) {
            return;
        }
        handler.removeCallbacks(windowOff);
        handler.removeCallbacks(windowOn);
        stopScanInternal();
        scanning = false;
        beginScanWindow();
    }

    List<ScanEntry> getEntries() {
        pruneExpired(SystemClock.elapsedRealtime());
        List<ScanEntry> result = new ArrayList<>(entries.values());
        result.sort(Comparator
                .comparingInt(ScanEntry::getRssi).reversed()
                .thenComparing(ScanEntry::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ScanEntry::getAddress));
        return result;
    }

    ScanEntry find(String address) {
        return address == null ? null : entries.get(address);
    }

    String getDisplayName(String address) {
        ScanEntry entry = find(address);
        return entry != null ? entry.getName() : address;
    }

    BluetoothDevice getDevice(String address) {
        ScanEntry entry = find(address);
        return entry != null ? entry.getDevice() : null;
    }

    private void beginScanWindow() {
        if (!enabled || scanning) {
            return;
        }
        if (!ensureReady()) {
            listener.onScanStateChanged(true, false, lastError);
            handler.removeCallbacks(windowOn);
            handler.postDelayed(windowOn, GAP_MS);
            return;
        }
        if (!startScanInternal()) {
            listener.onScanStateChanged(true, false, lastError);
            handler.removeCallbacks(windowOn);
            handler.postDelayed(windowOn, GAP_MS);
            return;
        }
        scanning = true;
        lastError = null;
        listener.onScanStateChanged(true, true, null);
        if (!foreground) {
            handler.removeCallbacks(windowOff);
            handler.postDelayed(windowOff, WINDOW_MS);
        }
    }

    private void finishScanWindow() {
        stopScanInternal();
        scanning = false;
        listener.onScanStateChanged(enabled, false, lastError);
        if (enabled && !foreground) {
            handler.removeCallbacks(windowOn);
            handler.postDelayed(windowOn, GAP_MS);
        }
    }

    private boolean ensureReady() {
        if (!hasBluetoothPermissions()) {
            lastError = "Bluetooth permission required";
            return false;
        }
        BluetoothAdapter currentAdapter = getAdapter();
        if (currentAdapter == null || !currentAdapter.isEnabled()) {
            lastError = "Bluetooth is off";
            return false;
        }
        BluetoothLeScanner currentScanner = currentAdapter.getBluetoothLeScanner();
        if (currentScanner == null) {
            lastError = "Bluetooth LE is unavailable";
            return false;
        }
        adapter = currentAdapter;
        scanner = currentScanner;
        return true;
    }

    private BluetoothAdapter getAdapter() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager != null ? manager.getAdapter() : null;
    }

    @SuppressLint("MissingPermission")
    private boolean startScanInternal() {
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(foreground
                        ? ScanSettings.SCAN_MODE_LOW_LATENCY
                        : ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            Log.i(TAG, "Scanning for BLE kv4p radios (foreground=" + foreground + ")");
            return true;
        } catch (SecurityException | IllegalStateException error) {
            Log.w(TAG, "Unable to start BLE scan", error);
            lastError = "Bluetooth scan failed";
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScanInternal() {
        if (scanner == null) {
            return;
        }
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException | IllegalStateException ignored) {
            // Best-effort teardown; the adapter may already be off.
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handler.post(() -> handleScanResult(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            handler.post(() -> {
                for (ScanResult result : results) {
                    handleScanResult(result);
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            handler.post(() -> {
                Log.w(TAG, "BLE scan failed: " + errorCode);
                lastError = "Bluetooth scan failed";
                stopScanInternal();
                scanning = false;
                listener.onScanStateChanged(enabled, false, lastError);
            });
        }
    };

    private void handleScanResult(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        String address;
        try {
            address = device.getAddress();
        } catch (SecurityException error) {
            return;
        }
        String name = null;
        ScanRecord record = result.getScanRecord();
        if (record != null) {
            name = record.getDeviceName();
        }
        if (name == null || name.isEmpty()) {
            name = safeDeviceName(device);
        }
        // Don't downgrade a known name to the address just because one advertisement omitted it;
        // otherwise the row would be rebound mid-tap.
        ScanEntry previous = entries.get(address);
        if (previous != null && name.equals(address)
                && !previous.getName().equals(address)) {
            name = previous.getName();
        }
        long now = SystemClock.elapsedRealtime();
        entries.put(address, new ScanEntry(device, address, name, result.getRssi(), now));
        pruneExpired(now);
        listener.onScanResultsChanged(getEntries());
    }

    private void pruneExpired(long now) {
        entries.entrySet().removeIf(entry -> now - entry.getValue().getLastSeenMs()
                > RESULT_TTL_MS);
    }

    @SuppressLint("MissingPermission")
    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name != null ? name : device.getAddress();
        } catch (SecurityException error) {
            return device.getAddress();
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}