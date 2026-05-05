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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles the asynchronous handshake process with the ESP32 over USB serial.
 * The firmware sends HELLO with version/status payload once radio initialization is complete.
 * This class supports clean chaining with timeouts, and can be restarted if the ESP32 reboots.
 */
@SuppressWarnings({"javaarchitecture:S7027"})
class ProtocolHandshake {
    private static final String TAG = ProtocolHandshake.class.getSimpleName();
    private static final int HELLO_TIMEOUT_MS = 60000;
    private enum HandshakeResult {INVALID, TOO_OLD, OK, RADIO_MODULE_NOT_FOUND}
    private final RadioAudioService radioAudioService;
    private ScheduledExecutorService protocolScheduler = Executors.newSingleThreadScheduledExecutor();
    private int handshakeSeq = 0;
    private int activeHandshakeId = 0;

    /**
     * Future completed when HELLO with version/status payload is received or timeout occurs.
     */
    private @NonNull CompletableFuture<Optional<Protocol.FirmwareVersion>> waitForHello =
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
        radioAudioService.getCallbacks().radioModuleHandshake();
        Log.i(TAG, handshakeLog(handshakeId, "start(): waiting for HELLO(version)"));
        startFor(handshakeId, waitForHello(handshakeId));
    }

    public void onDestroy() {
        protocolScheduler.shutdownNow();
    }

    private void startFor(int handshakeId, CompletableFuture<Optional<Protocol.FirmwareVersion>> waitHello) {
        waitHello
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
                Log.e(TAG, handshakeLog(handshakeId, "HELLO missing valid FirmwareVersion payload; firmware upgrade required"));
                radioAudioService.getCallbacks().outdatedFirmware(0);
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
                Log.i(TAG, handshakeLog(handshakeId, "HELLO version OK; proceeding with radio communication"));
                radioAudioService.setMode(RadioMode.RX);
                radioAudioService.openFirmwareAudio();
                // Turn off scanning if it was on (e.g. if radio was unplugged briefly and reconnected)
                radioAudioService.setScanning(false);
                radioAudioService.radioConnected();
        }
    }

    /**
     * Called when a HELLO command is received from the ESP32.
     * If a handshake is already waiting for HELLO, complete it.
     * If no handshake is active (e.g., due to unexpected reboot), validate the new HELLO payload.
     */
    public void onHelloReceived(Optional<Protocol.FirmwareVersion> version) {
        if (!waitForHello.isDone()) {
            Log.d(TAG, handshakeLog(activeHandshakeId, "HELLO received: " + version));
            waitForHello.complete(version);
        } else {
            int handshakeId = ++handshakeSeq;
            activeHandshakeId = handshakeId;
            Log.i(TAG, handshakeLog(handshakeId, "HELLO received after wait completed; validating rebooted firmware"));
            startFor(handshakeId, CompletableFuture.completedFuture(version));
        }
    }

    /**
     * Waits for the HELLO command from the ESP32 with a timeout.
     *
     * @return A future that completes when HELLO is received or times out.
     */
    private CompletableFuture<Optional<Protocol.FirmwareVersion>> waitForHello(int handshakeId) {
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
                Log.w(TAG, handshakeLog(handshakeId, "checkFirmwareVersionAndRadioStatus(): HELLO has no valid version payload"));
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
                if (ver.getDeviceState() != null) {
                    radioAudioService.handleInitialDeviceState(ver.getDeviceState());
                }
                radioAudioService.applyRadioPreferencesToFirmware(ver.isHasHl());
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
