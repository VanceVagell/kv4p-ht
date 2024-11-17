package com.vagell.kv4pht.firmware;

import android.content.Context;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.firmware.bearconsole.CommandInterfaceESP32;
import com.vagell.kv4pht.firmware.bearconsole.UploadSTM32CallBack;
import com.vagell.kv4pht.firmware.bearconsole.UploadSTM32Errors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class FirmwareUtils {
    private static boolean isFlashing = false;
    private static int progressPercent = 0;

    // Whenever there is new firmware, put the files in res/raw, and update these constants.
    public static final int PACKAGED_FIRMWARE_VER = 2;
    private static final int FIRMWARE_FILE_1_ID = R.raw.v2_kv4p_ht_esp32_wroom_32_ino_bootloader;
    private static final int FIRMWARE_FILE_2_ID = R.raw.v2_kv4p_ht_esp32_wroom_32_ino_partitions;
    private static final int FIRMWARE_FILE_3_ID = R.raw.boot_app0; // This one never changes, it's the Arduino ESP32 bootloader
    private static final int FIRMWARE_FILE_4_ID = R.raw.v2_kv4p_ht_esp32_wroom_32_ino;

    public FirmwareUtils() {
    }

    public static boolean isFlashing() {
        return isFlashing;
    }

    public interface FirmwareCallback {
        public void connectedToBootloader();
        public void reportProgress(int percent);
        public void doneFlashing(boolean success);
    }

    public static void flashFirmware(Context ctx, UsbSerialPort usbSerialPort, FirmwareCallback callback) {
        if (isFlashing) {
            Log.d("DEBUG", "Warning: Attempted to call FirmwareUtils.flashFirmware() while already flashing.");
            return;
        }
        isFlashing = true;
        boolean failed = false;
        InputStream firmwareFile1 = null;
        InputStream firmwareFile2 = null;
        InputStream firmwareFile3 = null;
        InputStream firmwareFile4 = null;
        CommandInterfaceESP32 cmd;

        UploadSTM32CallBack UpCallback = new UploadSTM32CallBack() {
            @Override
            public void onPreUpload() {
                Log.d("DEBUG", "onPreUpload");
            }

            @Override
            public void onUploading(int value) {
                Log.d("DEBUG", "onUploading: " + value);

                // Some of the file writes take a while, show finer-grained progress for those.
                if (progressPercent >= 20 && progressPercent < 30) {
                    int newPercent = Math.min(50, (int) (20 + (10 * ((float) value / 100.0f))));
                    trackProgress(callback, newPercent);
                }
                else if (progressPercent >= 50) {
                    int newPercent = Math.min(100, (int) (50 + (50 * ((float) value / 100.0f))));
                    trackProgress(callback, newPercent);
                }
            }

            @Override
            public void onInfo(String value) {
                Log.d("DEBUG", "onInfo: " + value);
            }

            @Override
            public void onPostUpload(boolean success) {
                Log.d("DEBUG", "onPostUpload: " + success);
            }

            @Override
            public void onCancel() {
                Log.d("DEBUG", "onCancel");
            }

            @Override
            public void onError(UploadSTM32Errors err) {
                Log.d("DEBUG", "onError: " + err);
            }
        };
        cmd = new CommandInterfaceESP32(ctx, UpCallback, usbSerialPort);

        firmwareFile1 = ctx.getResources().openRawResource(FIRMWARE_FILE_1_ID);
        firmwareFile2 = ctx.getResources().openRawResource(FIRMWARE_FILE_2_ID);
        firmwareFile3 = ctx.getResources().openRawResource(FIRMWARE_FILE_3_ID);
        firmwareFile4 = ctx.getResources().openRawResource(FIRMWARE_FILE_4_ID);

        Log.d("DEBUG", "Attempting to init ESP32 for firmware flash");

        boolean ret = cmd.initChip();
        if (!ret) {
            Log.d("DEBUG", "ESP32 failed to init, return value: " + ret);
            failed = true;
        }
        if (!failed) {
            callback.connectedToBootloader();
            // cmd.loadStubFromFile(); // Does not work
            // cmd.changeBaudRate(); // Faster baud can't work without stub loader
            trackProgress(callback, 10);
            cmd.init();
            trackProgress(callback, 20);

            Log.d("DEBUG", "Flashing firmware");
            cmd.flashData(readFirmwareBytes(firmwareFile1), 0x1000, 0);
            trackProgress(callback, 30);
            cmd.flashData(readFirmwareBytes(firmwareFile2), 0x8000, 0);
            trackProgress(callback, 40);
            cmd.flashData(readFirmwareBytes(firmwareFile3), 0xe000, 0);
            trackProgress(callback, 50);
            cmd.flashData(readFirmwareBytes(firmwareFile4), 0x10000, 0);

            // we have finished flashing, reboot ESP32
            cmd.reset();

            Log.d("DEBUG", "Done flashing firmware");
        }
        callback.doneFlashing(!failed);
        progressPercent = 0;
        isFlashing = false;
    }

    private static void trackProgress(FirmwareCallback callback, int newProgressPercent) {
        progressPercent = newProgressPercent;
        callback.reportProgress(progressPercent);
    }

    private static byte[] readFirmwareBytes(InputStream stream) {
        Log.d("DEBUG", "Reading firmware binary file");
        ByteArrayOutputStream byteArrayOutputStream = null;
        int i;
        try {
            byteArrayOutputStream = new ByteArrayOutputStream();
            i = stream.read();
            while (i != -1) {
                byteArrayOutputStream.write(i);
                i = stream.read();
            }
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return byteArrayOutputStream.toByteArray();
    }
}
