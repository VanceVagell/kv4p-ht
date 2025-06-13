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
import static com.vagell.kv4pht.radio.Protocol.ModuleType.SA818_UHF;
import static com.vagell.kv4pht.radio.Protocol.ModuleType.SA818_VHF;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
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
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import lombok.Setter;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
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
import com.vagell.kv4pht.javAX25.ax25.Afsk1200Modulator;
import com.vagell.kv4pht.javAX25.ax25.Afsk1200MultiDemodulator;
import com.vagell.kv4pht.javAX25.ax25.Arrays;
import com.vagell.kv4pht.javAX25.ax25.Packet;
import com.vagell.kv4pht.javAX25.ax25.PacketDemodulator;
import com.vagell.kv4pht.javAX25.ax25.PacketHandler;
import com.vagell.kv4pht.javAX25.ax25.PacketModulator;
import com.vagell.kv4pht.radio.Protocol.Config;
import com.vagell.kv4pht.radio.Protocol.Filters;
import com.vagell.kv4pht.radio.Protocol.FrameParser;
import com.vagell.kv4pht.radio.Protocol.Group;
import com.vagell.kv4pht.radio.Protocol.RadioStatus;
import com.vagell.kv4pht.radio.Protocol.RcvCommand;
import com.vagell.kv4pht.radio.Protocol.WindowUpdate;
import com.vagell.kv4pht.ui.MainActivity;
import com.vagell.kv4pht.ui.ToneHelper;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
/**
 * Background service that manages the connection to the ESP32 (to control the radio), and
 * handles playing back any audio received from the radio. This frees up the rest of the
 * application to focus primarily on the setup flows and UI, and ensures that the radio audio
 * continues to play even if the phone's screen is off or the user starts another app.
 */
public class RadioAudioService extends Service {

    private static final  String FIRMWARE_TAG = "firmware";
    private static final RadioAudioServiceCallbacks NO_OP_CALLBACKS = new RadioAudioServiceCallbacks() {};

    // Binder given to clients.
    private final IBinder binder = new RadioBinder();

    // Must match the ESP32 device we support.
    // Idx 0 matches https://www.amazon.com/gp/product/B08D5ZD528
    private static final int[] ESP32_VENDOR_IDS = {4292, 6790};
    private static final int[] ESP32_PRODUCT_IDS = {60000, 29987};

    public static final int MODE_STARTUP = -1;
    public static final int MODE_RX = 0;
    public static final int MODE_TX = 1;
    public static final int MODE_SCAN = 2;
    public static final int MODE_BAD_FIRMWARE = 3;
    public static final int MODE_FLASHING = 4;
    private int mode = MODE_STARTUP;
    private int messageNumber = 0;

    public static final byte SILENT_BYTE = 0;

    // Callbacks to the Activity that started us
    @Setter
    public @NonNull RadioAudioServiceCallbacks callbacks = NO_OP_CALLBACKS;

