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

package com.vagell.kv4pht.radio;

import android.util.Log;
import androidx.annotation.NonNull;
import com.vagell.kv4pht.firmware.FirmwareUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles the asynchronous, multistep handshake process with the ESP32 over USB serial.
 * Steps include waiting for a HELLO command, sending configuration, and verifying firmware version.
 * This class supports clean chaining with timeouts, and can be restarted if the ESP32 reboots.
 */
@SuppressWarnings({"javaarchitecture:S7027"})
class ProtocolHandshake {
    private static final String TAG = ProtocolHandshake.class.getSimpleName();
    private static final int HELLO_TIMEOUT_MS = 1500;
    private static final int FIRMWARE_VERSION_TIMEOUT_MS = 60000;
    private enum HandshakeResult {INVALID, TOO_OLD, OK, RADIO_MODULE_NOT_FOUND}
    private final RadioAudioService radioAudioService;
    private ScheduledExecutorService protocolScheduler = Executors.newSingleThreadScheduledExecutor();
    private int handshakeSeq = 0;
    private int activeHandshakeId = 0;

    /**
     * Future completed when HELLO is received or timeout occurs.
     */
    private @NonNull CompletableFuture<Void> waitForHello = CompletableFuture.completedFuture(null);
    /**
     * Future completed when firmware version is received or timeout occurs.
     */
    private @NonNull CompletableFuture<Optional<Protocol.FirmwareVersion>> waitFirmwareVersion =
        CompletableFuture.completedFuture(Optional.empty());

    public ProtocolHandshake(RadioAudioService radioAudioService) {
        this.radioAudioService = radioAudioService;
    }

    /**
     * Starts the full handshake process.
     */
    public void start() {
        if (protocolScheduler.isShutdown()) {
            protocolScheduler = Executors.newSingleThreadScheduledExecutor();
        }
        int handshakeId = ++handshakeSeq;
        activeHandshakeId = handshakeId;
        Log.i(TAG, handshakeLog(handshakeId, "start(): waiting for HELLO"));
        startFor(handshakeId, waitForHello(handshakeId)
            .thenCompose(ignored -> sendConfigStep(handshakeId)));
    }

    public void onDestroy() {
        protocolScheduler.shutdownNow();
    }

    private void startFor(int handshakeId, CompletionStage<Void> chain) {
        chain
            .thenCompose(ignored -> waitForFirmwareVersion(handshakeId))
            .thenCompose(version -> checkFirmwareVersionAndRadioStatus(handshakeId, version))
            .thenAccept(res -> handleResult(handshakeId, res))
            .exceptionally(ex -> {
                Log.e(TAG, handshakeLog(handshakeId, "chain failed"), ex);
                radioAudioService.setMode(RadioMode.BAD_FIRMWARE);
                radioAudioService.getCallbacks().missingFirmware();
                return null;
            })
            .whenComplete((ignored, ex) -> {
                Log.d(TAG, handshakeLog(handshakeId, "complete() ex=" + (ex != null)));
                radioAudioService.onHandshakeCompleted();
            });
    }

    private void handleResult(int handshakeId, HandshakeResult res) {
        switch (res) {
            case INVALID:
                Log.e(TAG, handshakeLog(handshakeId, "invalid FirmwareVersion packet"));
                radioAudioService.getCallbacks().missingFirmware();
                radioAudioService.setMode(RadioMode.BAD_FIRMWARE);
                return;
            case TOO_OLD:
                Log.w(TAG, handshakeLog(handshakeId, "firmware version too old"));
                radioAudioService.setMode(RadioMode.BAD_FIRMWARE);
                return;
            case RADIO_MODULE_NOT_FOUND:
                Log.w(TAG, handshakeLog(handshakeId, "radio module not found"));
                radioAudioService.setMode(RadioMode.BAD_FIRMWARE);
                radioAudioService.getCallbacks().radioModuleNotFound();
                return;
            case OK:
                Log.i(TAG, handshakeLog(handshakeId, "firmware version OK; proceeding with radio communication"));
                radioAudioService.setMode(RadioMode.RX);
                // Turn off scanning if it was on (e.g. if radio was unplugged briefly and reconnected)
                radioAudioService.setScanning(false);
                radioAudioService.radioConnected();
        }
    }

    /**
     * Called when a HELLO command is received from the ESP32.
     * If a handshake is already waiting for HELLO, complete it.
     * If no handshake is active (e.g., due to unexpected reboot), restart the handshake from config step.
     */
    public void onHelloReceived() {
        if (!waitForHello.isDone()) {
            Log.d(TAG, handshakeLog(activeHandshakeId, "HELLO received"));
            waitForHello.complete(null);
        } else {
            int handshakeId = ++handshakeSeq;
            activeHandshakeId = handshakeId;
            Log.i(TAG, handshakeLog(handshakeId, "HELLO received after wait completed; restarting from config step"));
            startFor(handshakeId, sendConfigStep(handshakeId)); // ESP32 rebooted mid-session, restart from config step
        }
    }

