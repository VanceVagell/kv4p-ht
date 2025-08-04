package com.vagell.kv4pht.firmware;

import static org.dkaukov.esp32.chip.Esp32ChipId.ESP32;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.vagell.kv4pht.R;

import org.dkaukov.esp32.chip.FlashRegion;
import org.dkaukov.esp32.core.EspFlasherApi;
import org.dkaukov.esp32.core.EspFlasherApi.StartStage;
import org.dkaukov.esp32.io.ProgressCallback;
import org.dkaukov.esp32.io.SerialTransport;
import org.dkaukov.esp32.protocol.EspFlasherProtocol;
import org.slf4j.LoggerFactory;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;
import lombok.SneakyThrows;
import pl.brightinventions.slf4android.LogLevel;
import pl.brightinventions.slf4android.LoggerConfiguration;

public final class FirmwareUtils {
    private static final String TAG = FirmwareUtils.class.getSimpleName();
    private static final String TAG_ESP32_FLASHER = EspFlasherProtocol.class.getSimpleName();

    private static final AtomicBoolean isFlashing = new AtomicBoolean(false);

    public static final int PACKAGED_FIRMWARE_VER = 15;

    private static final int ESP32_BOOTLOADER = R.raw.bootloader;
    private static final int ESP32_PARTITION_TABLE = R.raw.partitions;
    private static final int ESP32_BOOT_APP_0 = R.raw.boot_app0;
    private static final int ESP32_APP = R.raw.firmware_v15;

    static {
        LoggerFactory.getLogger(EspFlasherProtocol.class).trace("Init..");
        LoggerConfiguration.configuration().setLogLevel(LoggerFactory.getLogger(EspFlasherProtocol.class).getName(),
            LogLevel.DEBUG);
    }

    public static class ResourceLoadingException extends RuntimeException {
        @SuppressLint("DefaultLocale")
        public ResourceLoadingException(int id, IOException cause) {
            super(String.format("Failed to read resource: %d", id), cause);
        }
    }

    private FirmwareUtils() {}

    public interface FirmwareCallback {
        void connectedToBootloader();
        void reportProgress(int percent);
        void doneFlashing(boolean success);
    }

    public static void flashFirmware(Context ctx, UsbSerialPort usbSerialPort, FirmwareCallback callback) {
        if (!isFlashing.compareAndSet(false, true)) {
            Log.w(TAG, "Warning: Already flashing.");
            return;
        }
        try {
            setBaudRate(usbSerialPort, EspFlasherApi.ESP_ROM_BAUD);
            Log.i(TAG, "Starting firmware flash, version: " + PACKAGED_FIRMWARE_VER);
            Map<FlashRegion, byte[]> flashRegions = new EnumMap<>(FlashRegion.class);
            flashRegions.put(FlashRegion.BOOTLOADER, readResource(ctx, ESP32_BOOTLOADER));
            flashRegions.put(FlashRegion.PARTITION_TABLE, readResource(ctx, ESP32_PARTITION_TABLE));
            flashRegions.put(FlashRegion.APP_BOOTLOADER, readResource(ctx, ESP32_BOOT_APP_0));
            flashRegions.put(FlashRegion.APP_0, readResource(ctx, ESP32_APP));
            StartStage flasher = EspFlasherApi.connect(getSerialTransport(usbSerialPort))
                .withCallBack(getProgressCallback(flashRegions, callback));
            callback.connectedToBootloader();
            flasher.withBaudRate(EspFlasherApi.ESP_ROM_BAUD_HIGHEST, b -> setBaudRate(usbSerialPort, b))
                .chipDetect()
                .loadStub()
                .withCompression(false)
                .writeFlash(ESP32.getRegion(FlashRegion.BOOTLOADER), flashRegions.get(FlashRegion.BOOTLOADER))
                .writeFlash(ESP32.getRegion(FlashRegion.PARTITION_TABLE), flashRegions.get(FlashRegion.PARTITION_TABLE))
                .writeFlash(ESP32.getRegion(FlashRegion.APP_BOOTLOADER), flashRegions.get(FlashRegion.APP_BOOTLOADER))
                .withCompression(true)
                .writeFlash(ESP32.getRegion(FlashRegion.APP_0), flashRegions.get(FlashRegion.APP_0))
                .reset();
            Log.i(TAG, "Firmware flash completed successfully.");
            callback.doneFlashing(true);
        } catch (Exception e) {
            Log.e(TAG, "Firmware flashing failed", e);
            callback.doneFlashing(false);
        } finally {
            isFlashing.set(false);
        }
    }

    public static boolean isFlashing() {
        return isFlashing.get();
    }

    @SneakyThrows
    private static void setBaudRate(UsbSerialPort usbSerialPort, int baudRate) {
        usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
    }

    private static SerialTransport getSerialTransport(UsbSerialPort usbSerialPort) {
        return new SerialTransport() {
            @Override
            public int read(byte[] buffer, int length) throws IOException {
                if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "Read operation interrupted, terminating.");
                    throw new IOException("Read operation interrupted");
                }
                return usbSerialPort.read(buffer, length, 100);
            }
            @Override
            public void write(byte[] buffer, int length) throws IOException {
                if (Thread.currentThread().isInterrupted()) {
                    Log.w(TAG, "Write operation interrupted, terminating.");
                    throw new IOException("Write operation interrupted");
                }
                usbSerialPort.write(buffer, length, 0);
            }
            @Override
            public void setControlLines(boolean dtr, boolean rts) throws IOException {
                usbSerialPort.setDTRandRTS(dtr, rts);
            }
        };
    }

    private static ProgressCallback getProgressCallback(Map<FlashRegion, byte[]> flashRegions, FirmwareCallback callback) {
        return new ProgressCallback() {
            int part = 0;
            final int[] regionSizes = Stream.of(
                    FlashRegion.BOOTLOADER,
                    FlashRegion.PARTITION_TABLE,
                    FlashRegion.APP_BOOTLOADER,
                    FlashRegion.APP_0)
                .mapToInt(region -> Objects.requireNonNull(flashRegions.get(region)).length).toArray();
            final int totalSize = IntStream.of(regionSizes).sum();
            int completedSoFar = 0;
            @Override
            public void onProgress(float pct) {
                if (part >= regionSizes.length) return;
                callback.reportProgress(Math.round((completedSoFar + regionSizes[part] * (pct / 100.0f)) * 100.0f / totalSize));
            }
            @Override
            public void onInfo(String value) {
                Log.d(TAG_ESP32_FLASHER, value);
            }
            @Override
            public void onEnd() {
                part++;
                completedSoFar = IntStream.of(regionSizes).limit(part).sum();
            }
        };
    }

    private static byte[] readResource(Context ctx, int resourceId) {
        Log.d(TAG, "Reading resource ID: " + resourceId);
        try (InputStream inputStream = ctx.getResources().openRawResource(resourceId);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new ResourceLoadingException(resourceId, e);
        }
    }
}