    // For transmitting audio to ESP32 / radio
    public static final int AUDIO_SAMPLE_RATE = 48000;
    public static final int RX_AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int RX_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT;
    public static final int RX_AUDIO_MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, RX_AUDIO_CHANNEL_CONFIG, RX_AUDIO_FORMAT) * 2;
    private UsbManager usbManager;
    private UsbDevice esp32Device;
    private static UsbSerialPort serialPort;
    private SerialInputOutputManager usbIoManager;
    private Protocol.Sender hostToEsp32;

    // For receiving audio from ESP32 / radio
    private final float[] pcmFloat = new float[OPUS_FRAME_SIZE];
    private AudioTrack audioTrack;
    private float  audioTrackVolume = 0.0f;
    private AudioFocusRequest audioFocusRequest;
    private static final float SEC_BETWEEN_SCANS = 0.5f; // how long to wait during silence to scan to next frequency in scan mode
    private LiveData<List<ChannelMemory>> channelMemoriesLiveData = null;
    public static final int OPUS_FRAME_SIZE = 1920; // 40ms at 48kHz
    private final OpusUtils.OpusDecoderWrapper opusDecoder = new OpusUtils.OpusDecoderWrapper(AUDIO_SAMPLE_RATE, OPUS_FRAME_SIZE);
    private final OpusUtils.OpusEncoderWrapper opusEncoder = new OpusUtils.OpusEncoderWrapper(AUDIO_SAMPLE_RATE, OPUS_FRAME_SIZE);

    private final FrameParser esp32DataStreamParser = new FrameParser(this::handleParsedCommand);

    // AFSK modem
    private PacketModulator afskModulator = null;
    private PacketDemodulator afskDemodulator = null;
    private static final int MS_SILENCE_BEFORE_DATA_MS = 1100;
    private static final int MS_SILENCE_AFTER_DATA_MS = 700;
    private static final int APRS_MAX_MESSAGE_NUM = 99999;

    // APRS position settings
    public static final int APRS_POSITION_EXACT = 0;
    public static final int APRS_POSITION_APPROX = 1;
    public static final int APRS_BEACON_MINS = 5;
    private boolean aprsBeaconPosition = false;
    private int aprsPositionAccuracy = APRS_POSITION_EXACT;
    private Handler aprsBeaconHandler = null;
    private Runnable aprsBeaconRunnable = null;

    // Radio params and related settings
    private static final float VHF_MIN_FREQ = 134.0f; // SA818U lower limit, in MHz
    private static float min2mTxFreq = 144.0f; // US 2m band lower limit, in MHz (will be overwritten by user setting)
    private static float max2mTxFreq = 148.0f; // US 2m band upper limit, in MHz (will be overwritten by user setting)
    private static final float VHF_MAX_FREQ = 174.0f; // SA818U upper limit, in MHz

    private static final float UHF_MIN_FREQ = 400.0f; // SA818U lower limit, in MHz
    private static float min70cmTxFreq = 420.0f; // US 70cm band lower limit, in MHz (will be overwritten by user setting)
    private static float max70cmTxFreq = 450.0f; // US 70cm band upper limit, in MHz (will be overwritten by user setting)
    private static final float UHF_MAX_FREQ = 480.0f; // SA818U upper limit, in MHz (DRA818U can only go to 470MHz)

    private String activeFrequencyStr = null;
    private int squelch = 0;
    private String callsign = null;
    private int consecutiveSilenceBytes = 0; // To determine when to move scan after silence
    private int activeMemoryId = -1; // -1 means we're in simplex mode
    public static float minRadioFreq = VHF_MIN_FREQ; // in MHz
    public static float maxRadioFreq = VHF_MAX_FREQ; // in MHz
    private static float minHamFreq = min2mTxFreq; // in MHz
    private static float maxHamFreq = max2mTxFreq; // in MHz
    private MicGainBoost micGainBoost = MicGainBoost.NONE;
    private String bandwidth = "Wide";
    private boolean txAllowed = true;
    public static final String RADIO_MODULE_VHF = "v";
    public static final String RADIO_MODULE_UHF = "u";
    private String radioType = RADIO_MODULE_VHF;
    private boolean radioModuleNotFound = false;
    private boolean checkedFirmwareVersion = false;
    private boolean gotHello = false;
    private final Handler timeOutHandler = new Handler(Looper.getMainLooper());

    // Safety constants
    private static int RUNAWAY_TX_TIMEOUT_SEC = 180; // Stop runaway tx after 3 minutes

    // Notification stuff
    private static String MESSAGE_NOTIFICATION_CHANNEL_ID = "aprs_message_notifications";
    private static int MESSAGE_NOTIFICATION_TO_YOU_ID = 0;

    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2, 10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

    public enum MicGainBoost {
        NONE,
        LOW,
        MED,
        HIGH;

        public static MicGainBoost parse(String str) {
            if (str.equals("High")) {
                return HIGH;
            } else if (str.equals("Med")) {
                return MED;
            } else if (str.equals("Low")) {
                return LOW;
            }

            return NONE;
        }

        public static float toFloat(MicGainBoost micGainBoost) {
            if (micGainBoost == LOW) {
                return 1.5f;
            } else if (micGainBoost == MED) {
                return 2.0f;
            } else if (micGainBoost == HIGH) {
                return 2.5f;
            }

            return 1.0f;
        }
    }

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
        default void hideSnackbar() {}
        default void radioModuleHandshake() {}
        default void radioModuleNotFound() {}
        default void audioTrackCreated() {}
        default void packetReceived(APRSPacket aprsPacket) {}
        default void scannedToMemory(int memoryId) {}
        default void outdatedFirmware(int firmwareVer) {}
        default void missingFirmware() {}
        default void txStarted() {}
        default void txEnded() {}
        default void chatError(String snackbarText) {}
        default void sMeterUpdate(int value) {}
        default void aprsBeaconing(boolean beaconing, int accuracy) {}
        default void sentAprsBeacon(double latitude, double longitude) {}
        default void unknownLocation() {}
        default void forceTunedToFreq(String newFreqStr) {}
        default void forcedPttStart() {}
        default void forcedPttEnd() {}
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

    public boolean isTxAllowed() {
        return txAllowed;
    }

    public void setCallsign(String callsign) {
        this.callsign = callsign;
    }

    public void setFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        setRadioFilters(emphasis, highpass, lowpass);
    }

    public void setMicGainBoost(String micGainBoost) {
        this.micGainBoost = MicGainBoost.parse(micGainBoost);
    }

    public void setBandwidth(String bandwidth) {
        this.bandwidth = bandwidth;
    }

    public float getBandwidth() {
	if (this.bandwidth == "Wide") {
		return 0.025f;    // 25 KHz
	} else {
		return 0.0125f;   // 12.5 KHz
	}
    }

    public void setMinRadioFreq(float newMinFreq) {
        minRadioFreq = newMinFreq;

        // Detect if we're moving from VHF to UHF, and move active frequency to within band.
        if (mode != MODE_STARTUP && activeFrequencyStr != null && Float.parseFloat(activeFrequencyStr) < minRadioFreq) {
            tuneToFreq(String.format(java.util.Locale.US, "%.4f", min70cmTxFreq), squelch, true);
            callbacks.forceTunedToFreq(activeFrequencyStr);
        }
    }

    public float getMinRadioFreq() {
        return minRadioFreq;
    }

    public void setMaxRadioFreq(float newMaxFreq) {
        maxRadioFreq = newMaxFreq;

        // Detect if we're moving from UHF to VHF, and move active frequency to within band.
        if (mode != MODE_STARTUP && activeFrequencyStr != null && Float.parseFloat(activeFrequencyStr) > maxRadioFreq) {
            tuneToFreq(String.format(java.util.Locale.US, "%.4f", min2mTxFreq), squelch, true);
            callbacks.forceTunedToFreq(activeFrequencyStr);
        }
    }

    public float getMaxRadioFreq() {
        return maxRadioFreq;
    }

    // These methods enforce the limits below (which change when we switch bands)
    public static void setMinHamFreq(float newMinFreq) {
        minHamFreq = newMinFreq;
    }

    public static void setMaxHamFreq(float newMaxFreq) {
        maxHamFreq = newMaxFreq;
    }

    // These will come from user settings
    public static void setMin2mTxFreq(float newMinFreq) {
        min2mTxFreq = newMinFreq;
    }

    public static void setMax2mTxFreq(float newMaxFreq) {
        max2mTxFreq = newMaxFreq;
    }

    public static void setMin70cmTxFreq(float newMinFreq) {
        min70cmTxFreq = newMinFreq;
    }

    public static void setMax70cmTxFreq(float newMaxFreq) {
        max70cmTxFreq = newMaxFreq;
    }

    public void setAprsBeaconPosition(boolean aprsBeaconPosition) {
        if (!this.aprsBeaconPosition && aprsBeaconPosition) { // If it was off, and now turned on...
            Log.d("DEBUG", "Starting APRS position beaconing every " + APRS_BEACON_MINS + " mins");
            // Start beaconing
            aprsBeaconHandler = new Handler(Looper.getMainLooper());
            aprsBeaconRunnable = new Runnable() {
                @Override
                public void run() {
                    sendPositionBeacon();
                    aprsBeaconHandler.postDelayed(this, 60 * APRS_BEACON_MINS * 1000);
                }
            };
            aprsBeaconHandler.postDelayed(aprsBeaconRunnable, 60 * APRS_BEACON_MINS * 1000);

            // Tell callback we started (e.g. so it can show a snackbar letting user know)
            callbacks.aprsBeaconing(true, aprsPositionAccuracy);
        }

        if (!aprsBeaconPosition) {
            Log.d("DEBUG", "Stopping APRS position beaconing");

            // Stop beaconing
            if (null != aprsBeaconHandler) {
                aprsBeaconHandler.removeCallbacks(aprsBeaconRunnable);
            }
            aprsBeaconHandler = null;
            aprsBeaconRunnable = null;
        }

        this.aprsBeaconPosition = aprsBeaconPosition;
    }

    public boolean getAprsBeaconPosition() {
        return aprsBeaconPosition;
    }

    /**
     * @param aprsPositionAccuracy APRS_POSITION_EXACT or APRS_POSITION_APPROX
     */
    public void setAprsPositionAccuracy(int aprsPositionAccuracy) {
        this.aprsPositionAccuracy = aprsPositionAccuracy;
    }

    public int getAprsPositionAccuracy() {
        return aprsPositionAccuracy;
    }

    public void setMode(int mode) {
        switch (mode) {
            case MODE_FLASHING:
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
                    Log.e("DEBUG", "Error while restart ESP32.", e);
                }
                break;
            default:
                break;
        }

        this.mode = mode;
    }

    public void setSquelch(int squelch) {
        this.squelch = squelch;
    }

    public int getMode() {
        return mode;
    }

    public String getActiveFrequencyStr() {
        return activeFrequencyStr;
    }

    public float getActiveFrequency() {
        return Float.parseFloat(makeSafeHamFreq(activeFrequencyStr));
    }

    public void setActiveMemoryId(int activeMemoryId) {
        this.activeMemoryId = activeMemoryId;

        if (activeMemoryId > -1) {
            tuneToMemory(activeMemoryId, squelch, false);
        } else {
            tuneToFreq(activeFrequencyStr, squelch, false);
        }
    }

    public void setActiveFrequencyStr(String activeFrequencyStr) {
        this.activeFrequencyStr = activeFrequencyStr;

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
        findESP32Device();
        initAudioTrack();
        initAFSKModem();
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

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
      threadPoolExecutor.shutdownNow();
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

    // Tell microcontroller to tune to the given frequency string, which must already be formatted
    // in the style the radio module expects.
    public void tuneToFreq(String frequencyStr, int squelchLevel, boolean forceTune) {
        if (mode == MODE_STARTUP) {
            return; // Not fully loaded and initialized yet, don't tune.
        }

        setMode(MODE_RX);

        if (!forceTune && activeFrequencyStr.equals(frequencyStr) && squelch == squelchLevel) {
            return; // Already tuned to this frequency with this squelch level.
        }

        activeFrequencyStr = frequencyStr;
        squelch = squelchLevel;

        if (serialPort != null) {
            hostToEsp32.group(Group.builder()
                .freqTx(Float.parseFloat(makeSafeHamFreq(activeFrequencyStr)))
                .freqRx(Float.parseFloat(makeSafeHamFreq(activeFrequencyStr)))
                .bw((bandwidth.equals("Wide") ? DRA818_25K : DRA818_12K5))
                .squelch((byte) squelchLevel)
                .build());
        }

        try {
            Float freq = Float.parseFloat(makeSafeHamFreq(activeFrequencyStr));
            Float halfBandwidth = (bandwidth.equals("Wide") ? 0.025f : 0.0125f) / 2;
            Float offsetMaxFreq = maxHamFreq - halfBandwidth;
            Float offsetMinFreq = minHamFreq + halfBandwidth;
            if (freq < offsetMinFreq || freq > offsetMaxFreq) {
                txAllowed = false;
            } else {
                txAllowed = true;
            }
        } catch (NumberFormatException nfe) {
        }
    }

    public static String makeSafeHamFreq(String strFreq) {
        Float freq;
        try {
            freq = Float.parseFloat(strFreq);
        } catch (NumberFormatException nfe) {
            return String.format(java.util.Locale.US, "%.4f", minHamFreq); // 4 decimal places, in MHz
        }
        while (freq > 500.0f) { // Handle cases where user inputted "1467" or "14670" but meant "146.7".
            freq /= 10;
        }

        if (freq < minRadioFreq) {
            freq = minRadioFreq; // Lowest freq supported by radio module
        } else if (freq > maxRadioFreq) {
            freq = maxRadioFreq; // Highest freq supported
        }

        strFreq = String.format(java.util.Locale.US, "%.4f", freq);

        return strFreq;
    }

    public String validateFrequency(String tempFrequency) {
        String newFrequency = makeSafeHamFreq(tempFrequency);

        // Resort to the old frequency, the one the user inputted is unsalvageable.
        return newFrequency == null ? activeFrequencyStr : newFrequency;
    }

    public void tuneToMemory(int memoryId, int squelchLevel, boolean forceTune) {
        if (!forceTune && activeMemoryId == memoryId && squelch == squelchLevel) {
            return; // Already tuned to this memory, with this squelch.
        }

        if (mode == MODE_STARTUP) {
            return; // Not fully loaded and initialized yet, don't tune.
        }

        if (channelMemoriesLiveData == null) {
            Log.d("DEBUG", "Error: attempted tuneToMemory() but channelMemories was never set.");
            return;
        }
        List<ChannelMemory> channelMemories = channelMemoriesLiveData.getValue();
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == memoryId) {
                if (serialPort != null) {
                    tuneToMemory(channelMemories.get(i), squelchLevel, forceTune);
                }
            }
        }
    }

    public void tuneToMemory(ChannelMemory memory, int squelchLevel, boolean forceTune) {
        if (!forceTune && activeMemoryId == memory.memoryId && squelch == squelchLevel) {
            return; // Already tuned to this memory, with this squelch.
        }

        if (mode == MODE_STARTUP) {
            return; // Not fully loaded and initialized yet, don't tune.
        }

        if (memory == null) {
            return;
        }

        activeFrequencyStr = validateFrequency(memory.frequency);
        activeMemoryId = memory.memoryId;
        Float txFreq = null;
        try {
            txFreq = Float.parseFloat(getTxFreq(memory.frequency, memory.offset, memory.offsetKhz));
        } catch (NumberFormatException nfe) {
        }

        if (serialPort != null) {
            int rxToneIdx = ToneHelper.getToneIndex(memory.rxTone);
            int txToneIdx = ToneHelper.getToneIndex(memory.txTone);
            hostToEsp32.group(Group.builder()
                    .freqTx(txFreq)
                    .freqRx(Float.parseFloat(makeSafeHamFreq(activeFrequencyStr)))
                    .bw((bandwidth.equals("Wide") ? DRA818_25K : DRA818_12K5))
                    .squelch((byte) squelchLevel)
                    .ctcssRx(new Integer(rxToneIdx == -1 ? 0 : rxToneIdx).byteValue())
                    .ctcssTx(new Integer(txToneIdx == -1 ? 0 : txToneIdx).byteValue())
                    .build());
        }

        Float deviation = (bandwidth.equals("Wide") ? 0.005f : 0.0025f);
        Float offsetMaxFreq = maxHamFreq - deviation;
        Float offsetMinFreq = minHamFreq + deviation;
        if (txFreq < offsetMinFreq || txFreq > offsetMaxFreq) {
            txAllowed = false;
        } else {
            txAllowed = true;
        }
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
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build();
        audioTrack.setVolume(0.0f);
        audioTrackVolume = 0.0f;
        audioTrack.setAuxEffectSendLevel(0.0f);

        callbacks.audioTrackCreated();
    }

    private void setTxRunAwayTimer() {
        // Setup runaway tx safety measures.
        long startTxTimeSec = System.currentTimeMillis() / 1000;
        threadPoolExecutor.execute(() -> {
            try {
                Thread.sleep(RUNAWAY_TX_TIMEOUT_SEC * 1000L);
                if (mode != MODE_TX) {
                    return;
                }
                long elapsedSec = (System.currentTimeMillis() / 1000) - startTxTimeSec;
                if (elapsedSec
                    > RUNAWAY_TX_TIMEOUT_SEC) { // Check this because multiple tx may have happened with RUNAWAY_TX_TIMEOUT_SEC.
                    Log.d("DEBUG", "Warning: runaway tx timeout reached, PTT stopped.");
                    endPtt();
                }
            } catch (InterruptedException e) {
            }
        });
    }

    public void startPtt() {
        if (mode == MODE_RX && txAllowed) {
            setMode(MODE_TX);
            callbacks.sMeterUpdate(0);
            setTxRunAwayTimer();
            hostToEsp32.pttDown();
            audioTrackVolume = 0.0f;
            Optional.ofNullable(audioTrack).ifPresent(t -> t.setVolume(0.0f));
            callbacks.txStarted();
        } else {
            Log.d("DEBUG", "Warning: Attempted startPtt when it should not happen.");
            new Throwable().printStackTrace();
        }
    }

    public void endPtt() {
        if (mode == MODE_TX) {
            setMode(MODE_RX);
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
        Log.d("DEBUG", "findESP32Device()");

        setMode(MODE_STARTUP);
        esp32Device = null;

        if (null == usbIoManager) {
            Log.d("DEBUG", "Warning: usbManager was null in findESP32Device. Retrying momentarily.");

            callbacks.radioMissing(); // Not quite accurate, but this situation is likely transient/race condition.

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            setupSerialConnection(); // Attempt to reconnect after the brief pause above.

            return;
        }

        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();

        for (UsbDevice device : usbDevices.values()) {
            // Check for device's vendor ID and product ID
            if (isESP32Device(device)) {
                esp32Device = device;
                break;
            }
        }

        if (esp32Device == null) {
            Log.d("DEBUG", "No ESP32 detected");
            callbacks.radioMissing();
        } else {
            Log.d("DEBUG", "Found ESP32.");
            callbacks.hideSnackbar();
            setupSerialConnection();
        }
    }

    private boolean isESP32Device(UsbDevice device) {
        Log.d("DEBUG", "isESP32Device()");

        int vendorId = device.getVendorId();
        int productId = device.getProductId();
        Log.d("DEBUG", "vendorId: " + vendorId + " productId: " + productId + " name: " + device.getDeviceName());
        // TODO these vendor and product checks might be too rigid/brittle for future PCBs,
        // especially those that are more custom and not a premade dev board. But we need some way
        // to tell if the given USB device is an ESP32 so we can interact with the right device.
        for (int i = 0; i < ESP32_VENDOR_IDS.length; i++) {
            if ((vendorId == ESP32_VENDOR_IDS[i]) && (productId == ESP32_PRODUCT_IDS[i])) {
                return true;
            }
        }
        return false;
    }

    public void setupSerialConnection() {
        Log.d("DEBUG", "setupSerialConnection()");

        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            Log.d("DEBUG", "Error: no available USB drivers.");
            callbacks.radioMissing();
            return;
        }

        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            Log.d("DEBUG", "Error: couldn't open USB device.");
            callbacks.radioMissing();
            return;
        }

        serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        Log.d("DEBUG", "serialPort: " + serialPort);
        try {
            serialPort.open(connection);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (Exception e) {
            Log.d("DEBUG", "Error: couldn't open USB serial port.");
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
                Log.d("DEBUG", "Error reading from ESP32.");
                if (audioTrack != null) {
                    audioTrack.stop();
                }
                connection.close();
                try {
                    serialPort.close();
                } catch (Exception ex) {
                    // Ignore.
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                findESP32Device(); // Attempt to reconnect after the brief pause above.
            }
        });
        usbIoManager.setWriteBufferSize(90000); // Must be large enough that ESP32 can take its time accepting our bytes without overrun.
        usbIoManager.setReadBufferSize(1024); // Must not be 0 (infinite) or it may block on read() until a write() occurs.
        usbIoManager.setReadBufferCount(16 * 2);
        usbIoManager.start();
        hostToEsp32 = new Protocol.Sender(usbIoManager);
        checkedFirmwareVersion = false;
        gotHello = false;

        Log.d("DEBUG", "Connected to ESP32.");
        timeOutHandler.removeCallbacksAndMessages(null);
        timeOutHandler.postDelayed(() -> {
            if (!gotHello) {
                Log.d("DEBUG", "Error: No HELLO received from module.");
                callbacks.missingFirmware();
                setMode(MODE_BAD_FIRMWARE);
            }
        }, 1000);
    }

    /**
     * @param radioType should be RADIO_TYPE_UHF or RADIO_TYPE_VHF
     */
    public void setRadioType(String radioType) {
        Log.d("DEBUG", "setRadioType: " + radioType);

        if (!this.radioType.equals(radioType)) {
            this.radioType = radioType;

            // Ensure frequencies we're using match the radioType
            if (radioType.equals(RADIO_MODULE_VHF)) {
                setMinRadioFreq(VHF_MIN_FREQ);
                setMinHamFreq(min2mTxFreq);
                setMaxHamFreq(max2mTxFreq);
                setMaxRadioFreq(VHF_MAX_FREQ);
            } else if (radioType.equals(RADIO_MODULE_UHF)) {
                setMinRadioFreq(UHF_MIN_FREQ);
                setMinHamFreq(min70cmTxFreq);
                setMaxHamFreq(max70cmTxFreq);
                setMaxRadioFreq(UHF_MAX_FREQ);
            }

            if (mode != MODE_STARTUP) {
                // Re-init connection to ESP32 so it knows what kind of module it has.
                setMode(MODE_STARTUP);
                checkedFirmwareVersion = false;
                checkFirmwareVersion();
            }
        }
    }

    public String getRadioType() {
        return radioType;
    }

    private void checkFirmwareVersion() {
        checkedFirmwareVersion = true; // To prevent multiple USB connect events from spamming the ESP32 with requests (which can cause logic errors).

        // Verify that the firmware of the ESP32 app is supported.
        setMode(MODE_STARTUP);

        hostToEsp32.stop();
        hostToEsp32.config(Config.builder()
            .moduleType(radioType.equals(RADIO_MODULE_UHF) ? SA818_UHF : SA818_VHF)
            .build());
        // The version is actually evaluated in handleESP32Data().

        // If we don't hear back from the ESP32, it means the firmware is either not
        // installed or it's somehow corrupt.
        timeOutHandler.removeCallbacksAndMessages(null);
        timeOutHandler.postDelayed(() -> {
            if (mode == MODE_STARTUP && !checkedFirmwareVersion) {
                Log.d("DEBUG", "Error: Did not hear back from ESP32 after requesting its firmware version. Offering to flash.");
                callbacks.missingFirmware();
                setMode(MODE_BAD_FIRMWARE);
            }
        }, 60000);
    }

    private void initAfterESP32Connected() {
        setMode(MODE_RX);
        // Turn off scanning if it was on (e.g. if radio was unplugged briefly and reconnected)
        setScanning(false);
        callbacks.radioConnected();
    }

    public void setScanning(boolean scanning, boolean goToRxMode) {
        if (!scanning && mode != MODE_SCAN) {
            return;
        }

        if (!scanning) {
            // If squelch was off before we started scanning, turn it off again
            if (squelch == 0) {
                tuneToMemory(activeMemoryId, squelch, true);
            }

            if (goToRxMode) {
                setMode(MODE_RX);
            }
        } else { // Start scanning
            setMode(MODE_SCAN);
            nextScan();
        }
    }

    public void setScanning(boolean scanning) {
        setScanning(scanning, true);
    }

    public void nextScan() {
        // Only proceed if actually in SCAN mode.
        if (mode != MODE_SCAN) {
            return;
        }

        // Make sure channelMemoriesLiveData is set and has items.
        if (channelMemoriesLiveData == null) {
            Log.d("DEBUG", "Error: attempted nextScan() but channelMemories was never set.");
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
                Log.d("DEBUG", "Memory with id " + candidate.memoryId + " had invalid frequency.");
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
        Log.d("DEBUG", "Warning: All memories are skipDuringScan, no next memory found to scan to.");
    }

    private float[] applyMicGain(float[] audioBuffer) {
        if (micGainBoost == MicGainBoost.NONE) {
            return audioBuffer; // No gain, just return original
        }
        float[] newAudioBuffer = new float[audioBuffer.length];
        float gain = MicGainBoost.toFloat(micGainBoost);
        for (int i = 0; i < audioBuffer.length; i++) {
            newAudioBuffer[i] = audioBuffer[i] * gain;
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

    public static UsbSerialPort getUsbSerialPort() {
        return serialPort;
    }

    @SuppressWarnings({"java:S6541"})
    private void handleParsedCommand(final RcvCommand cmd, final byte[] param, final Integer len) {
        switch (cmd) {
            case COMMAND_SMETER_REPORT:
                Protocol.Rssi.from(param, len)
                    .map(Protocol.Rssi::getSMeter9Value)
                    .filter(i -> mode == MODE_RX || mode == MODE_SCAN)
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
                handleHello();
                break;

            case COMMAND_RX_AUDIO:
                handleRxAudio(param, len);
                break;

            case COMMAND_VERSION:
                handleVersion(param, len);
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
        if (mode == MODE_TX) {
            endPtt();
            callbacks.forcedPttEnd();
        }
    }

    private void handlePhysicalPttDown() {
        if (mode == MODE_RX && txAllowed) { // Note that people can't hit PTT in the middle of a scan.
            startPtt();
            callbacks.forcedPttStart();
        }
    }

    private void handleHello() {
        gotHello = true;
        if (audioTrack != null) {
            audioTrack.stop();
        }
        callbacks.radioModuleHandshake();
        checkFirmwareVersion();
    }

    private void handleVersion(final byte[] param, final Integer len) {
        if (mode == MODE_STARTUP) {
            Protocol.FirmwareVersion.from(param, len).ifPresent(ver -> {
                if (ver.getVer() < FirmwareUtils.PACKAGED_FIRMWARE_VER) {
                    Log.e("DEBUG", "Error: ESP32 app firmware " + ver.getVer() + " is older than latest firmware "
                            + FirmwareUtils.PACKAGED_FIRMWARE_VER);
                    callbacks.outdatedFirmware(ver.getVer());
                    return;
                }
                Log.i("DEBUG", "Recent ESP32 app firmware version detected (" + ver + ").");
                radioModuleNotFound = ver.getRadioModuleStatus() != RadioStatus.RADIO_STATUS_FOUND;
                if (radioModuleNotFound) {
                    callbacks.radioModuleNotFound();
                } else {
                    hostToEsp32.setFlowControlWindow(ver.getWindowSize());
                    initAfterESP32Connected();
                }
            });
        }
    }

    private void handleRxAudio(final byte[] param, final Integer len) {
        int decoded = opusDecoder.decode(param, len, pcmFloat);
        if (mode == MODE_RX || mode == MODE_SCAN) {
            if (afskDemodulator != null) {
                afskDemodulator.addSamples(pcmFloat, decoded);
            }
            if (audioTrack != null) {
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                audioTrack.write(pcmFloat, 0, decoded, AudioTrack.WRITE_NON_BLOCKING);
                audioManager.requestAudioFocus(audioFocusRequest);
                ensureAudioPlaying();
            }
        }
        if (mode == MODE_SCAN) {
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

    private void initAFSKModem() {
        final Context activity = this;

        PacketHandler packetHandler = new PacketHandler() {
            @Override
            public void handlePacket(byte[] data) {
                APRSPacket aprsPacket;
                try {
                    aprsPacket = Parser.parseAX25(data);

                    final String finalString;

                    // Reformat the packet to be more human readable.
                    InformationField infoField = aprsPacket.getAprsInformation();
                    if (infoField.getDataTypeIdentifier() == ':') { // APRS "message" type. What we expect for our text chat.
                        MessagePacket messagePacket = new MessagePacket(infoField.getRawBytes(), aprsPacket.getDestinationCall());

                        // If the message was addressed to us, notify the user and ACK the message to the sender.
                        if (!messagePacket.isAck() && messagePacket.getTargetCallsign().trim().toUpperCase().equals(callsign.toUpperCase())) {
                            showNotification(MESSAGE_NOTIFICATION_CHANNEL_ID, MESSAGE_NOTIFICATION_TO_YOU_ID,
                                    aprsPacket.getSourceCall() + " messaged you", messagePacket.getMessageBody(), MainActivity.INTENT_OPEN_CHAT);

                            // Send ack after a brief delay (to let the sender keyup and start decooding again)
                            final Handler handler = new Handler(Looper.getMainLooper());
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    sendAckMessage(aprsPacket.getSourceCall().toUpperCase(), messagePacket.getMessageNumber());
                                }
                            }, 1000);
                        }
                    }
                } catch (Exception e) {
                    Log.d("DEBUG", "Unable to parse an APRSPacket, skipping.");
                    return;
                }

                // Let our parent Activity know about the packet, so it can display chat.
                callbacks.packetReceived(aprsPacket);
            }
        };

        try {
            afskDemodulator = new Afsk1200MultiDemodulator(AUDIO_SAMPLE_RATE, packetHandler);
            afskModulator = new Afsk1200Modulator(AUDIO_SAMPLE_RATE);
        } catch (Exception e) {
            Log.d("DEBUG", "Unable to create AFSK modem objects.");
        }
    }

    public void sendPositionBeacon() {
        if (!txAllowed) {
            return; // Don't try to beacon if person is outside the ham band. But don't need to show an error, would be spammy (e.g. when listening to NOAA radio).
        }

        if (getMode() != MODE_RX) { // Can only beacon in rx mode (e.g. not tx or scan)
            Log.d("DEBUG", "Skipping position beacon because not in RX mode");
            return;
        }

        LocationManager lm = (LocationManager)getSystemService(Context.LOCATION_SERVICE);

        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(getBaseContext()) != ConnectionResult.SUCCESS) {
            Log.d("DEBUG", "Unable to beacon position because Android device is missing Google Play Services, needed to get GPS location.");
            callbacks.unknownLocation();
            return;
        }

        // Otherwise, manually retrieve a new location for user.
        FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();

        fusedLocationClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.getToken())
            .addOnSuccessListener(new OnSuccessListener<Location>() {
                @Override
                public void onSuccess(Location location) {
                    if (location != null) {
                        // Use the location
                        double latitude = location.getLatitude();
                        double longitude = location.getLongitude();
                        sendPositionBeacon(latitude, longitude);
                    } else {
                        callbacks.unknownLocation();
                    }
                }
            }).addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    callbacks.unknownLocation();
                }
            });
    }

    private void sendPositionBeacon(double latitude, double longitude) {
        if (getMode() != MODE_RX) { // Can only beacon in rx mode (e.g. not tx or scan)
            Log.d("DEBUG", "Skipping position beacon because not in RX mode");
            return;
        }

        Log.d("DEBUG", "Beaconing position via APRS now");

        if (aprsPositionAccuracy == APRS_POSITION_APPROX) {
            // Fuzz the location (2 decimal places gives a spot in the neighborhood)
            longitude = Double.valueOf(String.format("%.2f", longitude));
            latitude = Double.valueOf(String.format("%.2f", latitude));
        }

        ArrayList<Digipeater> digipeaters = new ArrayList<>();
        digipeaters.add(new Digipeater("WIDE1*"));
        digipeaters.add(new Digipeater("WIDE2-1"));
        Position myPos = new Position(latitude, longitude);
        try {
            PositionField posField = new PositionField(("=" + myPos.toCompressedString()).getBytes(), "", 1);
            APRSPacket aprsPacket = new APRSPacket(callsign, "BEACON", digipeaters, posField.getRawBytes());
            aprsPacket.getAprsInformation().addAprsData(APRSTypes.T_POSITION, posField);
            Packet ax25Packet = new Packet(aprsPacket.toAX25Frame());

            txAX25Packet(ax25Packet);

            callbacks.sentAprsBeacon(latitude, longitude);
        } catch (Exception e) {
            Log.d("DEBUG", "Exception while trying to beacon APRS location.");
            e.printStackTrace();
        }
    }

    public void sendAckMessage(String targetCallsign, String remoteMessageNum) {
        // Prepare APRS packet, and use its bytes to populate an AX.25 packet.
        MessagePacket msgPacket = new MessagePacket(targetCallsign, "ack" + remoteMessageNum, remoteMessageNum);
        ArrayList<Digipeater> digipeaters = new ArrayList<>();
        digipeaters.add(new Digipeater("WIDE1*"));
        digipeaters.add(new Digipeater("WIDE2-1"));
        APRSPacket aprsPacket = new APRSPacket(callsign, targetCallsign, digipeaters, msgPacket.getRawBytes());
        Packet ax25Packet = new Packet(aprsPacket.toAX25Frame());

        txAX25Packet(ax25Packet);
    }

    /**
     * @param targetCallsign
     * @param outText
     * @return The message number that was used for the message, or -1 if there was a problem.
     */
    public int sendChatMessage(String targetCallsign, String outText) {
        // Remove reserved APRS characters.
        outText = outText.replace('|', ' ');
        outText = outText.replace('~', ' ');
        outText = outText.replace('{', ' ');

        // Prepare APRS packet, and use its bytes to populate an AX.25 packet.
        MessagePacket msgPacket = new MessagePacket(targetCallsign, outText, "" + (messageNumber++));
        if (messageNumber > APRS_MAX_MESSAGE_NUM) {
            messageNumber = 0;
        }
        ArrayList<Digipeater> digipeaters = new ArrayList<>();
        digipeaters.add(new Digipeater("WIDE1*"));
        digipeaters.add(new Digipeater("WIDE2-1"));
        if (null == callsign || callsign.trim().equals("")) {
            Log.d("DEBUG", "Error: Tried to send a chat message with no sender callsign.");
            return -1;
        }
        if (null == targetCallsign || targetCallsign.trim().equals("")) {
            Log.d("DEBUG", "Warning: Tried to send a chat message with no recipient callsign, defaulted to 'CQ'.");
            targetCallsign = "CQ";
        }

        Packet ax25Packet = null;
        try {
            APRSPacket aprsPacket = new APRSPacket(callsign, targetCallsign, digipeaters, msgPacket.getRawBytes());
            ax25Packet = new Packet(aprsPacket.toAX25Frame());
        } catch (IllegalArgumentException iae) {
            callbacks.chatError("Error in your callsign or To: callsign.");
            return -1;
        }

        // TODO start a timer to re-send this packet (up to a few times) if we don't receive an ACK for it.
        txAX25Packet(ax25Packet);

        return messageNumber - 1;
    }

    private void sendSilentFrames(int durationMs) {
        float[] opusFrame = new float[OPUS_FRAME_SIZE];
        java.util.Arrays.fill(opusFrame, 0.0f);
        for (int i = 0; i < (durationMs / 40); i++) {
            sendAudioToESP32(opusFrame, true);
        }
    }

    private void txAX25Packet(Packet ax25Packet) {
        if (!txAllowed) {
            Log.d("DEBUG", "Tried to send an AX.25 packet when tx is not allowed, did not send.");
            return;
        }
        Log.d("DEBUG", "Sending AX25 packet: " + ax25Packet);
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
        Log.i("DEBUG", "Send AX25 packet: " + ax25Packet);
    }

    public int getAudioTrackSessionId() {
        return Optional.ofNullable(audioTrack).map(AudioTrack::getAudioSessionId).orElse(-1);
    }

    private void showNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {
        if (notificationChannelId == null || title == null || message == null) {
            Log.d("DEBUG", "Unexpected null in showNotification.");
            return;
        }

        // Has the user disallowed notifications?
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // If they tap the notification when doing something else, come back to this app
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(tapIntentName);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Notify the user they got a message.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, notificationChannelId)
                .setSmallIcon(R.drawable.ic_chat_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true) // Dismiss on tap
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(notificationTypeId, builder.build());
    }
}
