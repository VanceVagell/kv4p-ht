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

import static com.vagell.kv4pht.radio.Protocol.DRA818_12K5;
import static com.vagell.kv4pht.radio.Protocol.DRA818_25K;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.lifecycle.LiveData;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import lombok.Getter;
import lombok.Setter;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.APRSTypes;
import com.vagell.kv4pht.aprs.parser.Digipeater;
import com.vagell.kv4pht.aprs.parser.InformationField;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.Parser;
import com.vagell.kv4pht.aprs.parser.Position;
import com.vagell.kv4pht.aprs.parser.PositionField;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.javAX25.ax25.Afsk1200Modulator;
import com.vagell.kv4pht.javAX25.ax25.Afsk1200MultiDemodulator;
import com.vagell.kv4pht.javAX25.ax25.Arrays;
import com.vagell.kv4pht.javAX25.ax25.Packet;
import com.vagell.kv4pht.javAX25.ax25.PacketDemodulator;
import com.vagell.kv4pht.javAX25.ax25.PacketHandler;
import com.vagell.kv4pht.javAX25.ax25.PacketModulator;
import com.vagell.kv4pht.radio.Protocol.Filters;
import com.vagell.kv4pht.radio.Protocol.FrameParser;
import com.vagell.kv4pht.radio.Protocol.Group;
import com.vagell.kv4pht.radio.Protocol.HlState;
import com.vagell.kv4pht.radio.Protocol.RcvCommand;
import com.vagell.kv4pht.radio.Protocol.WindowUpdate;
import com.vagell.kv4pht.ui.ToneHelper;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Background service that manages the connection to the ESP32 (to control the radio), and
 * handles playing back any audio received from the radio. This frees up the rest of the
 * application to focus primarily on the setup flows and UI, and ensures that the radio audio
 * continues to play even if the phone's screen is off or the user starts another app.
 */
public class RadioAudioService extends Service implements PacketHandler {

    // === Constants ===
    private static final String TAG = RadioAudioService.class.getSimpleName();
    private static final String FIRMWARE_TAG = "firmware";
    private static final int RUNAWAY_TX_TIMEOUT_SEC = 180;
    // Intents this Activity can handle besides the one that starts it in default mode.
    public static final String INTENT_OPEN_CHAT = "com.vagell.kv4pht.OPEN_CHAT_ACTION";

    // === USB Device Matching ===
    private static final int[] ESP32_VENDOR_IDS = {4292, 6790};
    private static final int[] ESP32_PRODUCT_IDS = {60000, 29987};

