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

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.APRSTypes;
import com.vagell.kv4pht.aprs.parser.Digipeater;
import com.vagell.kv4pht.aprs.parser.InformationField;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.Parser;
import com.vagell.kv4pht.aprs.parser.Position;
import com.vagell.kv4pht.aprs.parser.PositionField;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.firmware.FirmwareUtils;
import com.vagell.kv4pht.javAX25.ax25.Packet;
import com.vagell.kv4pht.radio.Protocol.KissParser;
import com.vagell.kv4pht.radio.Protocol.RcvCommand;
import com.vagell.kv4pht.radio.Protocol.WindowUpdate;
import com.vagell.kv4pht.ui.MainActivity;
import com.vagell.kv4pht.ui.ToneHelper;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Background service that manages the connection to the ESP32 (to control the radio), and
 * handles playing back any audio received from the radio. This frees up the rest of the
 * application to focus primarily on the setup flows and UI, and ensures that the radio audio
 * continues to play even if the phone's screen is off or the user starts another app.
 */
public class RadioAudioService extends Service {

    // === Constants ===
    private static final String TAG = RadioAudioService.class.getSimpleName();
    private static final String FIRMWARE_TAG = "firmware";
    private static final String ACTION_USB_PERMISSION = "com.vagell.kv4pht.USB_PERMISSION";
    private static final int RUNAWAY_TX_TIMEOUT_SEC = 180;
    // Intents this Activity can handle besides the one that starts it in default mode.
    public static final String INTENT_OPEN_CHAT = "com.vagell.kv4pht.OPEN_CHAT_ACTION";
    public static final String ACTION_STOP_SERVICE = "com.vagell.kv4pht.STOP_RADIO_SERVICE";
    public static final String ACTION_SERVICE_STOPPING = "com.vagell.kv4pht.SERVICE_STOPPING";


    // === USB Device Matching ===
    private static final int[] ESP32_VENDOR_IDS = {4292, 6790};
    private static final int[] ESP32_PRODUCT_IDS = {60000, 29987};

