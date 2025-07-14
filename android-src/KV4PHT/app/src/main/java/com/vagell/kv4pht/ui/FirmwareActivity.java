/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import com.vagell.kv4pht.R;
import com.vagell.kv4pht.firmware.FirmwareUtils;
import com.vagell.kv4pht.radio.RadioServiceConnector;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_INDEFINITE;


public class FirmwareActivity extends AppCompatActivity {

    public static final CharSequence FAILED_TO_MESSAGE = "Failed to flash firmware. If it keeps failing, use kv4p.com web flasher.";
    public static final String CONNECTING_TO_BOOTLOADER = "Connecting to bootloader...";
    public static final String RETRY = "Retry";

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Snackbar errorSnackbar;
    private RadioServiceConnector serviceConnector;
    private Future<?> flashingTask;

    private CircularProgressIndicator progressIndicator;
    private TextView firmwareStatusText;
    private View instructionText1;
    private View instructionImage;
    private View instructionText2;
    private View firmwareButtons;
    private View topLevelView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_firmware);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        serviceConnector = new RadioServiceConnector(this);
        firmwareStatusText = findViewById(R.id.firmwareStatusText);
        progressIndicator = findViewById(R.id.firmwareProgressIndicator);
        instructionText1 = findViewById(R.id.firmwareInstructionText1);
        instructionImage = findViewById(R.id.firmwareInstructionImage);
        instructionText2 = findViewById(R.id.firmwareInstructionText2);
        firmwareButtons = findViewById(R.id.firmwareButtons);
        topLevelView = findViewById(R.id.firmwareTopLevelView);
    }

    @Override
    protected void onStart() {
        super.onStart();
        serviceConnector.bind(service -> startFlashing(service.getSerialPort()));
    }

    @Override
    protected void onStop() {
        super.onStop();
        serviceConnector.unbind();
        if (errorSnackbar != null) errorSnackbar.dismiss();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    @SuppressLint("UnsafeIntentLaunch")
    public void firmwareCancelButtonClicked(View view) {
        if (flashingTask != null) {
            flashingTask.cancel(true); // sends an interrupt
        }
        setResult(Activity.RESULT_CANCELED, getIntent());
        finish();
    }

    private void setStatusText(String text) {
        firmwareStatusText.setText(text);
    }

    private void startFlashing(UsbSerialPort serialPort) {
        if (FirmwareUtils.isFlashing() || serialPort == null) {
            Log.w("FirmwareActivity", "Flashing already in progress or serialPort is null.");
            return;
        }
        updateInitialUi();
        flashingTask = executor.submit(() -> FirmwareUtils.flashFirmware(
            this,
            serialPort,
            new FirmwareUtils.FirmwareCallback() {
                @Override
                public void connectedToBootloader() {
                    runOnUiThread(() -> {
                        setStatusText("Flashing 0%");
                        toggleInstructionViews(false);
                        firmwareButtons.setVisibility(View.GONE);
                        instructionText2.setVisibility(View.VISIBLE);
                        progressIndicator.setIndeterminate(false);
                        progressIndicator.setProgress(0);
                    });
                }
                @Override
                public void reportProgress(int percent) {
                    runOnUiThread(() -> {
                        setStatusText("Flashing " + percent + "%");
                        progressIndicator.setProgress(percent);
                    });
                }
                @SuppressLint("UnsafeIntentLaunch")
                @Override
                public void doneFlashing(boolean success) {
                    Log.d("FirmwareActivity", "Flashing done: " + success);
                    if (success) {
                        setResult(Activity.RESULT_OK, getIntent());
                        finish();
                    } else {
                        showErrorSnackBar(serialPort);
                    }
                }
            }));
    }

    private void updateInitialUi() {
        setStatusText(CONNECTING_TO_BOOTLOADER);
        toggleInstructionViews(true);
        firmwareButtons.setVisibility(View.VISIBLE);
        instructionText2.setVisibility(View.GONE);
        progressIndicator.setIndeterminate(true);
    }

    private void toggleInstructionViews(boolean showInitial) {
        instructionText1.setVisibility(showInitial ? View.VISIBLE : View.GONE);
        instructionImage.setVisibility(showInitial ? View.VISIBLE : View.GONE);
    }

    private void showErrorSnackBar(UsbSerialPort port) {
        errorSnackbar = Snackbar.make(topLevelView, FAILED_TO_MESSAGE, LENGTH_INDEFINITE)
            .setBackgroundTint(Color.rgb(140, 20, 0))
            .setTextColor(Color.WHITE)
            .setActionTextColor(Color.WHITE)
            .setAction(RETRY, v -> {
                errorSnackbar.dismiss();
                startFlashing(port);
            });
        View snackbarView = errorSnackbar.getView();
        TextView text = snackbarView.findViewById(com.google.android.material.R.id.snackbar_text);
        TextView action = snackbarView.findViewById(com.google.android.material.R.id.snackbar_action);
        text.setTextSize(20);
        action.setTextSize(20);
        errorSnackbar.show();
    }
}