    // === Audio Constants ===
    public static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int RX_AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int RX_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    public static final int OPUS_FRAME_SIZE = 1920; // 40ms at 48kHz
    private static final int RX_AUDIO_MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, RX_AUDIO_CHANNEL_CONFIG, RX_AUDIO_FORMAT) * 2;

    // === APRS Constants ===
    public static final int APRS_POSITION_EXACT = 0;
    public static final int APRS_POSITION_APPROX = 1;
    public static final int APRS_BEACON_MINS = 5;
    private static final int APRS_MAX_MESSAGE_NUM = 99999;
    private static final int MS_SILENCE_BEFORE_DATA_MS = 1100;
    private static final int MS_SILENCE_AFTER_DATA_MS = 700;
    private static final String MESSAGE_NOTIFICATION_CHANNEL_ID = "aprs_message_notifications";
    private static final int MESSAGE_NOTIFICATION_TO_YOU_ID = 0;
    public static final List<Digipeater> DEFAULT_DIGIPEATERS = List.of(new Digipeater("WIDE1*"), new Digipeater("WIDE2-1"));

    // === Frequency Ranges ===
    private static final float VHF_MIN_FREQ = 134.0f;
    private static final float VHF_MAX_FREQ = 174.0f;
    private static final float UHF_MIN_FREQ = 400.0f;
    private static final float UHF_MAX_FREQ = 480.0f;

    // These will be overwritten by user settings
    @Setter
    private float min2mTxFreq = 144.0f;
    @Setter
    private float max2mTxFreq = 148.0f;
    @Setter
    private float min70cmTxFreq = 420.0f;
    @Setter
    private float max70cmTxFreq = 450.0f;

    @Getter
    private float minRadioFreq = VHF_MIN_FREQ;
    @Getter
    private float maxRadioFreq = VHF_MAX_FREQ;
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

    // === USB / Serial ===
    private UsbManager usbManager;
    @Getter
    private UsbSerialPort serialPort;
    private SerialInputOutputManager usbIoManager;
    @Getter
    private Protocol.Sender hostToEsp32;
    private final FrameParser esp32DataStreamParser = new FrameParser(this::handleParsedCommand);

    // === AFSK Modem ===
    private final PacketModulator afskModulator = new Afsk1200Modulator(AUDIO_SAMPLE_RATE);
    private final PacketDemodulator afskDemodulator = new Afsk1200MultiDemodulator(AUDIO_SAMPLE_RATE, this);

    // === APRS State ===
    private boolean aprsBeaconPosition = false;
    @Getter
    @Setter
    private int aprsPositionAccuracy = APRS_POSITION_EXACT;
    private final ScheduledExecutorService aprsPositionExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> beaconFuture;
    private int messageNumber = 0;

    // === Radio State ===
    @Getter
    private @NonNull RadioMode mode = RadioMode.STARTUP;
    @Getter
    @Setter
    private boolean hasHighLowPowerSwitch = false;
    @Getter
    @Setter
    private boolean hasPhysPttButton = false;
    @Getter
    private boolean isHighPower = true;
    @Getter
    private boolean isRssiOn = true;
    @Getter
    private boolean txAllowed = true;
    @Setter
    private int squelch = 0;
    @Setter
    private @NonNull String callsign = "";
    @Getter
    private @NonNull String activeFrequencyStr = "";
    @Getter
    private RadioModuleType radioType = RadioModuleType.UNKNOWN;
    private int activeMemoryId = -1;
    private int consecutiveSilenceBytes = 0;
    private MicGainBoost micGainBoost = MicGainBoost.NONE;
    @Setter
    private @NonNull String bandwidth = "Wide";

    // === Android Components ===
    private final IBinder binder = new RadioBinder();
    private static final RadioAudioServiceCallbacks NO_OP_CALLBACKS = new RadioAudioServiceCallbacks() {};
    @Setter
    @Getter
    private @NonNull RadioAudioServiceCallbacks callbacks = NO_OP_CALLBACKS;
    private final ProtocolHandshake protocolHandshake = new ProtocolHandshake(this);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable txTimeoutHandler;
    private LiveData<List<ChannelMemory>> channelMemoriesLiveData = null;

    // === Scan Timing ===
    private static final float SEC_BETWEEN_SCANS = 0.5f;

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
        default void scannedToMemory(int memoryId) {}
        default void outdatedFirmware(int firmwareVer) {}
        default void missingFirmware() {}
        default void txStarted() {}
        default void txEnded() {}
        default void chatError(String text) {}
        default void sMeterUpdate(int value) {}
        default void aprsBeaconing(boolean beaconing, int accuracy) {}
        default void sentAprsBeacon(double latitude, double longitude) {}
        default void unknownLocation() {}
        default void forceTunedToFreq(String newFreqStr) {}
        default void forcedPttStart() {}
        default void forcedPttEnd() {}
        default void setRadioType(RadioModuleType ratioType) {}
        default void showNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Retrieve necessary parameters from the intent.
        Bundle bundle = intent.getExtras();
        callsign = bundle.getString("callsign");
        squelch = bundle.getInt("squelch");
        activeMemoryId = bundle.getInt("activeMemoryId");
        activeFrequencyStr = bundle.getString("activeFrequencyStr");
        return binder;
    }

    public void setFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        setRadioFilters(emphasis, highpass, lowpass);
    }

    public void setMicGainBoost(String micGainBoost) {
        this.micGainBoost = MicGainBoost.parse(micGainBoost);
    }

    public void setMinRadioFreq(float newMinFreq) {
        minRadioFreq = newMinFreq;
        // Detect if we're moving from VHF to UHF, and move active frequency to within band.
        if (mode != RadioMode.STARTUP && Float.parseFloat(activeFrequencyStr) < minRadioFreq) {
            tuneToFreq(String.format(java.util.Locale.US, "%.4f", min70cmTxFreq), squelch, true);
            callbacks.forceTunedToFreq(activeFrequencyStr);
        }
    }

    public void setMaxRadioFreq(float newMaxFreq) {
        maxRadioFreq = newMaxFreq;
        // Detect if we're moving from UHF to VHF, and move active frequency to within band.
        if (mode != RadioMode.STARTUP && Float.parseFloat(activeFrequencyStr) > maxRadioFreq) {
            tuneToFreq(String.format(java.util.Locale.US, "%.4f", min2mTxFreq), squelch, true);
            callbacks.forceTunedToFreq(activeFrequencyStr);
        }
    }

    public void setAprsBeaconPosition(boolean enabled) {
        if (this.aprsBeaconPosition != enabled) {
            this.aprsBeaconPosition = enabled;
            if (enabled) {
                beaconFuture = aprsPositionExecutor.scheduleWithFixedDelay(this::sendPositionBeacon,
                    0, APRS_BEACON_MINS, TimeUnit.MINUTES);
                // Tell callback we started (e.g. so it can show a SnackBar letting user know)
                callbacks.aprsBeaconing(true, aprsPositionAccuracy);
            } else if (beaconFuture != null) {
                beaconFuture.cancel(true);
                beaconFuture = null;
            }
        }
    }

    public void setMode(RadioMode mode) {
        if (mode == RadioMode.FLASHING) {
            hostToEsp32.stop();
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
        this.mode = mode;
    }

    public void setActiveMemoryId(int activeMemoryId) {
        this.activeMemoryId = activeMemoryId;
        if (activeMemoryId > -1) {
            tuneToMemory(activeMemoryId, squelch, false);
        } else {
            tuneToFreq(activeFrequencyStr, squelch, false);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
        handler.postDelayed(this::findESP32Device, 10);
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
        protocolHandshake.onDestroy();
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
    }

    private void createNotificationChannels() {
        // Notification channel for APRS text chat messages
        NotificationChannel channel = new NotificationChannel(MESSAGE_NOTIFICATION_CHANNEL_ID,
            "Chat messages", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("APRS text chat messages addressed to you");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private void setRadioFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        hostToEsp32.filters(Filters.builder()
            .high(highpass)
            .low(lowpass)
            .pre(emphasis)
            .build());
    }

    /**
     * Determines if transmission (TX) is allowed on the given frequency.
     *
     * @param freq The frequency to check, in MHz.
     * @return true if the frequency is within the allowed transmission range, false otherwise.
     */
    private boolean isTxAllowed(float freq) {
        final float halfBandwidth = (bandwidth.equals("Wide") ? 0.025f : 0.0125f) / 2;
        return  (freq >= (minTxFreq + halfBandwidth)) && (freq <= (maxTxFreq - halfBandwidth));
    }

    // Tell microcontroller to tune to the given frequency string, which must already be formatted
    // in the style the radio module expects.
    public void tuneToFreq(String frequencyStr, int squelchLevel, boolean forceTune) {
        if (mode == RadioMode.STARTUP) {
            return; // Not fully loaded and initialized yet, don't tune.
        }
        setMode(RadioMode.RX);
        if (!forceTune && activeFrequencyStr.equals(frequencyStr) && squelch == squelchLevel) {
            return; // Already tuned to this frequency with this squelch level.
        }
        float freq;
        try {
            freq = Float.parseFloat(makeSafeHamFreq(frequencyStr));
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid frequency string: " + frequencyStr, e);
            return;
        }
        activeFrequencyStr = frequencyStr;
        activeMemoryId = -1; // Reset active memory ID since we're tuning to a frequency, not a memory.
        squelch = squelchLevel;
        if (isRadioConnected()) {
            hostToEsp32.group(Group.builder()
                .freqTx(freq)
                .freqRx(freq)
                .bw((bandwidth.equals("Wide") ? DRA818_25K : DRA818_12K5))
                .squelch((byte) squelchLevel)
                .build());
        }
        txAllowed = isTxAllowed(freq);
    }

    public String makeSafeHamFreq(String strFreq) {
        try {
            float freq = Float.parseFloat(strFreq);
            // Normalize values like "1467" → "146.7"
            while (freq > 500.0f) {
                freq /= 10;
            }
            return formatFreq(Math.max(minRadioFreq, Math.min(freq, maxRadioFreq)));
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

    public void tuneToMemory(int memoryId, int squelchLevel, boolean forceTune) {
        if (forceTune || activeMemoryId != memoryId || squelch != squelchLevel) {
            Optional.ofNullable(channelMemoriesLiveData)
                .map(LiveData::getValue)
                .orElse(Collections.emptyList())
                .stream()
                .filter(channelMemory -> channelMemory.memoryId == memoryId)
                .findFirst()
                .ifPresent(channelMemory -> tuneToMemory(channelMemory, squelchLevel, forceTune));
        }
    }

    public void tuneToMemory(ChannelMemory memory, int squelchLevel, boolean forceTune) {
        if (memory == null || (!forceTune && activeMemoryId == memory.memoryId && squelch == squelchLevel) || mode == RadioMode.STARTUP) {
            return; // Skip if memory is null, already tuned, or in STARTUP mode.
        }
        activeFrequencyStr = validateFrequency(memory.frequency);
        activeMemoryId = memory.memoryId;
        final float txFreq = Float.parseFloat(getTxFreq(memory.frequency, memory.offset, memory.offsetKhz));
        if (isRadioConnected()) {
            hostToEsp32.group(Group.builder()
                .freqTx(txFreq)
                .freqRx(Float.parseFloat(makeSafeHamFreq(activeFrequencyStr)))
                .bw(bandwidth.equals("Wide") ? DRA818_25K : DRA818_12K5)
                .squelch((byte) squelchLevel)
                .ctcssRx((byte) Math.max(0, ToneHelper.getToneIndex(memory.rxTone)))
                .ctcssTx((byte) Math.max(0, ToneHelper.getToneIndex(memory.txTone)))
                .build());
        }
        txAllowed = isTxAllowed(txFreq);
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

    private void checkScanDueToSilence() {
        // Note that we handle scanning explicitly like this rather than using dra->scan() because
        // as best I can tell the DRA818v chip has a defect where it always returns "S=1" (which
        // means there is no signal detected on the given frequency) even when there is. I did
        // extensive debugging and even rewrote large portions of the DRA818v library to determine
        // that this was the case. So in lieu of that, we scan using a timing/silence-based system.
        if (consecutiveSilenceBytes >= (AUDIO_SAMPLE_RATE * SEC_BETWEEN_SCANS)) {
            consecutiveSilenceBytes = 0;
            nextScan();
        }
    }

    private void initAudioTrack() {
        if (audioTrack != null) {
            audioTrack.release();
            audioTrack = null;
        }
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
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
            callbacks.radioMissing(); // Notify UI that connection is problematic
            return;
        }
        if (mode == RadioMode.RX && txAllowed) {
            setMode(RadioMode.TX);
            callbacks.sMeterUpdate(0);
            setTxRunAwayTimer();
            hostToEsp32.pttDown();
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
            hostToEsp32.pttUp();
            callbacks.txEnded();
        }
    }

    public void reconnectViaUSB() {
        findESP32Device();
    }

    private void findESP32Device() {
        Log.i(TAG, "findESP32Device()");
        setMode(RadioMode.STARTUP);
        setRadioType(RadioModuleType.UNKNOWN);
        Optional<UsbDevice> device = usbManager.getDeviceList().values().stream()
            .filter(this::isESP32Device)
            .findFirst();
        if (device.isPresent()) {
            Log.i(TAG, "Found ESP32.");
            callbacks.hideSnackBar();
            setupSerialConnection();
            return;
        }
        Log.w(TAG, "No ESP32 detected");
        callbacks.radioMissing();
    }

    private boolean isESP32Device(UsbDevice device) {
        int vendorId = device.getVendorId();
        int productId = device.getProductId();
        Log.i(TAG, String.format("Checking USB device: vendorId=%d, productId=%d, product=\"%s\"",
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
        Log.i(TAG, "Setting up serial connection to ESP32...");
        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            Log.e(TAG, "Error: no available USB drivers.");
            callbacks.radioMissing();
            return;
        }
        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            Log.e(TAG, "Error: couldn't open USB device.");
            callbacks.radioMissing();
            return;
        }
        serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        Log.i(TAG, "serialPort: " + serialPort);
        try {
            serialPort.open(connection);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (Exception e) {
            Log.e(TAG, "Error: couldn't open USB serial port.");
            callbacks.radioMissing();
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
                Log.e(TAG, "Error reading from ESP32.");
                if (audioTrack != null) {
                    audioTrack.stop();
                }
                connection.close();
                try {
                    serialPort.close();
                } catch (Exception ignored) {
                    // Ignore, we don't care if it fails to close.
                }
                // Attempt to reconnect after the brief pause above.
                handler.postDelayed(() -> findESP32Device(), 1000);
            }
        });
        usbIoManager.setWriteBufferSize(90000); // Must be large enough that ESP32 can take its time accepting our bytes without overrun.
        usbIoManager.setReadBufferSize(1024); // Must not be 0 (infinite) or it may block on read() until a write() occurs.
        usbIoManager.setReadBufferCount(16 * 2);
        usbIoManager.start();
        hostToEsp32 = new Protocol.Sender(usbIoManager);
        Log.i(TAG, "Connected to ESP32.");
        protocolHandshake.start();
    }

    /**
     * @param radioType should be RADIO_TYPE_UHF or RADIO_TYPE_VHF
     */
    public void setRadioType(RadioModuleType radioType) {
        callbacks.setRadioType(radioType);
        if (!Objects.equals(this.radioType, radioType)) {
            this.radioType = radioType;
            updateFrequencyLimitsForBand();
        }
    }

    public void updateFrequencyLimitsForBand() {
        // Ensure frequencies we're using match the radioType
        if (RadioModuleType.VHF.equals(getRadioType())) {
            setMinRadioFreq(VHF_MIN_FREQ);
            setMinTxFreq(min2mTxFreq);
            setMaxTxFreq(max2mTxFreq);
            setMaxRadioFreq(VHF_MAX_FREQ);
        } else if (RadioModuleType.UHF.equals(getRadioType())) {
            setMinRadioFreq(UHF_MIN_FREQ);
            setMinTxFreq(min70cmTxFreq);
            setMaxTxFreq(max70cmTxFreq);
            setMaxRadioFreq(UHF_MAX_FREQ);
        }
        Log.d(TAG, "Radio type set to: " + radioType);
        Log.d(TAG, "Min radio freq: " + minRadioFreq);
        Log.d(TAG, "Max radio freq: " + maxRadioFreq);
        Log.d(TAG, "Min tx freq: " + minTxFreq);
        Log.d(TAG, "Max tx freq: " + maxTxFreq);
        txAllowed = isTxAllowed(Float.parseFloat(activeFrequencyStr));
        Log.d(TAG, String.format("Tx allowed: %b (%s)", txAllowed, activeFrequencyStr));
    }

    public void setScanning(boolean scanning, boolean goToRxMode) {
        if (!scanning && mode != RadioMode.SCAN) {
            return;
        }
        if (!scanning) {
            // If squelch was off before we started scanning, turn it off again
            if (squelch == 0) {
                tuneToMemory(activeMemoryId, squelch, true);
            }
            if (goToRxMode) {
                setMode(RadioMode.RX);
            }
        } else { // Start scanning
            setMode(RadioMode.SCAN);
            nextScan();
        }
    }

    public void setScanning(boolean scanning) {
        setScanning(scanning, true);
    }

    public void nextScan() {
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
            if (!candidate.skipDuringScan && memoryFreqFloat >= minRadioFreq && memoryFreqFloat <= maxRadioFreq) {
                // Reset silence since we found an active memory.
                consecutiveSilenceBytes = 0;
                // If squelch is off (0), use squelch=1 during scanning.
                tuneToMemory(candidate, squelch > 0 ? squelch : 1, true);
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
        if (!dataMode) {
            samples = applyMicGain(samples);
        }
        byte[] audioFrame = new byte[Protocol.PROTO_MTU];
        int encodedLength = opusEncoder.encode(samples, audioFrame);
        hostToEsp32.txAudio(java.util.Arrays.copyOfRange(audioFrame, 0, encodedLength));
    }

    public boolean isRadioConnected() {
        return hostToEsp32 != null;
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
    private void handleParsedCommand(final RcvCommand cmd, final byte[] param, final Integer len) {
        switch (cmd) {
            case COMMAND_SMETER_REPORT:
                Protocol.Rssi.from(param, len)
                    .map(Protocol.Rssi::getSMeter9Value)
                    .filter(i -> getMode() == RadioMode.RX || getMode() == RadioMode.SCAN)
                    .ifPresent(callbacks::sMeterUpdate);
                break;

            case COMMAND_PHYS_PTT_DOWN:
                handlePhysicalPttDown();
                break;

            case COMMAND_PHYS_PTT_UP:
                handlePhysicalPttUp();
                break;

            case COMMAND_DEBUG_INFO:
                Log.i(FIRMWARE_TAG, new String(Arrays.copyOf(param, len)));
                break;

            case COMMAND_DEBUG_DEBUG:
                Log.d(FIRMWARE_TAG, new String(Arrays.copyOf(param, len)));
                break;

            case COMMAND_DEBUG_ERROR:
                Log.e(FIRMWARE_TAG, new String(Arrays.copyOf(param, len)));
                break;

            case COMMAND_DEBUG_WARN:
                Log.w(FIRMWARE_TAG, new String(Arrays.copyOf(param, len)));
                break;

            case COMMAND_DEBUG_TRACE:
                Log.v(FIRMWARE_TAG, new String(Arrays.copyOf(param, len)));
                break;

            case COMMAND_HELLO:
                protocolHandshake.onHelloReceived();
                break;

            case COMMAND_RX_AUDIO:
                handleRxAudio(param, len);
                break;

            case COMMAND_VERSION:
                protocolHandshake.onVersionReceived(Protocol.FirmwareVersion.from(param, len));
                break;

            case COMMAND_WINDOW_UPDATE:
                WindowUpdate.from(param, len).ifPresent(windowAck ->
                    hostToEsp32.enlargeFlowControlWindow(windowAck.getSize()));
                break;

            default:
                break;
        }
    }

    private void handlePhysicalPttUp() {
        if (getMode() == RadioMode.TX) {
            endPtt();
            callbacks.forcedPttEnd();
        }
    }

    private void handlePhysicalPttDown() {
        if (getMode() == RadioMode.RX && txAllowed) { // Note that people can't hit PTT in the middle of a scan.
            startPtt();
            callbacks.forcedPttStart();
        }
    }

    /**
     * Handles incoming audio data from the ESP32, decoding it and playing it through the AudioTrack.
     * If in RX or SCAN mode, it processes the audio samples and manages the AFSK demodulator.
     * In SCAN mode, it checks for silence to determine if a scan should be triggered.
     *
     * @param param The byte array containing the audio data.
     * @param len   The length of the audio data in bytes.
     */
    private void handleRxAudio(final byte[] param, final Integer len) {
        int decoded = opusDecoder.decode(param, len, pcmFloat);
        if (getMode() == RadioMode.RX || getMode() == RadioMode.SCAN) {
            afskDemodulator.addSamples(pcmFloat, decoded);
            if (audioTrack != null) {
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                audioTrack.write(pcmFloat, 0, decoded, AudioTrack.WRITE_NON_BLOCKING);
                audioManager.requestAudioFocus(audioFocusRequest);
                ensureAudioPlaying();
            }
        }
        if (getMode() == RadioMode.SCAN) {
            for (int i = 0; i < decoded; i++) {
                if (Math.abs(pcmFloat[i]) > 0.001) {
                    consecutiveSilenceBytes = 0;
                } else {
                    consecutiveSilenceBytes++;
                    checkScanDueToSilence();
                }
            }
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

    @Override
    public void handlePacket(byte[] packet) {
        try {
            APRSPacket aprsPacket = Parser.parseAX25(packet);
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
        if (!txAllowed || getMode() != RadioMode.RX) {
            Log.d(TAG, "Skipping position beacon: tx not allowed or not in RX mode.");
            return;
        }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(getBaseContext()) != ConnectionResult.SUCCESS) {
            Log.d(TAG, "Missing Google Play Services — cannot retrieve GPS location.");
            callbacks.unknownLocation();
            return;
        }
        FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(this);
        CancellationToken token = new CancellationTokenSource().getToken();
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token)
            .addOnSuccessListener(location -> {
                if (location != null) {
                    sendPositionBeacon(location.getLatitude(), location.getLongitude());
                } else {
                    callbacks.unknownLocation();
                }
            }).addOnFailureListener(e -> callbacks.unknownLocation());
    }

    /**
     * Sends a position beacon via APRS.
     * This method can only be called when the radio is in RX mode.
     *
     * @param latitude  The latitude to beacon.
     * @param longitude The longitude to beacon.
     */
    private void sendPositionBeacon(final double latitude, final double longitude) {
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
            callbacks.sentAprsBeacon(latitude, longitude);
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
     * Sends silent frames to the ESP32 for a specified duration.
     * This is used to ensure that there is silence before and after sending data.
     *
     * @param durationMs The duration in milliseconds for which to send silence.
     */
    private void sendSilentFrames(int durationMs) {
        float[] opusFrame = new float[OPUS_FRAME_SIZE];
        java.util.Arrays.fill(opusFrame, 0.0f);
        for (int i = 0; i < (durationMs / 40); i++) {
            sendAudioToESP32(opusFrame, true);
        }
    }

    /**
     * Sends an AX.25 packet to the ESP32 for transmission.
     * This method handles the modulation and transmission of the packet.
     *
     * @param ax25Packet The AX.25 packet to send.
     */
    private void txAX25Packet(Packet ax25Packet) {
        if (!txAllowed) {
            Log.e(TAG, "Tried to send an AX.25 packet when tx is not allowed, did not send.");
            return;
        }
        Log.d(TAG, "Sending AX25 packet: " + ax25Packet);
        startPtt();
        float[] opusFrame = new float[OPUS_FRAME_SIZE];
        // Send lead-in silence
        sendSilentFrames(MS_SILENCE_BEFORE_DATA_MS);
        // Prepare AFSK modulator
        int opusFrameIndex = 0;
        java.util.Arrays.fill(opusFrame, 0.0f);
        afskModulator.prepareToTransmit(ax25Packet);
        float[] buffer = afskModulator.getTxSamplesBuffer();
        // Modulate and send samples
        int n;
        while ((n = afskModulator.getSamples()) > 0) {
            for (int i = 0; i < n; i++) {
                opusFrame[opusFrameIndex++] = buffer[i];
                if (opusFrameIndex == OPUS_FRAME_SIZE) {
                    sendAudioToESP32(opusFrame, true);
                    java.util.Arrays.fill(opusFrame, 0.0f);
                    opusFrameIndex = 0;
                }
            }
        }
        // Send remaining audio if needed
        sendAudioToESP32(opusFrame, true);
        // Send tail silence
        sendSilentFrames(MS_SILENCE_AFTER_DATA_MS);
        endPtt();
        Log.i(TAG, "Send AX25 packet: " + ax25Packet);
    }

    public int getAudioTrackSessionId() {
        return Optional.ofNullable(audioTrack).map(AudioTrack::getAudioSessionId).orElse(-1);
    }

    /**
     * Sets the high power mode for the radio.
     * This will send the new state to the ESP32 if it is connected.
     *
     * @param highPower true to enable high power mode, false to disable it.
     */
    public void setHighPower(boolean highPower) {
        if (isHighPower != highPower) {
            isHighPower = highPower;
            if (isRadioConnected()) {
                hostToEsp32.setHighPower(HlState.builder()
                    .isHighPower(highPower)
                    .build());
            }
        }
    }

    /**
     * Sets whether radio module should poll RSSI. We need to be able to turn this off
     * because in v1.x versions of the PCB there's cross-talk between the Serial2 trace and
     * the audio trace, which breaks APRS decoding (and any other digital mode we might add).
     * See https://github.com/VanceVagell/kv4p-ht/issues/310 for context.
     *
     * This will send the new state to the ESP32 if it is connected.
     *
     * @param on true to enable RSSI, false to disable it.
     */
    public void setRssi(boolean on) {
        if (isRssiOn != on) {
            isRssiOn = on;
            if (isRadioConnected()) {
                hostToEsp32.setRssi(Protocol.RSSIState.builder()
                        .on(isRssiOn)
                        .build());
            }
        }
    }
}
