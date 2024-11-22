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

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.vagell.kv4pht.databinding.ActivityFirmwareBinding;

import com.vagell.kv4pht.R;
import com.vagell.kv4pht.firmware.FirmwareUtils;
import com.vagell.kv4pht.radio.RadioAudioService;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class FirmwareActivity extends AppCompatActivity {
    private ThreadPoolExecutor threadPoolExecutor = null;
    private Snackbar errorSnackbar = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_firmware);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // Or firmware writing will fail when app is paused

        threadPoolExecutor = new ThreadPoolExecutor(2,
                2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    }

    @Override
    protected void onPause() {
        super.onPause();
        threadPoolExecutor.shutdownNow();
        threadPoolExecutor = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        threadPoolExecutor = new ThreadPoolExecutor(2,
                2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    }

    @Override
    protected void onStart() {
        super.onStart();
        startFlashing();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (errorSnackbar != null) {
            errorSnackbar.dismiss();
        }
    }

    private void startFlashing() {
        setStatusText("Connecting to bootloader...");
        findViewById(R.id.firmwareInstructionText1).setVisibility(View.VISIBLE);
        findViewById(R.id.firmwareInstructionImage).setVisibility(View.VISIBLE);
        findViewById(R.id.firmwareButtons).setVisibility(View.VISIBLE);
        findViewById(R.id.firmwareInstructionText2).setVisibility(View.GONE);
        CircularProgressIndicator progressIndicator = findViewById(R.id.firmwareProgressIndicator);
        progressIndicator.setIndeterminate(true);

        // Start flashing, if we're not already.
        if (FirmwareUtils.isFlashing()) {
            return;
        }
        final Context ctx = this;
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                UsbSerialPort serialPort = RadioAudioService.getUsbSerialPort();
                if (null == serialPort) {
                    Log.d("DEBUG", "Error: Unexpected null serial port in FirmwareActivity.");
                    // TODO report in UI that serial port not found, with option to retry
                }
                FirmwareUtils.flashFirmware(ctx, serialPort, new FirmwareUtils.FirmwareCallback() {
                    @Override
                    public void connectedToBootloader() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatusText("Flashing 0%");
                                findViewById(R.id.firmwareInstructionText1).setVisibility(View.GONE);
                                findViewById(R.id.firmwareInstructionImage).setVisibility(View.GONE);
                                findViewById(R.id.firmwareButtons).setVisibility(View.GONE);
                                findViewById(R.id.firmwareInstructionText2).setVisibility(View.VISIBLE);
                                CircularProgressIndicator progressIndicator = findViewById(R.id.firmwareProgressIndicator);
                                progressIndicator.setIndeterminate(false);
                                progressIndicator.setProgress(0);
                            }
                        });
                    }

                    @Override
                    public void reportProgress(int percent) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatusText("Flashing " + percent + "%");
                                CircularProgressIndicator progressIndicator = findViewById(R.id.firmwareProgressIndicator);
                                progressIndicator.setIndeterminate(false);
                                progressIndicator.setProgress(percent);
                            }
                        });
                    }

                    @Override
                    public void doneFlashing(boolean success) {
                        Log.d("DEBUG", "doneFlashing, success: " + success);

                        if (success) {
                            setResult(Activity.RESULT_OK, getIntent());
                            finish();
                        } else {
                            Log.d("DEBUG", "Error: Flashing firmware failed.");

                            CharSequence snackbarMsg = "Failed to flash firmware. If it keeps failing, use kv4p.com web flasher.";
                            errorSnackbar = Snackbar.make(ctx, findViewById(R.id.firmwareTopLevelView), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                                    .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE);
                            errorSnackbar.setAction("Retry", new View.OnClickListener() {
                                        @Override
                                        public void onClick(View view) {
                                            errorSnackbar.dismiss();
                                            startFlashing();
                                        }
                                    });

                            // Make the text of the snackbar larger.
                            TextView snackbarActionTextView = (TextView) errorSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
                            snackbarActionTextView.setTextSize(20);
                            TextView snackbarTextView = (TextView) errorSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
                            snackbarTextView.setTextSize(20);

                            errorSnackbar.show();
                        }
                    }
                });
            }
        });
    }

    private void setStatusText(String text) {
        TextView firmwareStatusText = findViewById(R.id.firmwareStatusText);
        firmwareStatusText.setText(text);
    }

    public void firmwareCancelButtonClicked(View view) {
        // TODO actually cancel any firmware flashing in progress
        setResult(Activity.RESULT_CANCELED, getIntent());
        finish();
    }
}