    // === Audio Constants ===
    public static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int RX_AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    private static final int RX_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    public static final int OPUS_FRAME_SIZE = 1920; // 40ms at 48kHz
    private static final int RX_AUDIO_MIN_BUFFER_SIZE =
            AudioTrack.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    RX_AUDIO_CHANNEL_CONFIG,
                    RX_AUDIO_FORMAT);

    // === APRS Constants ===
    public static final int APRS_POSITION_EXACT = 0;
    public static final int APRS_POSITION_APPROX = 1;
    public static final int APRS_BEACON_MINS = 5;
    private static final int APRS_MAX_MESSAGE_NUM = 99999;
    private static final String MESSAGE_NOTIFICATION_CHANNEL_ID = "aprs_message_notifications";
    private static final int MESSAGE_NOTIFICATION_TO_YOU_ID = 0;
    public static final List<Digipeater> DEFAULT_DIGIPEATERS = List.of(new Digipeater("WIDE1*"), new Digipeater("WIDE2-1"));
    private static final long SCAN_SQUELCHED_ADVANCE_DELAY_MS = 250L;

    // === Used for the persistent notification ===
    private PowerManager.WakeLock wakeLock;
    private static final int SERVICE_ID = 1;

    // These will be overwritten by user settings
    @Setter
    private float min2mTxFreq = 144.0f;
    @Setter
    private float max2mTxFreq = 148.0f;
    @Setter
    private float min70cmTxFreq = 420.0f;
    @Setter
    private float max70cmTxFreq = 450.0f;

    @Setter
    private float minTxFreq = min2mTxFreq;
    @Setter
    private float maxTxFreq = max2mTxFreq;

    public enum RadioModuleType {UNKNOWN, VHF, UHF}

    // === Audio / Opus Handling ===
    private final float[] pcmFloat = new float[OPUS_FRAME_SIZE];
    private AudioTrack audioTrack;
    private float audioTrackVolume = 0.0f;
    private AudioFocusRequest audioFocusRequest;
    private final OpusUtils.OpusDecoderWrapper opusDecoder =
        new OpusUtils.OpusDecoderWrapper(AUDIO_SAMPLE_RATE, OPUS_FRAME_SIZE);
    private final OpusUtils.OpusEncoderWrapper opusEncoder =
        new OpusUtils.OpusEncoderWrapper(AUDIO_SAMPLE_RATE, OPUS_FRAME_SIZE);
    private final byte[] txAudioFrame = new byte[Protocol.PROTO_MTU];

    // === USB / Serial ===
    private UsbManager usbManager;
    @Getter
    private UsbSerialPort serialPort;
    private SerialInputOutputManager usbIoManager;
    private boolean usbPermissionRequestPending = false;
    @Getter
    private Protocol.Sender hostToEsp32;
    @Getter
    private final RadioModuleController radioModule = new RadioModuleController();
    private final KissParser esp32DataStreamParser = new KissParser(this::handleParsedCommand, this::handleEsp32Ax25Packet);
    private int usbConnectAttemptSeq = 0;
    private int activeUsbConnectAttemptId = 0;

    // === APRS State ===
    private boolean aprsBeaconPosition = false;
    private String aprsBeaconFrequency = "Current";
    @Getter
    @Setter
    private int aprsPositionAccuracy = APRS_POSITION_EXACT;
    private ScheduledExecutorService beaconScheduler;
    private ScheduledFuture<?> beaconFuture;
    private int messageNumber = 0;

    // === Protocol Handshake ===
    private static final int HELLO_TIMEOUT_MS = 60000;
    private int handshakeSeq = 0;
    private int activeHandshakeId = 0;
    private boolean waitingForHello = false;
    private Runnable helloTimeoutRunnable;

    // === Radio State ===
    @Getter
    private @NonNull RadioMode mode = RadioMode.STARTUP;
    @Setter
    private @NonNull String callsign = "";
    @Getter
    private @NonNull String activeFrequencyStr = "";
    private int activeMemoryId = -1;
    private int scanBaseSquelch = -1;
    private Runnable pendingScanAdvance;
    private int pendingScanAdvanceMemoryId = -1;
    private MicGainBoost micGainBoost = MicGainBoost.NONE;

    // === Android Components ===
    private final IBinder binder = new RadioBinder();
    private static final RadioAudioServiceCallbacks NO_OP_CALLBACKS = new RadioAudioServiceCallbacks() {};
    @Setter
    @Getter
    private @NonNull RadioAudioServiceCallbacks callbacks = NO_OP_CALLBACKS;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final long CONNECT_RETRY_PERIOD_MS = 500L;
    private final ConnectionController connectionController =
        new ConnectionController(handler, CONNECT_RETRY_PERIOD_MS, this::isConnectionReady, this::attemptUsbConnect);
    private boolean radioMissingNotified = false;
    private Runnable txTimeoutHandler;
    private LiveData<List<ChannelMemory>> channelMemoriesLiveData = null;

    /**
     * Class used for the client Binder. This service always runs in the same process as its clients.
     */
    public class RadioBinder extends Binder {
        public RadioAudioService getService() {
            // Return this instance of RadioService so clients can call public methods.
            return RadioAudioService.this;
        }
    }

    /**
     * Callbacks for the activity to implement, so it can be notified of various events.
     */
    public interface RadioAudioServiceCallbacks {
        default void radioMissing() {}
        default void radioConnected() {}
        default void hideSnackBar() {}
        default void radioModuleHandshake() {}
        default void radioModuleNotFound() {}
        default void audioTrackCreated() {}
        default void packetReceived(APRSPacket aprsPacket) {}
        default void startingAprsBeacon(String frequencyStr) {}
        default void scannedToMemory(int memoryId) {}
        default void tunedToFreq(String frequencyStr) {}
        default void outdatedFirmware(int firmwareVer) {}
        default void initialDeviceStateReceived() {}
        default void missingFirmware() {}
        default void txStarted() {}
        default void txEnded() {}
        default void moduleTxStateChanged(boolean txActive) {}
        default void chatError(String text) {}
        default void sMeterUpdate(int value) {}
        default void sentAprsBeacon(double latitude, double longitude, String frequencyStr, boolean wasSwitch) {}
        default void unknownLocation() {}
        default void forcedPttStart() {}
        default void forcedPttEnd() {}
        default void setRadioType(RadioModuleType ratioType) {}
        default void showNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        Bundle bundle = intent.getExtras();
        if (null == bundle) {
            Log.d(TAG, "Warning: RadioAudioService started without parameters, likely in a bad state.");
            return binder;
        }

        // Retrieve necessary parameters from the intent.
        callsign = Optional.ofNullable(bundle.getString("callsign")).orElse("");
        if (bundle.containsKey("squelch")) {
            radioModule.seedDesiredSquelch(bundle.getInt("squelch"));
        }
        activeMemoryId = bundle.getInt("activeMemoryId");
        activeFrequencyStr = Optional.ofNullable(bundle.getString("activeFrequencyStr")).orElse("");
        return binder;
    }

    public void setMicGainBoost(String micGainBoost) {
        this.micGainBoost = MicGainBoost.parse(micGainBoost);
    }

    public void setAprsBeaconPosition(boolean enabled) {
        if (this.aprsBeaconPosition != enabled) {
            this.aprsBeaconPosition = enabled;
            if (enabled) {
                startBeaconScheduler();
            } else if (beaconFuture != null) {
                stopBeaconScheduler();
            }
        }
    }

    public void setAprsBeaconFrequency(String frequency) {
        this.aprsBeaconFrequency = frequency;
    }

    public boolean getAprsBeaconPosition() {
        return this.aprsBeaconPosition;
    }

    private void startBeaconScheduler() {
        if (beaconScheduler == null || beaconScheduler.isShutdown()) {
            beaconScheduler = Executors.newSingleThreadScheduledExecutor();
        }
        // Cancel any old task
        if (beaconFuture != null) {
            beaconFuture.cancel(false);
        }

        // First run now (or after initial delay), then every 5 minutes
        beaconFuture = beaconScheduler.scheduleAtFixedRate(() -> {
            try {
                // Acquire a short wakelock just for the beacon if you prefer not to keep it held.
                if (wakeLock != null && !wakeLock.isHeld()) {
                    // 20 seconds is usually ample for a single beacon
                    wakeLock.acquire(20_000);
                }
                if (aprsBeaconPosition) {
                    sendPositionBeacon();  // uses FusedLocation + TX
                }
            } catch (Throwable t) {
                Log.w(TAG, "Beacon task error", t);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Throwable ignored) {}
                }
            }
        }, 0, APRS_BEACON_MINS, TimeUnit.MINUTES);
    }

    private void stopBeaconScheduler() {
        if (beaconFuture != null) {
            beaconFuture.cancel(false);
            beaconFuture = null;
        }
        if (beaconScheduler != null) {
            beaconScheduler.shutdownNow();
            beaconScheduler = null;
        }
    }

    public void setMode(RadioMode mode) {
        if (mode == RadioMode.FLASHING) {
            radioModule.stop();
            audioTrack.stop();
            usbIoManager.stop();
            try {
                serialPort.setDTR(false);
                serialPort.setRTS(true);
                Thread.sleep(100);
                serialPort.setDTR(true);
                serialPort.setRTS(false);
                Thread.sleep(50);
                serialPort.setDTR(false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                Log.e(TAG, "Error while restart ESP32.", e);
            }
        }

        RadioMode previousMode = this.mode;
        this.mode = mode;
        if (previousMode != mode) {
            syncFirmwareAudioStateForMode(mode);
        }
    }

    private void syncFirmwareAudioStateForMode(RadioMode mode) {
        if (!isConnectionReady()) {
            return;
        }
        if (mode == RadioMode.RX || mode == RadioMode.SCAN) {
            openFirmwareAudio();
        } else if (mode == RadioMode.STARTUP || mode == RadioMode.BAD_FIRMWARE || mode == RadioMode.UNKNOWN) {
            radioModule.closeAudio();
        }
    }

    void openFirmwareAudio() {
        if (isConnectionReady()) {
            radioModule.openAudio();
        }
    }

    public void setActiveMemoryId(int activeMemoryId) {
        this.activeMemoryId = activeMemoryId;
        if (activeMemoryId > -1) {
            tuneToMemory(activeMemoryId);
        } else {
            tuneToFreq(activeFrequencyStr);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Keep CPU on while service is running so we can play and process audio
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "RadioAudioService::Playback");
        wakeLock.setReferenceCounted(false);

        // Create channel for the persistent notification user can interact with
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    "KV4P_HT_RADIO_AUDIO",
                    "kv4p HT audio",
                    NotificationManager.IMPORTANCE_DEFAULT);
            chan.setSound(null, null); // no sound for the notification itself
            chan.setShowBadge(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(chan);
        }

        SecureRandom random = new SecureRandom();
        messageNumber = random.nextInt(APRS_MAX_MESSAGE_NUM); // Start with any Message # from 0-99999, we'll increment it by 1 each tx until restart.
    }

    /**
     * Bound activities should call this when they're done providing any data (via setters), including the several
     * necessary callback handlers.
     */
    public void start() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        createNotificationChannels();
        initAudioTrack();
        connectionController.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            Intent stopIntent = new Intent(ACTION_SERVICE_STOPPING);
            stopIntent.setPackage(getPackageName());
            sendBroadcast(stopIntent);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Build an ongoing notification
        Notification notification = buildForegroundNotification();

        try {
            startForeground(SERVICE_ID, notification);
        } catch (SecurityException e) {
            Log.e(TAG, "Unable to start foreground radio service.", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Make the service sticky so it is restarted if the process dies
        return START_STICKY;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private Notification buildForegroundNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openApp, PendingIntent.FLAG_IMMUTABLE);

        // Create an Intent that will be sent when the user swipes the notification away.
        Intent stopSelf = new Intent(this, RadioAudioService.class);
        stopSelf.setAction(ACTION_STOP_SERVICE);
        PendingIntent pStopSelf = PendingIntent.getService(this, 0, stopSelf, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, "KV4P_HT_RADIO_AUDIO")
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle("kv4p HT")
                .setContentText("Starting up...")
                .setContentIntent(pi)
                .setDeleteIntent(pStopSelf)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // visible on lock screen
                .build();
    }

    private void updateForegroundNotification(String text) {
        // Create an Intent that will be sent when the user swipes the notification away.
        Intent stopSelf = new Intent(this, RadioAudioService.class);
        stopSelf.setAction(ACTION_STOP_SERVICE);
        PendingIntent pStopSelf = PendingIntent.getService(this, 0, stopSelf, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, "KV4P_HT_RADIO_AUDIO")
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle("kv4p HT")
                .setContentText(text)
                .setContentIntent(buildPendingIntent())
                .setDeleteIntent(pStopSelf)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // visible on lock screen
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(SERVICE_ID, notification);
    }

    private PendingIntent buildPendingIntent() {
        Intent open = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * This must be set before any method that requires channels (like scanning or tuning to a memory) is access, or
     * they will just report an error. And it should also be called whenever the active memories have changed (e.g.
     * user selected a different memory group).
     */
    public void setChannelMemories(LiveData<List<ChannelMemory>> channelMemoriesLiveData) {
        this.channelMemoriesLiveData = channelMemoriesLiveData;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        tryToStopRadioModule();
        connectionController.stop();
        cancelHelloTimeout();

        // Clean up APRS beacon executor
        if (this.beaconScheduler != null && !beaconScheduler.isShutdown()) {
            beaconScheduler.shutdownNow();
        }

        // Clean up USB resources to prevent race conditions on restart
        if (usbIoManager != null) {
            usbIoManager.stop();
            usbIoManager = null;
        }
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (IOException e) {
                // Ignore, closing anyway.
            }
            serialPort = null;
        }

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void tryToStopRadioModule() {
        if (isConnectionReady() && (mode == RadioMode.RX || mode == RadioMode.TX || mode == RadioMode.SCAN)) {
            try {
                Log.d(TAG, "Sending stop to ESP32...");
                radioModule.closeAudio();
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Ignore, we are shutting down anyway.
            }
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf();
    }

    private void createNotificationChannels() {
        // Notification channel for APRS text chat messages
        NotificationChannel channel = new NotificationChannel(MESSAGE_NOTIFICATION_CHANNEL_ID,
            "Chat messages", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("APRS text chat messages addressed to you");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    /**
     * Determines if transmission (TX) is allowed on the given frequency.
     *
     * @param freq The frequency to check, in MHz.
     * @return true if the frequency is within the allowed transmission range, false otherwise.
     */
    private boolean canTransmitOnFrequency(float freq) {
        final float halfBandwidth = radioModule.getHalfBandwidthMhz();
        return  (freq >= (minTxFreq + halfBandwidth)) && (freq <= (maxTxFreq - halfBandwidth));
    }

    private void updateTxAllowed(float txFrequency) {
        radioModule.setTxAllowed(canTransmitOnFrequency(txFrequency));
    }

    public boolean isTxAllowed() {
        return radioModule.isTxAllowed();
    }

    public void tuneToFreq(String frequencyStr) {
        if (mode == RadioMode.STARTUP) {
            return; // Not fully loaded and initialized yet, don't tune.
        }
        setMode(RadioMode.RX);
        float freq;
        try {
            freq = Float.parseFloat(makeSafeHamFreq(frequencyStr));
            updateForegroundNotification("Simplex " + frequencyStr + " MHz");
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid frequency string: " + frequencyStr, e);
            return;
        }
        activeFrequencyStr = frequencyStr;
        activeMemoryId = -1; // Reset active memory ID since we're tuning to a frequency, not a memory.
        callbacks.tunedToFreq(activeFrequencyStr);
        radioModule.beginUpdate();
        try {
            radioModule.setMemoryId(-1);
            radioModule.setTxFrequency(freq);
            radioModule.setRxFrequency(freq);
            radioModule.setTxTone((byte) 0);
            radioModule.setRxTone((byte) 0);
            updateTxAllowed(freq);
        } finally {
            radioModule.endUpdate();
        }
    }

    public String makeSafeHamFreq(String strFreq) {
        try {
            float freq = Float.parseFloat(strFreq);
            // Normalize values like "1467" → "146.7"
            while (freq > 500.0f) {
                freq /= 10;
            }
            return formatFreq(Math.max(getMinRadioFreq(), Math.min(freq, getMaxRadioFreq())));
        } catch (NumberFormatException e) {
            return formatFreq(minTxFreq);
        }
    }

    private static String formatFreq(float freq) {
        return String.format(java.util.Locale.US, "%.4f", freq);
    }

    public String validateFrequency(String tempFrequency) {
        // Resort to the old frequency, the one the user inputted is unsalvageable.
        return makeSafeHamFreq(tempFrequency);
    }

    public void tuneToMemory(int memoryId) {
        Optional.ofNullable(channelMemoriesLiveData)
            .map(LiveData::getValue)
            .orElse(Collections.emptyList())
            .stream()
            .filter(channelMemory -> channelMemory.memoryId == memoryId)
            .findFirst()
            .ifPresent(this::tuneToMemory);
    }

    public void tuneToMemory(ChannelMemory memory) {
        if (memory == null || mode == RadioMode.STARTUP) {
            return;
        }
        activeFrequencyStr = validateFrequency(memory.frequency);
        activeMemoryId = memory.memoryId;
        final float txFreq = Float.parseFloat(getTxFreq(memory.frequency, memory.offset, memory.offsetKhz));
        radioModule.beginUpdate();
        try {
            radioModule.setMemoryId(memory.memoryId);
            radioModule.setTxFrequency(txFreq);
            radioModule.setRxFrequency(Float.parseFloat(makeSafeHamFreq(activeFrequencyStr)));
            radioModule.setTxTone((byte) Math.max(0, ToneHelper.getToneIndex(memory.txTone)));
            radioModule.setRxTone((byte) Math.max(0, ToneHelper.getToneIndex(memory.rxTone)));
            updateTxAllowed(txFreq);
        } finally {
            radioModule.endUpdate();
        }
        updateForegroundNotification(memory.name + " (" + memory.frequency + " MHz)");
        callbacks.scannedToMemory(memory.memoryId);
    }

    private String getTxFreq(String txFreq, int offset, int khz) {
        if (offset == ChannelMemory.OFFSET_NONE) {
            return txFreq;
        } else {
            float freqFloat = Float.parseFloat(txFreq);
            if (offset == ChannelMemory.OFFSET_UP) {
                freqFloat += 0f + (khz / 1000f);
            } else if (offset == ChannelMemory.OFFSET_DOWN) {
                freqFloat -= 0f + (khz / 1000f);
            }
            return makeSafeHamFreq(Float.toString(freqFloat));
        }
    }

    private void checkScanDueToSquelch() {
        if (getMode() != RadioMode.SCAN) {
            cancelPendingScanAdvance();
            return;
        }
        if (radioModule.getMemoryId() == activeMemoryId && radioModule.isSquelched()) {
            scheduleScanAdvance(activeMemoryId);
        } else {
            cancelPendingScanAdvance();
        }
    }

    private void scheduleScanAdvance(int memoryId) {
        if (pendingScanAdvance != null && pendingScanAdvanceMemoryId == memoryId) {
            return;
        }
        cancelPendingScanAdvance();
        pendingScanAdvanceMemoryId = memoryId;
        pendingScanAdvance = () -> {
            pendingScanAdvance = null;
            pendingScanAdvanceMemoryId = -1;
            if (getMode() == RadioMode.SCAN
                && activeMemoryId == memoryId
                && radioModule.getMemoryId() == memoryId
                && radioModule.isSquelched()) {
                nextScan();
            }
        };
        handler.postDelayed(pendingScanAdvance, SCAN_SQUELCHED_ADVANCE_DELAY_MS);
    }

    private void cancelPendingScanAdvance() {
        if (pendingScanAdvance != null) {
            handler.removeCallbacks(pendingScanAdvance);
            pendingScanAdvance = null;
            pendingScanAdvanceMemoryId = -1;
        }
    }

    private void initAudioTrack() {
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .build();
        audioTrack = new AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(RX_AUDIO_FORMAT)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(RX_AUDIO_MIN_BUFFER_SIZE)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build();
        audioTrack.setVolume(0.0f);
        audioTrackVolume = 0.0f;
        audioTrack.setAuxEffectSendLevel(0.0f);
        callbacks.audioTrackCreated();
    }

    private void setTxRunAwayTimer() {
        // cancel any existing timeout
        if (txTimeoutHandler != null) {
            handler.removeCallbacks(txTimeoutHandler);
        }
        txTimeoutHandler = () -> {
            if (RadioMode.TX.equals(getMode())) {
                Log.d(TAG, "Warning: runaway TX timeout reached, PTT stopped.");
                endPtt();
            }
        };
        handler.postDelayed(txTimeoutHandler, RUNAWAY_TX_TIMEOUT_SEC * 1000L);
    }

    public void startPtt() {
        if (hostToEsp32 == null) {
            Log.e(TAG, "Attempted to start PTT but hostToEsp32 is null. USB connection likely failed.");
            radioMissing();
            return;
        }
        if (mode == RadioMode.RX && isTxAllowed()) {
            setMode(RadioMode.TX);
            callbacks.sMeterUpdate(0);
            setTxRunAwayTimer();
            radioModule.pttDown();
            audioTrackVolume = 0.0f;
            Optional.ofNullable(audioTrack).ifPresent(t -> t.setVolume(0.0f));
            callbacks.txStarted();
        } else {
            Log.w(TAG, "Attempted to start PTT when not allowed", new Throwable());
        }
    }

    public void endPtt() {
        if (mode == RadioMode.TX) {
            setMode(RadioMode.RX);
            audioTrackVolume = 0.0f;
            Optional.ofNullable(audioTrack).ifPresent(t -> t.setVolume(0.0f));
            radioModule.pttUp();
            callbacks.txEnded();
        }
    }

    public void reconnectViaUSB() {
        Log.i(TAG, connectLog("reconnectViaUSB(): clearing pending state for next attempt"));
        usbPermissionRequestPending = false;
        // Re-plug is an explicit user/device action; allow connection attempts again.
        radioMissingNotified = false;
        connectionController.markAttemptFinished();
    }

    public void renegotiateAfterFlashing() {
        Log.i(TAG, connectLog("renegotiateAfterFlashing(): closing port and resetting state before renegotiation"));
        closePortAndReset();
        reconnectViaUSB();
    }

    public void onUsbPermissionDenied() {
        Log.w(TAG, connectLog("USB permission denied by system dialog"));
        usbPermissionRequestPending = false;
        radioMissing();
    }

    private boolean isConnectionReady() {
        return hostToEsp32 != null
            && serialPort != null
            && usbIoManager != null;
    }

    private void closePortAndReset() {
        waitingForHello = false;
        cancelHelloTimeout();
        radioModule.detachSender();
        hostToEsp32 = null;
        if (usbIoManager != null) {
            try {
                usbIoManager.stop();
            } catch (Exception ignored) {
                // Best-effort cleanup during teardown; transport may already be stopping.
            }
            usbIoManager = null;
        }
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (Exception ignored) {
                // Best-effort cleanup during teardown; port may already be closed.
            }
            serialPort = null;
        }
    }

    private void attemptUsbConnect() {
        activeUsbConnectAttemptId = ++usbConnectAttemptSeq;
        Log.d(TAG, connectLog("attemptUsbConnect(): starting; state=" + connectionStateSummary()));
        if (isConnectionReady()) {
            Log.d(TAG, connectLog("attemptUsbConnect(): already connected, skipping enumeration"));
            connectionController.markAttemptFinished();
            return;
        }
        setMode(RadioMode.STARTUP);
        clearRadioTypeAndLimits();
        Optional<UsbDevice> device = usbManager.getDeviceList().values().stream()
            .filter(this::isESP32Device)
            .findFirst();
        if (device.isPresent()) {
            Log.d(TAG, connectLog("attemptUsbConnect(): found ESP32 device"));
            callbacks.hideSnackBar();
            setupSerialConnection();
            return;
        }
        Log.d(TAG, connectLog("attemptUsbConnect(): no ESP32 detected"));
        radioMissing();
    }

    private boolean isESP32Device(UsbDevice device) {
        int vendorId = device.getVendorId();
        int productId = device.getProductId();
        Log.d(TAG, String.format("Checking USB device: vendorId=%d, productId=%d, product=\"%s\"",
            vendorId, productId, device.getProductName()));
        for (int i = 0; i < ESP32_VENDOR_IDS.length; i++) {
            if (vendorId == ESP32_VENDOR_IDS[i] && productId == ESP32_PRODUCT_IDS[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets up the USB serial connection to the ESP32, and starts listening for data.
     * If no ESP32 is found, it will call radioMissing() on the callbacks.
     */
    public void setupSerialConnection() {
        Log.d(TAG, connectLog("setupSerialConnection(): begin"));
        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            Log.d(TAG, connectLog("setupSerialConnection(): no available USB drivers"));
            radioMissing();
            return;
        }
        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        if (!manager.hasPermission(driver.getDevice())) {
            if (usbPermissionRequestPending) {
                Log.d(TAG, connectLog("setupSerialConnection(): USB permission request already pending"));
                return;
            }
            Log.i(TAG, connectLog("setupSerialConnection(): requesting USB permission"));
            usbPermissionRequestPending = true;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            manager.requestPermission(driver.getDevice(), permissionIntent);
            return;
        }
        usbPermissionRequestPending = false;
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            Log.w(TAG, connectLog("setupSerialConnection(): couldn't open USB device"));
            radioMissing();
            return;
        }
        serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        Log.d(TAG, connectLog("setupSerialConnection(): serialPort=" + serialPort));
        try {
            serialPort.open(connection);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (Exception e) {
            Log.w(TAG, connectLog("setupSerialConnection(): couldn't open USB serial port"), e);
            closePortAndReset();
            radioMissing();
            return;
        }
        try { // These settings needed for better data transfer on Adafruit QT Py ESP32-S2
            serialPort.setRTS(true);
            serialPort.setDTR(true);
        } catch (Exception e) {
            // Ignore, may not be supported on all devices.
        }
        usbIoManager = new SerialInputOutputManager(serialPort, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                esp32DataStreamParser.processBytes(data);
            }
            @Override
            public void onRunError(Exception e) {
                Log.w(TAG, connectLog("onRunError(): error reading from ESP32; state=" + connectionStateSummary()), e);
                if (audioTrack != null) {
                    audioTrack.stop();
                }
                closePortAndReset();
                radioMissing();
            }
        });
        usbIoManager.setWriteBufferSize(90000); // Must be large enough that ESP32 can take its time accepting our bytes without overrun.
        usbIoManager.setReadBufferSize(1024); // Must not be 0 (infinite) or it may block on read() until a write() occurs.
        usbIoManager.setReadBufferCount(16 * 2);
        usbIoManager.start();
        hostToEsp32 = new Protocol.Sender(usbIoManager);
        radioModule.attachSender(hostToEsp32);
        Log.i(TAG, connectLog("setupSerialConnection(): serial transport connected; starting handshake"));
        startProtocolHandshake();
    }

    public void radioConnected() {
        Log.i(TAG, connectLog("radioConnected(): handshake complete; state=" + connectionStateSummary()));
        connectionController.markAttemptFinished();
        radioMissingNotified = false;
        // Acquire WakeLock if not already held to ensure audio processing continues in background.
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        callbacks.radioConnected();
    }

    private void startProtocolHandshake() {
        int handshakeId = ++handshakeSeq;
        activeHandshakeId = handshakeId;
        waitingForHello = true;
        callbacks.radioModuleHandshake();
        Log.i(TAG, handshakeLog(handshakeId, "start(): waiting for HELLO(version)"));
        scheduleHelloTimeout(handshakeId);
    }

    private void scheduleHelloTimeout(int handshakeId) {
        cancelHelloTimeout();
        helloTimeoutRunnable = () -> {
            if (!waitingForHello || activeHandshakeId != handshakeId) {
                return;
            }
            waitingForHello = false;
            Log.w(TAG, handshakeLog(handshakeId, "waitForHello(): timed out after " + HELLO_TIMEOUT_MS + "ms"));
            setMode(RadioMode.BAD_FIRMWARE);
            callbacks.missingFirmware();
            connectionController.markAttemptFinished();
        };
        handler.postDelayed(helloTimeoutRunnable, HELLO_TIMEOUT_MS);
    }

    private void cancelHelloTimeout() {
        if (helloTimeoutRunnable != null) {
            handler.removeCallbacks(helloTimeoutRunnable);
            helloTimeoutRunnable = null;
        }
    }

    private void handleHelloReceived(Optional<Protocol.Hello> hello) {
        int handshakeId;
        if (waitingForHello) {
            waitingForHello = false;
            handshakeId = activeHandshakeId;
            cancelHelloTimeout();
            Log.d(TAG, handshakeLog(handshakeId, "HELLO received: " + hello));
        } else {
            handshakeId = ++handshakeSeq;
            activeHandshakeId = handshakeId;
            Log.i(TAG, handshakeLog(handshakeId, "HELLO received outside active wait; validating firmware state"));
        }
        validateHello(handshakeId, hello);
    }

    private void validateHello(int handshakeId, Optional<Protocol.Hello> hello) {
        if (!hello.isPresent()) {
            Log.e(TAG, handshakeLog(handshakeId, "HELLO missing valid Hello payload; firmware upgrade required"));
            callbacks.outdatedFirmware(0);
            setMode(RadioMode.BAD_FIRMWARE);
            connectionController.markAttemptFinished();
            return;
        }

        Protocol.Hello helloPayload = hello.get();
        Protocol.FirmwareVersion version = helloPayload.getVersion();
        Log.d(TAG, handshakeLog(handshakeId, "hello=" + helloPayload));
        if (version.getVer() < FirmwareUtils.PACKAGED_FIRMWARE_VER) {
            callbacks.outdatedFirmware(version.getVer());
            setMode(RadioMode.BAD_FIRMWARE);
            connectionController.markAttemptFinished();
            return;
        }

        handleHello(helloPayload);
        if (Protocol.RadioStatus.RADIO_STATUS_NOT_FOUND.equals(version.getRadioModuleStatus())) {
            Log.w(TAG, handshakeLog(handshakeId, "radio module not found"));
            setMode(RadioMode.BAD_FIRMWARE);
            callbacks.radioModuleNotFound();
            connectionController.markAttemptFinished();
            return;
        }

        getHostToEsp32().setFlowControlWindow(version.getWindowSize());
        handleInitialDeviceState(helloPayload.getDeviceState());
        markRadioTransportReady();
        Log.i(TAG, handshakeLog(handshakeId, "HELLO version OK; proceeding with radio communication"));
        setMode(RadioMode.RX);
        openFirmwareAudio();
        // Turn off scanning if it was on (e.g. if radio was unplugged briefly and reconnected)
        setScanning(false);
        radioConnected();
    }

    private String handshakeLog(int handshakeId, String message) {
        return "handshake#" + handshakeId
            + "/connect#" + getActiveUsbConnectAttemptId()
            + " " + threadTag()
            + " " + message;
    }

    // Called in many situations where radio connection is found to be broken
    private void radioMissing() {
        Log.i(TAG, connectLog("radioMissing(): state=" + connectionStateSummary()));
        connectionController.markAttemptFinished();
        closePortAndReset();
        if (!radioMissingNotified) {
            radioMissingNotified = true;
            callbacks.radioMissing(); // Notify UI only on transition into missing state
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release(); // Don't keep screen on
        }
    }

    int getActiveUsbConnectAttemptId() {
        return activeUsbConnectAttemptId;
    }

    private String connectLog(String message) {
        return "connect#" + activeUsbConnectAttemptId + " " + threadTag() + " " + message;
    }

    private String connectionStateSummary() {
        return "mode=" + mode
            + ",hostToEsp32=" + (hostToEsp32 != null)
            + ",serialPort=" + (serialPort != null)
            + ",usbIoManager=" + (usbIoManager != null)
            + ",usbPermissionPending=" + usbPermissionRequestPending
            + ",radioMissingNotified=" + radioMissingNotified;
    }

    static String threadTag() {
        Thread thread = Thread.currentThread();
        return "thread=" + thread.getName() + "#" + thread.getId();
    }

    public RadioModuleType getRadioType() {
        Protocol.RfModuleType rfModuleType = radioModule.getRfModuleType();
        if (Protocol.RfModuleType.RF_SA818_UHF.equals(rfModuleType)) {
            return RadioModuleType.UHF;
        }
        if (Protocol.RfModuleType.RF_SA818_VHF.equals(rfModuleType)) {
            return RadioModuleType.VHF;
        }
        return RadioModuleType.UNKNOWN;
    }

    private void clearRadioTypeAndLimits() {
        radioModule.clearFirmwareVersion();
        callbacks.setRadioType(RadioModuleType.UNKNOWN);
    }

    public void handleHello(Protocol.Hello hello) {
        radioModule.seedFirmwareVersion(hello.getVersion());
        callbacks.setRadioType(getRadioType());
        updateTxLimitsForBand();
    }

    public void updateTxLimitsForBand() {
        if (RadioModuleType.VHF.equals(getRadioType())) {
            setMinTxFreq(min2mTxFreq);
            setMaxTxFreq(max2mTxFreq);
        } else if (RadioModuleType.UHF.equals(getRadioType())) {
            setMinTxFreq(min70cmTxFreq);
            setMaxTxFreq(max70cmTxFreq);
        }
        Log.d(TAG, "Radio type set to: " + getRadioType());
        Log.d(TAG, "Min radio freq: " + getMinRadioFreq());
        Log.d(TAG, "Max radio freq: " + getMaxRadioFreq());
        Log.d(TAG, "Min tx freq: " + minTxFreq);
        Log.d(TAG, "Max tx freq: " + maxTxFreq);
        float txFrequency = radioModule.getTxFrequency() > 0 ? radioModule.getTxFrequency() : parseActiveFrequencyOrZero();
        updateTxAllowed(txFrequency);
        Log.d(TAG, String.format("Tx allowed: %b (%s)", isTxAllowed(), txFrequency));
    }

    private float parseActiveFrequencyOrZero() {
        try {
            return Float.parseFloat(activeFrequencyStr);
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    public boolean isHasHighLowPowerSwitch() {
        return radioModule.hasHighLowPowerSwitch();
    }

    public boolean isHasPhysPttButton() {
        return radioModule.hasPhysPttButton();
    }

    public float getMinRadioFreq() {
        return radioModule.getMinRadioFreq();
    }

    public float getMaxRadioFreq() {
        return radioModule.getMaxRadioFreq();
    }

    public void setScanning(boolean scanning, boolean goToRxMode) {
        if (!scanning && mode != RadioMode.SCAN) {
            return;
        }
        if (!scanning) {
            cancelPendingScanAdvance();
            if (scanBaseSquelch >= 0 && scanBaseSquelch != radioModule.getDesiredSquelch()) {
                radioModule.beginUpdate();
                try {
                    radioModule.setSquelch((byte) scanBaseSquelch);
                    if (activeMemoryId > -1) {
                        tuneToMemory(activeMemoryId);
                    } else {
                        tuneToFreq(activeFrequencyStr);
                    }
                } finally {
                    radioModule.endUpdate();
                }
            }
            scanBaseSquelch = -1;
            if (goToRxMode) {
                setMode(RadioMode.RX);
            }
        } else { // Start scanning
            scanBaseSquelch = radioModule.getDesiredSquelch();
            setMode(RadioMode.SCAN);
            nextScan();
        }
    }

    public void setScanning(boolean scanning) {
        setScanning(scanning, true);
    }

    public void nextScan() {
        cancelPendingScanAdvance();
        // Only proceed if actually in SCAN mode.
        if (getMode() != RadioMode.SCAN) {
            return;
        }
        // Make sure channelMemoriesLiveData is set and has items.
        if (channelMemoriesLiveData == null) {
            Log.d(TAG, "Error: attempted nextScan() but channelMemories was never set.");
            return;
        }
        List<ChannelMemory> channelMemories = channelMemoriesLiveData.getValue();
        if (channelMemories == null || channelMemories.isEmpty()) {
            return;
        }
        // Find the index of our current active memory in the list,
        // or -1 if we didn't find it (e.g. simplex mode).
        int currentIndex = -1;
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == activeMemoryId) {
                currentIndex = i;
                break;
            }
        }
        // If we’re in simplex (activeMemoryId == -1), treat it as if
        // the "current index" is -1 so the next index starts at 0.
        int nextIndex = (currentIndex + 1) % channelMemories.size();
        int firstTriedIndex = nextIndex;  // So we know when we've looped around.
        do {
            ChannelMemory candidate = channelMemories.get(nextIndex);
            // If not marked as skipped, and it's in the active band, we tune to it and return.
            float memoryFreqFloat = 0.0f;
            try {
                memoryFreqFloat = Float.parseFloat(candidate.frequency);
            } catch (Exception e) {
                Log.d(TAG, "Memory with id " + candidate.memoryId + " had invalid frequency.");
            }
            if (!candidate.skipDuringScan && memoryFreqFloat >= getMinRadioFreq() && memoryFreqFloat <= getMaxRadioFreq()) {
                // If squelch is off (0), use squelch=1 during scanning.
                int desiredSquelch = scanBaseSquelch >= 0 ? scanBaseSquelch : radioModule.getDesiredSquelch();
                radioModule.beginUpdate();
                try {
                    radioModule.setSquelch((byte) (desiredSquelch > 0 ? desiredSquelch : 1));
                    tuneToMemory(candidate);
                } finally {
                    radioModule.endUpdate();
                }
                callbacks.scannedToMemory(candidate.memoryId);
                return;
            }
            // Otherwise, move on to the next memory in the list.
            nextIndex = (nextIndex + 1) % channelMemories.size();
            // Repeat until we loop back to the first tried index.
        } while (nextIndex != firstTriedIndex);
        // If we reach here, all memories are marked skipDuringScan.
        Log.d(TAG, "Warning: All memories are skipDuringScan, no next memory found to scan to.");
    }

    private float[] applyMicGain(float[] audioBuffer) {
        if (micGainBoost == MicGainBoost.NONE) {
            return audioBuffer; // No gain, just return original
        }
        float[] newAudioBuffer = new float[audioBuffer.length];
        for (int i = 0; i < audioBuffer.length; i++) {
            newAudioBuffer[i] = audioBuffer[i] * micGainBoost.getGain();
        }
        return newAudioBuffer;
    }

    public void sendAudioToESP32(float[] samples, boolean dataMode) {
        if (hostToEsp32 == null) {
            return; // If connection is lost, just drop the audio frame.
        }
        if (!dataMode) {
            samples = applyMicGain(samples);
        }
        int encodedLength = opusEncoder.encode(samples, txAudioFrame);
        hostToEsp32.txAudio(txAudioFrame, encodedLength);
    }

    public boolean isRadioConnected() {
        return isConnectionReady() && mode != RadioMode.STARTUP;
    }

    /**
     * Handles the parsed command received from the ESP32.
     * It processes various commands such as S-meter reports, PTT control,
     * debug messages, HELLO command, RX audio, version information, and window updates.
     *
     * @param cmd   The command received from the ESP32.
     * @param param The parameters associated with the command.
     * @param len   The length of the parameters.
     */
    @SuppressWarnings({"java:S6541"})
    private void handleParsedCommand(final RcvCommand cmd, final ByteBuffer param, final int offset, final int len) {
        switch (cmd) {
            case COMMAND_DEBUG_INFO:
                Log.i(FIRMWARE_TAG, firmwareString(param, offset, len));
                break;

            case COMMAND_DEBUG_DEBUG:
                Log.d(FIRMWARE_TAG, firmwareString(param, offset, len));
                break;

            case COMMAND_DEBUG_ERROR:
                Log.e(FIRMWARE_TAG, firmwareString(param, offset, len));
                break;

            case COMMAND_DEBUG_WARN:
                Log.w(FIRMWARE_TAG, firmwareString(param, offset, len));
                break;

            case COMMAND_DEBUG_TRACE:
                Log.v(FIRMWARE_TAG, firmwareString(param, offset, len));
                break;

            case COMMAND_HELLO:
                handleHelloReceived(Protocol.Hello.from(param, offset, len));
                break;

            case COMMAND_RX_AUDIO:
                handleRxAudio(param, offset, len);
                break;

            case COMMAND_WINDOW_UPDATE:
                WindowUpdate.from(param, offset, len).ifPresent(windowAck ->
                    hostToEsp32.enlargeFlowControlWindow(windowAck.getSize()));
                break;

            case COMMAND_DEVICE_STATE:
                Protocol.DeviceState.from(param, offset, len).ifPresent(this::handleDeviceState);
                break;

            default:
                break;
        }
    }

    private String firmwareString(ByteBuffer param, int offset, int len) {
        if (param == null || !param.hasArray() || offset < 0 || len <= 0 || param.limit() < offset + len) {
            return "";
        }
        return new String(param.array(), offset, len, StandardCharsets.UTF_8);
    }

    private void handleEsp32Ax25Packet(final ByteBuffer param, final int offset, final int len) {
        if (param == null || !param.hasArray() || len < 1 || offset < 0 || param.limit() < offset + len) {
            return;
        }
        handleAx25Packet(param.array(), offset, len);
    }

    void handleInitialDeviceState(Protocol.DeviceState state) {
        radioModule.seedFromDeviceState(state);
        if (state.hasRadioConfig()) {
            activeFrequencyStr = formatFreq(state.getFreqRx());
            activeMemoryId = state.getMemoryId();
        }
        handleDeviceState(state);
        callbacks.initialDeviceStateReceived();
    }

    void markRadioTransportReady() {
        radioModule.markTransportReady();
    }

    private void handleDeviceState(Protocol.DeviceState state) {
        radioModule.updateDeviceState(state);
        callbacks.moduleTxStateChanged(radioModule.isDeviceTxActive());
        if (radioModule.isAppliedStateInSync() && radioModule.getTxFrequency() > 0) {
            updateTxAllowed(radioModule.getTxFrequency());
        }
        if (getMode() == RadioMode.RX || getMode() == RadioMode.SCAN) {
            callbacks.sMeterUpdate(radioModule.getSMeter9Value());
        }
        checkScanDueToSquelch();
        if (radioModule.didPhysPttChange()) {
            boolean physPttDown = radioModule.isPhysPttDown();
            if (physPttDown) {
                if (getMode() == RadioMode.RX && isTxAllowed()) {
                    startPtt();
                    callbacks.forcedPttStart();
                }
            } else if (getMode() == RadioMode.TX) {
                endPtt();
                callbacks.forcedPttEnd();
            }
        }
    }

    /**
     * Handles incoming audio data from the ESP32, decoding it and playing it through the AudioTrack.
     * If in RX or SCAN mode, it processes the audio samples and manages the AFSK demodulator.
     * In SCAN mode, firmware squelch state determines whether scanning should advance.
     *
     * @param param The byte array containing the audio data.
     * @param len   The length of the audio data in bytes.
     */
    private void handleRxAudio(final ByteBuffer param, final int offset, final int len) {
        if (param == null || !param.hasArray() || offset < 0 || len <= 0 || param.limit() < offset + len) {
            return;
        }
        int decoded = opusDecoder.decode(param.array(), offset, len, pcmFloat);

        if ((getMode() == RadioMode.RX || getMode() == RadioMode.SCAN) && audioTrack != null) {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioTrack.write(pcmFloat, 0, decoded, AudioTrack.WRITE_NON_BLOCKING);
            audioManager.requestAudioFocus(audioFocusRequest);
            ensureAudioPlaying();
        }
    }

    /**
     * Ensures that the AudioTrack is playing and gradually adjusts its volume.
     * The volume is increased smoothly based on a factor of alpha, and the volume is capped at 0.7f.
     * If the calculated volume is below 0.7f, the volume is set to 0.0f.
     * This method is intended to apply smooth volume adjustments to the AudioTrack.
     * <p>
     * The method first checks if the AudioTrack is playing. If it is not playing, the method starts the playback.
     * Then, the volume is adjusted by applying a smoothing factor using a simple exponential-like formula.
     * If the volume exceeds 0.7f, it will be set to the calculated value; otherwise, the volume will be set to 0.0f.
     * </p>
     *
     * @see AudioTrack
     * @see AudioTrack#setVolume(float)
     */
    private void ensureAudioPlaying() {
        if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            audioTrackVolume = 0;
            audioTrack.setVolume(0.0f);
            audioTrack.play();
        }
        float alpha = 0.05f;
        audioTrackVolume = alpha + (1.0f - alpha) * audioTrackVolume;
        if (audioTrackVolume > 0.7f) {
            audioTrack.setVolume(audioTrackVolume);
        }
        else {
            audioTrack.setVolume(0.0f);
        }
    }

    private void handleAx25Packet(byte[] packet, int offset, int len) {
        try {
            APRSPacket aprsPacket = Parser.parseAX25(packet, offset, len);
            InformationField info = aprsPacket.getPayload();
            // Check if the packet is an APRS message type
            if (info.getDataTypeIdentifier() == ':') {
                MessagePacket msg = new MessagePacket(info.getRawBytes(), aprsPacket.getDestinationCall());
                String target = msg.getTargetCallsign().trim().toUpperCase();
                // Handle messages addressed to the current callsign
                if (!msg.isAck() && target.equals(callsign.toUpperCase())) {
                    callbacks.showNotification(
                        MESSAGE_NOTIFICATION_CHANNEL_ID,
                        MESSAGE_NOTIFICATION_TO_YOU_ID,
                        aprsPacket.getSourceCall() + " messaged you",
                        msg.getMessageBody(),
                        INTENT_OPEN_CHAT);
                    // Send acknowledgment after a delay
                    handler.postDelayed(() -> sendAckMessage(aprsPacket.getSourceCall().toUpperCase(), msg.getMessageNumber()), 1000);
                }
            }
            // Notify callbacks about the received packet
            callbacks.packetReceived(aprsPacket);
        } catch (Exception e) {
            Log.d(TAG, "Unable to parse an APRS packet, skipping.");
        }
    }

    /**
     * Sends a position beacon via APRS.
     * This method can only be called when the radio is in RX mode.
     * If Google Play Services are not available, it will call unknownLocation() on the callbacks.
     */
    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    public void sendPositionBeacon() {
        boolean isScanning = getMode() == RadioMode.SCAN;
        boolean isRx = getMode() == RadioMode.RX;
        boolean isCurrent = "Current".equals(aprsBeaconFrequency);

        if (!isRadioConnected() || !isTxAllowed()) {
            Log.d(TAG, "Skipping position beacon: radio disconnected or tx not allowed.");
            return;
        }

        if (isScanning && isCurrent) {
            Log.d(TAG, "Skipping position beacon: scanning and set to 'Current' frequency.");
            return;
        }

        if (!isRx && !isScanning) {
            Log.d(TAG, "Skipping position beacon: not in RX or SCAN mode.");
            return;
        }

        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(getBaseContext()) != ConnectionResult.SUCCESS) {
            Log.d(TAG, "Can't get GPS position: missing Google Play Services.");
            callbacks.unknownLocation();
            return;
        }
        FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(this);
        CancellationToken token = new CancellationTokenSource().getToken();
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token)
            .addOnSuccessListener(location -> {
                if (location != null) {
                    performPositionBeacon(location.getLatitude(), location.getLongitude());
                } else {
                    callbacks.unknownLocation();
                }
            }).addOnFailureListener(e -> callbacks.unknownLocation());
    }

    private void performPositionBeacon(final double latitude, final double longitude) {
        if ("Current".equals(aprsBeaconFrequency)) {
            callbacks.startingAprsBeacon(activeFrequencyStr);
            sendPositionBeacon(latitude, longitude, false);
            return;
        }

        // Frequency switch logic
        final boolean wasScanning = getMode() == RadioMode.SCAN;
        final int originalMemoryId = activeMemoryId;
        final String originalFrequencyStr = activeFrequencyStr;

        callbacks.startingAprsBeacon(aprsBeaconFrequency);

        if (wasScanning) {
            setScanning(false, false);
        }

        tuneToFreq(aprsBeaconFrequency);

        // Give it a moment to tune and stabilize
        handler.postDelayed(() -> {
            sendPositionBeacon(latitude, longitude, true);

            // Wait for transmission to finish before restoring
            handler.postDelayed(() -> {
                if (wasScanning) {
                    setScanning(true);
                } else {
                    if (originalMemoryId != -1) {
                        tuneToMemory(originalMemoryId);
                    } else {
                        tuneToFreq(originalFrequencyStr);
                    }
                }
            }, 3000); // 3 seconds for TX
        }, 500); // 500ms for tuning
    }

    /**
     * Sends a position beacon via APRS.
     * This method can only be called when the radio is in RX mode.
     *
     * @param latitude  The latitude to beacon.
     * @param longitude The longitude to beacon.
     * @param wasSwitch True if we switched frequencies for this beacon.
     */
    private void sendPositionBeacon(final double latitude, final double longitude, final boolean wasSwitch) {
        if (getMode() != RadioMode.RX) {
            Log.d(TAG, "Skipping position beacon because not in RX mode");
            return;
        }
        Log.i(TAG, "Beaconing position via APRS");
        final boolean isApprox = aprsPositionAccuracy == APRS_POSITION_APPROX;
        final Position myPos = new Position(
            isApprox ? Math.round(latitude * 100.0) / 100.0 : latitude,
            isApprox ? Math.round(longitude * 100.0) / 100.0 : longitude
        );
        try {
            final PositionField posField = new PositionField(("=" + myPos.toCompressedString()).getBytes(), "", 1);
            final APRSPacket aprsPacket = new APRSPacket(callsign, "BEACON", DEFAULT_DIGIPEATERS, posField.getRawBytes());
            aprsPacket.getPayload().addAprsData(APRSTypes.T_POSITION, posField);
            txAX25Packet(new Packet(aprsPacket.toAX25Frame()));
            callbacks.sentAprsBeacon(myPos.getLatitude(), myPos.getLongitude(), activeFrequencyStr, wasSwitch);
        } catch (Exception e) {
            Log.w(TAG, "Exception while trying to beacon APRS location.", e);
        }
    }

    /**
     * Sends an acknowledgment message to the specified target callsign.
     *
     * @param to    The callsign of the recipient.
     * @param remoteMessageNum  The message number to acknowledge.
     */
    public void sendAckMessage(String to, String remoteMessageNum) {
        MessagePacket msgPacket = new MessagePacket(to, "ack" + remoteMessageNum, remoteMessageNum);
        APRSPacket aprsPacket = new APRSPacket(callsign, to, DEFAULT_DIGIPEATERS, msgPacket.getRawBytes());
        txAX25Packet(new Packet(aprsPacket.toAX25Frame()));
    }

    /**
     * Sends a chat message to the specified recipient.
     *
     * @param to   The callsign of the recipient, or null for CQ.
     * @param text The message text to send.
     * @return The message number if sent successfully, -1 on error.
     */
    public int sendChatMessage(String to, String text) {
        // Sanitize message text
        final String outText = text.replace('|', ' ').replace('~', ' ').replace('{', ' ');
        final String targetCallsign = (to == null || to.trim().isEmpty()) ? "CQ" : to;
        if (callsign.trim().isEmpty()) {
            Log.d(TAG, "Error: Tried to send message with no sender callsign.");
            return -1;
        }
        // Create message and digipeater path
        MessagePacket msgPacket = new MessagePacket(targetCallsign, outText, String.valueOf(messageNumber++));
        if (messageNumber > APRS_MAX_MESSAGE_NUM) {
            messageNumber = 0;
        }
        try {
            APRSPacket aprsPacket = new APRSPacket(callsign, targetCallsign, DEFAULT_DIGIPEATERS, msgPacket.getRawBytes());
            Packet ax25Packet = new Packet(aprsPacket.toAX25Frame());
            txAX25Packet(ax25Packet);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error: sending APRS packet", e);
            callbacks.chatError(e.getMessage());
            return -1;
        }
        return messageNumber - 1;
    }

    /**
     * Sends an AX.25 packet to the ESP32 for transmission.
     * Firmware handles AFSK modulation so the Android app does not need to stream packet audio.
     *
     * @param ax25Packet The AX.25 packet to send.
     */
    private void txAX25Packet(Packet ax25Packet) {
        if (!isTxAllowed()) {
            Log.e(TAG, "Tried to send an AX.25 packet when tx is not allowed, did not send.");
            return;
        }
        if (getMode() != RadioMode.RX) {
            Log.e(TAG, "Tried to send an AX.25 packet when radio was not in RX mode, did not send.");
            return;
        }
        if (hostToEsp32 == null) {
            Log.e(TAG, "Tried to send AX.25 packet with no ESP32 connection.");
            return;
        }
        Log.d(TAG, "Sending AX25 packet: " + ax25Packet);
        hostToEsp32.txAx25(ax25Packet.bytesWithoutCRC());
        Log.i(TAG, "Send AX25 packet: " + ax25Packet);
    }

    public int getAudioTrackSessionId() {
        return Optional.ofNullable(audioTrack).map(AudioTrack::getAudioSessionId).orElse(-1);
    }

    /**
     * Sets whether radio module should poll RSSI. We need to be able to turn this off
     * because in v1.x versions of the PCB there's cross-talk between the Serial2 trace and
     * the audio trace, which breaks APRS decoding (and any other digital mode we might add).
     * See <a href="https://github.com/VanceVagell/kv4p-ht/issues/310">...</a> for context.
     * <p>
     * This will send the new state to the ESP32 if it is connected.
     *
     * @param on true to enable RSSI, false to disable it.
     */
    public void setRssi(boolean on) {
        radioModule.setRssi(on);
    }

    public boolean isRssiOn() {
        return radioModule.isRssiEnabled();
    }

}
