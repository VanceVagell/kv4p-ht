package com.vagell.kv4pht.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.radio.RadioAudioService;
import com.vagell.kv4pht.radio.RadioConnectionState;
import com.vagell.kv4pht.radio.RadioDeviceInfo;
import com.vagell.kv4pht.radio.RadioServiceConnector;

import java.util.ArrayList;
import java.util.List;

/** Lists USB and Bluetooth radios and lets the user pair, connect, disconnect, or forget them. */
public class ConnectRadioActivity extends AppCompatActivity implements ConnectRadioAdapter.Listener {
    private RadioServiceConnector serviceConnector;
    private final Observer<RadioConnectionState> stateObserver = this::render;

    @Nullable
    private RadioAudioService radioAudioService;
    private LiveData<RadioConnectionState> observedState;
    private ConnectRadioAdapter adapter;
    private TextView statusView;
    private boolean receivedInitialState;
    private String lastShownConnectionError;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connect_radio);
        serviceConnector = new RadioServiceConnector(this);

        adapter = new ConnectRadioAdapter(this);
        RecyclerView list = findViewById(R.id.connectRadioList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        statusView = findViewById(R.id.connectRadioStatus);
        findViewById(R.id.connectRadioDoneButton).setOnClickListener(view -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        serviceConnector.bind(service -> {
            radioAudioService = service;
            observe(service);
            service.setBleScanForeground(true);
        });
    }

    @Override
    protected void onStop() {
        if (radioAudioService != null) {
            radioAudioService.setBleScanForeground(false);
        }
        if (observedState != null) {
            observedState.removeObserver(stateObserver);
            observedState = null;
        }
        radioAudioService = null;
        serviceConnector.unbind();
        super.onStop();
    }

    private void observe(RadioAudioService service) {
        if (observedState != null) {
            observedState.removeObserver(stateObserver);
        }
        // Any error left over from a previous visit is stale; don't show it again on reopen.
        service.clearConnectionError();
        receivedInitialState = false;
        lastShownConnectionError = null;
        observedState = service.getConnectionState();
        observedState.observe(this, stateObserver);
    }

    private void render(@Nullable RadioConnectionState state) {
        if (state == null) {
            state = RadioConnectionState.empty();
        }
        List<ConnectRadioAdapter.Row> rows = new ArrayList<>();
        for (RadioDeviceInfo device : state.getUsbDevices()) {
            rows.add(ConnectRadioAdapter.Row.device(device));
        }
        // Show the empty paired-section label unless the list is taken over by a USB radio. A
        // connected Bluetooth radio still belongs in the Bluetooth section, paired or not.
        RadioDeviceInfo active = state.getActiveDevice();
        boolean bluetoothSectionVisible = active == null
                || active.getKind() == RadioDeviceInfo.Kind.BLE;
        if (state.getPairedBleDevices().isEmpty() && bluetoothSectionVisible) {
            rows.add(ConnectRadioAdapter.Row.status(
                    getString(R.string.connect_radio_no_paired)));
        }
        for (RadioDeviceInfo device : state.getPairedBleDevices()) {
            rows.add(ConnectRadioAdapter.Row.device(device));
        }
        for (RadioDeviceInfo device : state.getDiscoveredBleDevices()) {
            rows.add(ConnectRadioAdapter.Row.device(device));
        }
        moveActiveToTop(rows, active);
        adapter.submit(rows);
        statusView.setText(statusText(state));
        handleConnectionError(state);
    }

    private static void moveActiveToTop(List<ConnectRadioAdapter.Row> rows,
                                        @Nullable RadioDeviceInfo active) {
        if (active == null || !active.isConnected()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            RadioDeviceInfo device = rows.get(i).getDevice();
            if (device != null && device.getKind() == active.getKind()
                    && device.getId().equals(active.getId())) {
                if (i > 0) {
                    rows.add(0, rows.remove(i));
                }
                return;
            }
        }
    }

    private void handleConnectionError(RadioConnectionState state) {
        String error = state.getConnectionError();
        if (!receivedInitialState) {
            // The first state may still contain an error from before this screen was opened.
            receivedInitialState = true;
            if (error != null) {
                consumeConnectionError();
            }
            return;
        }
        if (error == null) {
            lastShownConnectionError = null;
            return;
        }
        if (!error.equals(lastShownConnectionError)) {
            lastShownConnectionError = error;
            Snackbar.make(findViewById(android.R.id.content), error, 7000)
                    .setBackgroundTint(Color.rgb(140, 20, 0))
                    .setTextColor(Color.WHITE)
                    .show();
            // Clear it in the service so an identical later failure still produces a new snackbar.
            consumeConnectionError();
        }
    }

    private void consumeConnectionError() {
        if (radioAudioService != null) {
            radioAudioService.clearConnectionError();
        }
    }

    private String statusText(RadioConnectionState state) {
        if (state.getBluetoothError() != null) {
            if (state.getBluetoothError().contains("off")) {
                return getString(R.string.connect_radio_bt_off);
            }
            return state.getBluetoothError();
        }
        if (state.isScanning()) {
            return getString(R.string.connect_radio_scanning);
        }
        return "";
    }

    @Override
    public void onPair(RadioDeviceInfo device) {
        if (radioAudioService != null) {
            radioAudioService.pairBle(device.getId());
        }
    }

    @Override
    public void onConnect(RadioDeviceInfo device) {
        if (radioAudioService == null) {
            return;
        }
        if (device.getKind() == RadioDeviceInfo.Kind.USB) {
            radioAudioService.connectUsb(device.getId());
        } else {
            radioAudioService.connectBle(device.getId());
        }
    }

    @Override
    public void onDisconnect(RadioDeviceInfo device) {
        if (radioAudioService != null) {
            radioAudioService.disconnectBle();
        }
    }

    @Override
    public void onForget(RadioDeviceInfo device) {
        if (radioAudioService != null) {
            radioAudioService.forgetBle(device.getId());
        }
    }

    @Override
    public void onShowFirmwareInfo(RadioDeviceInfo device) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connect_radio_old_firmware)
                .setMessage(R.string.connect_radio_old_firmware_message)
                .setPositiveButton(R.string.close_display, (dialog, which) -> {
                    // Nothing else to do.
                })
                .show();
    }
}