    /**
     * Notifies the handshake logic that a firmware version was received from the ESP32.
     * Completes the waiting future if it's active.
     *
     * @param version The firmware version parsed from the received data.
     * @noinspection OptionalUsedAsFieldOrParameterType
     */
    public void onVersionReceived(Optional<Protocol.FirmwareVersion> version) {
        if (!waitFirmwareVersion.isDone()) {
            Log.d(TAG, handshakeLog(activeHandshakeId, "firmware version received: " + version));
            waitFirmwareVersion.complete(version);
        }
    }

    /**
     * Waits for the HELLO command from the ESP32 with a timeout.
     *
     * @return A future that completes when HELLO is received or times out.
     */
    private CompletableFuture<Void> waitForHello(int handshakeId) {
        waitForHello = new CompletableFuture<>();
        Log.d(TAG, handshakeLog(handshakeId, "waitForHello(): timeout=" + HELLO_TIMEOUT_MS + "ms"));
        protocolScheduler.schedule(() -> {
            if (!waitForHello.isDone()) {
                Log.w(TAG, handshakeLog(handshakeId, "waitForHello(): timed out"));
                waitForHello.completeExceptionally(new TimeoutException("Timeout waiting for HELLO"));
            }
        }, HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return waitForHello;
    }

    /**
     * Sends configuration to the ESP32.
     * Stops any previous communication, notifies callbacks, and sends power config.
     *
     * @return A future that completes once the config is sent.
     */
    private CompletableFuture<Void> sendConfigStep(int handshakeId) {
        return CompletableFuture.runAsync(() -> {
            radioAudioService.getCallbacks().radioModuleHandshake();
            radioAudioService.setMode(RadioMode.STARTUP);
            radioAudioService.getHostToEsp32().stop();
            Protocol.Config cfg = Protocol.Config.builder().isHigh(radioAudioService.isHighPower()).build();
            Log.d(TAG, handshakeLog(handshakeId, "sendConfigStep(): sending " + cfg));
            radioAudioService.getHostToEsp32().config(cfg);
        });
    }

    /**
     * Waits for a firmware version response from the ESP32 with a timeout.
     *
     * @return A future that completes when version is received or times out.
     */
    private CompletableFuture<Optional<Protocol.FirmwareVersion>> waitForFirmwareVersion(int handshakeId) {
        waitFirmwareVersion = new CompletableFuture<>();
        Log.d(TAG, handshakeLog(handshakeId, "waitForFirmwareVersion(): timeout=" + FIRMWARE_VERSION_TIMEOUT_MS + "ms"));
        protocolScheduler.schedule(() -> {
            if (!waitFirmwareVersion.isDone()) {
                Log.w(TAG, handshakeLog(handshakeId, "waitForFirmwareVersion(): timed out"));
                waitFirmwareVersion.completeExceptionally(new TimeoutException("Timeout waiting for firmware version"));
            }
        }, FIRMWARE_VERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return waitFirmwareVersion;
    }

    /**
     * Starts communication with the ESP32 after verifying the firmware version.
     * Updates mode and notifies the app if the firmware is outdated or the radio module is missing.
     *
     * @param firmwareVersion The firmware version received from the ESP32.
     * @return A future that completes when initialization is done.
     * @noinspection OptionalUsedAsFieldOrParameterType
     */
    private CompletableFuture<HandshakeResult> checkFirmwareVersionAndRadioStatus(int handshakeId, Optional<Protocol.FirmwareVersion> firmwareVersion) {
        return CompletableFuture.supplyAsync(() -> {
            if (!firmwareVersion.isPresent()) {
                Log.w(TAG, handshakeLog(handshakeId, "checkFirmwareVersionAndRadioStatus(): no firmware version present"));
                return HandshakeResult.INVALID;
            }
            final Protocol.FirmwareVersion ver = firmwareVersion.get();
            Log.d(TAG, handshakeLog(handshakeId, "checkFirmwareVersionAndRadioStatus(): version=" + ver));
            radioAudioService.getCallbacks().firmwareVersionReceived(ver.getVer());
            if (ver.getVer() < FirmwareUtils.PACKAGED_FIRMWARE_VER) {
                radioAudioService.getCallbacks().outdatedFirmware(ver.getVer());
                return HandshakeResult.TOO_OLD;
            }
            radioAudioService.setRadioType(Protocol.RfModuleType.RF_SA818_VHF.equals(ver.getModuleType())
                ? RadioAudioService.RadioModuleType.VHF
                : RadioAudioService.RadioModuleType.UHF);
            radioAudioService.setHasHighLowPowerSwitch(ver.isHasHl());
            radioAudioService.setHasPhysPttButton(ver.isHasPhysPtt());
            if (Protocol.RadioStatus.RADIO_STATUS_NOT_FOUND.equals(ver.getRadioModuleStatus())) {
                return HandshakeResult.RADIO_MODULE_NOT_FOUND;
            } else {
                radioAudioService.getHostToEsp32().setFlowControlWindow(ver.getWindowSize());
                return HandshakeResult.OK;
            }
        });
    }

    private String handshakeLog(int handshakeId, String message) {
        return "handshake#" + handshakeId
            + "/connect#" + radioAudioService.getActiveUsbConnectAttemptId()
            + " " + RadioAudioService.threadTag()
            + " " + message;
    }
}
