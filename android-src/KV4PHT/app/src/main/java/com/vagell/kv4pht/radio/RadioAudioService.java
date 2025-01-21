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

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
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
import com.vagell.kv4pht.javAX25.ax25.Packet;
import com.vagell.kv4pht.javAX25.ax25.PacketDemodulator;
import com.vagell.kv4pht.javAX25.ax25.PacketHandler;
import com.vagell.kv4pht.ui.MainActivity;

import org.apache.commons.lang3.ArrayUtils;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    // Binder given to clients.
    private final IBinder binder = new RadioBinder();

    // Must match the ESP32 device we support.
    // Idx 0 matches https://www.amazon.com/gp/product/B08D5ZD528
    private static final int[] ESP32_VENDOR_IDS = {4292};
    private static final int[] ESP32_PRODUCT_IDS = {60000};

    // Version related constants (also see FirmwareUtils for others)
    private static final String VERSION_PREFIX = "VERSION";
    private static String versionStrBuffer = "";
    private static final int VERSION_LENGTH = 8; // Chars in the version string from ESP32 app.

    public static final int MODE_STARTUP = -1;
    public static final int MODE_RX = 0;
    public static final int MODE_TX = 1;
    public static final int MODE_SCAN = 2;
    public static final int MODE_BAD_FIRMWARE = 3;
    public static final int MODE_FLASHING = 4;
    private int mode = MODE_STARTUP;
    private int messageNumber = 0;

    public static final byte SILENT_BYTE = -128;

    // Callbacks to the Activity that started us
    private RadioAudioServiceCallbacks callbacks = null;

    // For transmitting audio to ESP32 / radio
    public static final int AUDIO_SAMPLE_RATE = 22050;
    public static final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    public static final  int audioFormat = AudioFormat.ENCODING_PCM_8BIT;
    public static final  int minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, audioFormat) * 4;
    private UsbManager usbManager;
    private UsbDevice esp32Device;
    private static UsbSerialPort serialPort;
    private SerialInputOutputManager usbIoManager;
    private static final int TX_AUDIO_CHUNK_SIZE = 512; // Tx audio bytes to send to ESP32 in a single USB write
    private Map<String, Integer> mTones = new HashMap<>();
    private static final int MS_FOR_FINAL_TX_AUDIO_BEFORE_PTT_UP = 400;

    // For receiving audio from ESP32 / radio
    private AudioTrack audioTrack;
    private static final int PRE_BUFFER_SIZE = 256;
    private byte[] rxBytesPrebuffer = new byte[PRE_BUFFER_SIZE];
    private int rxPrebufferIdx = 0;
    private boolean prebufferComplete = false;
    private static final float SEC_BETWEEN_SCANS = 0.5f; // how long to wait during silence to scan to next frequency in scan mode
    private LiveData<List<ChannelMemory>> channelMemoriesLiveData = null;

    // Delimiter must match ESP32 code
    private static final byte[] COMMAND_DELIMITER = new byte[] {(byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00};
    private static final byte COMMAND_SMETER_REPORT = 0x53; // Ascii "S"

    // This buffer holds leftover data that wasn’t fully parsed yet (from ESP32 audio stream)
    private final ByteArrayOutputStream leftoverBuffer = new ByteArrayOutputStream();

    // AFSK modem
    private Afsk1200Modulator afskModulator = null;
    private PacketDemodulator afskDemodulator = null;
    private static final int MS_DELAY_BEFORE_DATA_XMIT = 1000;
    private static final int MS_SILENCE_BEFORE_DATA = 300;
    private static final int MS_SILENCE_AFTER_DATA = 700;
    private static final int APRS_MAX_MESSAGE_NUM =  99999;

    // APRS position settings
    public static final int APRS_POSITION_EXACT = 0;
    public static final int APRS_POSITION_APPROX = 1;
    public static final int APRS_BEACON_MINS = 5;
    private boolean aprsBeaconPosition = false;
    private int aprsPositionAccuracy = APRS_POSITION_EXACT;
    private Handler aprsBeaconHandler = null;
    private Runnable aprsBeaconRunnable = null;

    // Radio params and related settings
    private static final float VHF_MIN_FREQ    = 134.0f; // DRA818V lower limit, in MHz
    private static final float VHF_MIN_FREQ_US = 144.0f; // US 2m band lower limit, in MHz
    private static final float VHF_MAX_FREQ_US = 148.0f; // US 2m band upper limit, in MHz
    private static final float VHF_MAX_FREQ    = 174.0f; // DRA818V upper limit, in MHz

    private static final float UHF_MIN_FREQ    = 400.0f; // DRA818U lower limit, in MHz
    private static final float UHF_MIN_FREQ_US = 420.0f; // US 70cm band lower limit, in MHz
    private static final float UHF_MAX_FREQ_US = 450.0f; // US 70cm band upper limit, in MHz
    private static final float UHF_MAX_FREQ    = 470.0f; // DRA818U upper limit, in MHz

    private String activeFrequencyStr = String.format(java.util.Locale.US, "%.4f", VHF_MIN_FREQ_US); // 4 decimal places, in MHz
    private int squelch = 0;
    private String callsign = null;
    private int consecutiveSilenceBytes = 0; // To determine when to move scan after silence
    private int activeMemoryId = -1; // -1 means we're in simplex mode
    private static float minRadioFreq = VHF_MIN_FREQ; // in MHz
    private static float maxRadioFreq = VHF_MAX_FREQ; // in MHz
    private static float minHamFreq = VHF_MIN_FREQ_US; // in MHz
    private static float maxHamFreq = VHF_MAX_FREQ_US; // in MHz
    private MicGainBoost micGainBoost = MicGainBoost.NONE;
    private String bandwidth = "Wide";
    private boolean txAllowed = true;
    private static final String RADIO_MODULE_NOT_FOUND = "x";
    private static final String RADIO_MODULE_FOUND = "f";
    public static final String RADIO_MODULE_VHF = "v";
    public static final String RADIO_MODULE_UHF = "u";
    private String radioType = RADIO_MODULE_VHF;
    private boolean radioModuleNotFound = false;
    private boolean checkedFirmwareVersion = false;

    // Safety constants
    private static int RUNAWAY_TX_TIMEOUT_SEC = 180; // Stop runaway tx after 3 minutes
    private long startTxTimeSec = -1;

    // Notification stuff
    private static String MESSAGE_NOTIFICATION_CHANNEL_ID = "aprs_message_notifications";
    private static int MESSAGE_NOTIFICATION_TO_YOU_ID = 0;

    private ThreadPoolExecutor threadPoolExecutor = null;

    public enum ESP32Command {
        PTT_DOWN((byte) 1),
        PTT_UP((byte) 2),
        TUNE_TO((byte) 3), // paramsStr contains freq, offset, tone details
        FILTERS((byte) 4), // paramStr contains emphasis, highpass, lowpass (each 0/1)
        STOP((byte) 5),
        GET_FIRMWARE_VER((byte) 6);

        private byte commandByte;
        ESP32Command(byte commandByte) {
            this.commandByte = commandByte;
        }

        public byte getByte() {
            return commandByte;
        }
    }

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

    public void setMinRadioFreq(float newMinFreq) {
        minRadioFreq = newMinFreq;

        // Detect if we're moving from VHF to UHF, and move fix active frequency to within band.
        if (activeFrequencyStr != null && Float.parseFloat(activeFrequencyStr) < minRadioFreq) {
            tuneToFreq(String.format(java.util.Locale.US, "%.4f", UHF_MIN_FREQ_US), squelch, true);
            callbacks.forceTunedToFreq(activeFrequencyStr);
        }
    }

    public void setMaxRadioFreq(float newMaxFreq) {
        maxRadioFreq = newMaxFreq;

        // Detect if we're moving from UHF to VHF, and move fix active frequency to within band.
        if (activeFrequencyStr != null && Float.parseFloat(activeFrequencyStr) > maxRadioFreq) {
            tuneToFreq(String.format(java.util.Locale.US, "%.4f", VHF_MIN_FREQ_US), squelch, true);
            callbacks.forceTunedToFreq(activeFrequencyStr);
        }
    }

    public void setMinFreq(float newMinFreq) {
        minHamFreq = newMinFreq;
    }

    public void setMaxFreq(float newMaxFreq) {
        maxHamFreq = newMaxFreq;
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
                    aprsBeaconHandler.postDelayed(this,  60 * APRS_BEACON_MINS * 1000);
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
                sendCommandToESP32(RadioAudioService.ESP32Command.STOP);
                audioTrack.stop();
                usbIoManager.stop();
                break;
            default:
                if (null != usbIoManager && usbIoManager.getState() == SerialInputOutputManager.State.STOPPED) {
                    usbIoManager.start();
                }
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

        threadPoolExecutor = new ThreadPoolExecutor(2,
                10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        messageNumber = (int) (Math.random() * APRS_MAX_MESSAGE_NUM); // Start with any Message # from 0-99999, we'll increment it by 1 each tx until restart.
    }

    /**
     * Bound activities should call this when they're done providing any data (via setters),
     * including the several necessary callback handlers.
     */
    public void start() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        createNotificationChannels();
        findESP32Device();
        initAudioTrack();
        setupTones();
        initAFSKModem();
    }

    /**
     * This must be set before any method that requires channels (like scanning or tuning to a
     * memory) is access, or they will just report an error. And it should also be called whenever
     * the active memories have changed (e.g. user selected a different memory group).
     */
    public void setChannelMemories(LiveData<List<ChannelMemory>> channelMemoriesLiveData) {
        this.channelMemoriesLiveData = channelMemoriesLiveData;
    }

    public interface RadioAudioServiceCallbacks {
        public void radioMissing();
        public void radioConnected();
        public void radioModuleNotFound();
        public void audioTrackCreated();
        public void packetReceived(APRSPacket aprsPacket);
        public void scannedToMemory(int memoryId);
        public void outdatedFirmware(int firmwareVer);
        public void missingFirmware();
        public void txAllowed(boolean allowed);
        public void txStarted();
        public void txEnded();
        public void chatError(String snackbarText);
        public void sMeterUpdate(int value);
        public void aprsBeaconing(boolean beaconing, int accuracy);
        public void sentAprsBeacon(double latitude, double longitude);
        public void unknownLocation();
        public void forceTunedToFreq(String newFreqStr);
    }

    public void setCallbacks(RadioAudioServiceCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        if (threadPoolExecutor != null) {
            threadPoolExecutor.shutdownNow();
            threadPoolExecutor = null;
        }
    }

    private void setupTones() {
        mTones.put("None", 0);
        mTones.put("67", 1);
        mTones.put("71.9", 2);
        mTones.put("74.4", 3);
        mTones.put("77", 4);
        mTones.put("79.7", 5);
        mTones.put("82.5", 6);
        mTones.put("85.4", 7);
        mTones.put("88.5", 8);
        mTones.put("91.5", 9);
        mTones.put("94.8", 10);
        mTones.put("97.4", 11);
        mTones.put("100", 12);
        mTones.put("103.5", 13);
        mTones.put("107.2", 14);
        mTones.put("110.9", 15);
        mTones.put("114.8", 16);
        mTones.put("118.8", 17);
        mTones.put("123", 18);
        mTones.put("127.3", 19);
        mTones.put("131.8", 20);
        mTones.put("136.5", 21);
        mTones.put("141.3", 22);
        mTones.put("146.2", 23);
        mTones.put("151.4", 24);
        mTones.put("156.7", 25);
        mTones.put("162.2", 26);
        mTones.put("167.9", 27);
        mTones.put("173.8", 28);
        mTones.put("179.9", 29);
        mTones.put("186.2", 30);
        mTones.put("192.8", 31);
        mTones.put("203.5", 32);
        mTones.put("210.7", 33);
        mTones.put("218.1", 34);
        mTones.put("225.7", 35);
        mTones.put("233.6", 36);
        mTones.put("241.8", 37);
        mTones.put("250.3", 38);
    }

    private void createNotificationChannels() {
        // Notification channel for APRS text chat messages
        NotificationChannel channel = new NotificationChannel(MESSAGE_NOTIFICATION_CHANNEL_ID,
                "Chat messages", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("APRS text chat messages addressed to you");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private void restartAudioPrebuffer() {
        prebufferComplete = false;
        rxPrebufferIdx = 0;
    }

    private void setRadioFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        sendCommandToESP32(ESP32Command.FILTERS, (emphasis ? "1" : "0") + (highpass ? "1" : "0") + (lowpass ? "1" : "0"));
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
            sendCommandToESP32(ESP32Command.TUNE_TO, makeSafe2MFreq(activeFrequencyStr) +
                    makeSafe2MFreq(activeFrequencyStr) + "0000" + squelchLevel +
                    (bandwidth.equals("Wide") ? "W" : "N"));
        }

        // Reset audio prebuffer
        restartAudioPrebuffer();

        try {
            Float freq = Float.parseFloat(makeSafe2MFreq(activeFrequencyStr));
            Float offsetMaxFreq = maxHamFreq - (bandwidth.equals("W") ? 0.025f : 0.0125f);
            if (freq < minHamFreq|| freq > offsetMaxFreq) {
                txAllowed = false;
            } else {
                txAllowed = true;
            }
            callbacks.txAllowed(txAllowed);
        } catch (NumberFormatException nfe) {
        }
    }

    public static String makeSafe2MFreq(String strFreq) {
        Float freq;
        try {
            freq = Float.parseFloat(strFreq);
        } catch (NumberFormatException nfe) {
            return String.format(java.util.Locale.US, "%.4f", VHF_MIN_FREQ_US); // 4 decimal places, in MHz
        }
        while (freq > 500.0f) { // Handle cases where user inputted "1467" or "14670" but meant "146.7".
            freq /= 10;
        }

        if (freq < minRadioFreq) {
            freq = minRadioFreq; // Lowest freq supported by radio module
        } else if (freq > maxRadioFreq) {
            freq = maxRadioFreq; // Highest freq supported
        }

        strFreq = String.format(java.util.Locale.US,"%.4f", freq);

        return strFreq;
    }

    public String validateFrequency(String tempFrequency) {
        String newFrequency = makeSafe2MFreq(tempFrequency);

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

        if (serialPort != null) {
            sendCommandToESP32(ESP32Command.TUNE_TO,
                    getTxFreq(memory.frequency, memory.offset, memory.offsetKhz) + makeSafe2MFreq(memory.frequency) +
                            getToneIdxStr(memory.txTone) + getToneIdxStr(memory.rxTone) + squelchLevel +
                            (bandwidth.equals("Wide") ? "W" : "N"));
        }

        // Reset audio prebuffer
        restartAudioPrebuffer();

        try {
            Float txFreq = Float.parseFloat(getTxFreq(memory.frequency, memory.offset, memory.offsetKhz));
            Float offsetMaxFreq = maxHamFreq - (bandwidth.equals("W") ? 0.025f : 0.0125f);
            if (txFreq < minHamFreq || txFreq > offsetMaxFreq) {
                txAllowed = false;
            } else {
                txAllowed = true;
            }
            callbacks.txAllowed(txAllowed);
        } catch (NumberFormatException nfe) {
        }
    }

    private String getToneIdxStr(String toneStr) {
        if (toneStr == null) {
            toneStr = "None";
        }

        Integer toneIdx = mTones.get(toneStr);

        return toneIdx < 10 ? "0" + toneIdx : toneIdx.toString();
    }

    private String getTxFreq(String txFreq, int offset, int khz) {
        if (offset == ChannelMemory.OFFSET_NONE) {
            return txFreq;
        } else {
            Float freqFloat = Float.parseFloat(txFreq);
            if (offset == ChannelMemory.OFFSET_UP) {
                freqFloat += 0f + (khz / 1000f);
            } else if (offset == ChannelMemory.OFFSET_DOWN){
                freqFloat -= 0f + (khz / 1000f);
            }
            return makeSafe2MFreq(freqFloat.toString());
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
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBufferSize)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build();
        audioTrack.setAuxEffectSendLevel(0.0f);

        restartAudioPrebuffer();

        if (callbacks != null) {
            callbacks.audioTrackCreated();
        }
    }

    public void startPtt() {
        if (!txAllowed) { // Extra precauation, though MainActivity should enforce this.
            Log.d("DEBUG", "Warning: Attempted startPtt when txAllowed was false (should not happen).");
            new Throwable().printStackTrace();
            return;
        }

        setMode(MODE_TX);

        if (null != callbacks) {
            callbacks.sMeterUpdate(0);
        }

        // Setup runaway tx safety measures.
        startTxTimeSec = System.currentTimeMillis() / 1000;
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(RUNAWAY_TX_TIMEOUT_SEC * 1000);

                    if (mode != MODE_TX) {
                        return;
                    }

                    long elapsedSec = (System.currentTimeMillis() / 1000) - startTxTimeSec;
                    if (elapsedSec > RUNAWAY_TX_TIMEOUT_SEC) { // Check this because multiple tx may have happened with RUNAWAY_TX_TIMEOUT_SEC.
                        Log.d("DEBUG", "Warning: runaway tx timeout reached, PTT stopped.");
                        endPtt();
                    }
                } catch (InterruptedException e) {
                }
            }
        });

        sendCommandToESP32(ESP32Command.PTT_DOWN);
        audioTrack.stop();
        callbacks.txStarted();
    }

    public void endPtt() {
        if (mode == MODE_RX) {
            return;
        }
        setMode(MODE_RX);

        // Hold off on telling the ESP32 firmware to PTT_UP, because we want the last bit of
        // tx audio to be transmitted first (it's stuck in buffers).
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendCommandToESP32(ESP32Command.PTT_UP);
                audioTrack.flush();
                restartAudioPrebuffer();
                callbacks.txEnded();
            }
        }, MS_FOR_FINAL_TX_AUDIO_BEFORE_PTT_UP);
    }

    public void reconnectViaUSB() {
        findESP32Device();
    }

    private void findESP32Device() {
        Log.d("DEBUG", "findESP32Device()");

        setMode(MODE_STARTUP);
        esp32Device = null;

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
            if (callbacks != null) {
                callbacks.radioMissing();
            }
        } else {
            Log.d("DEBUG", "Found ESP32.");
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
            if (callbacks != null) {
                callbacks.radioMissing();
            }
            return;
        }

        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            Log.d("DEBUG", "Error: couldn't open USB device.");
            if (callbacks != null) {
                callbacks.radioMissing();
            }
            return;
        }

        serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        Log.d("DEBUG", "serialPort: " + serialPort);
        try {
            serialPort.open(connection);
            serialPort.setParameters(230400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (Exception e) {
            Log.d("DEBUG", "Error: couldn't open USB serial port.");
            if (callbacks != null) {
                callbacks.radioMissing();
            }
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
                handleESP32Data(data);
            }

            @Override
            public void onRunError(Exception e) {
                Log.d("DEBUG", "Error reading from ESP32.");
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
        usbIoManager.setReadTimeout(1000); // Must not be 0 (infinite) or it may block on read() until a write() occurs.
        usbIoManager.start();
        checkedFirmwareVersion = false;

        Log.d("DEBUG", "Connected to ESP32.");

        // After a brief pause (to let it boot), do things with the ESP32 that we were waiting to do.
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!checkedFirmwareVersion) {
                    checkFirmwareVersion();
                }
            }
        }, 3000);
    }

    /**
     * @param radioType should be RADIO_TYPE_UHF or RADIO_TYPE_VHF
     */
    public void setRadioType(String radioType) {
        if (!this.radioType.equals(radioType)) {
            this.radioType = radioType;

            // Ensure frequencies we're using match the radioType
            if (radioType.equals(RADIO_MODULE_VHF)) {
                setMinRadioFreq(VHF_MIN_FREQ);
                setMinFreq(VHF_MIN_FREQ_US);
                setMaxFreq(VHF_MAX_FREQ_US);
                setMaxRadioFreq(VHF_MAX_FREQ);
            } else if (radioType.equals(RADIO_MODULE_UHF)) {
                setMinRadioFreq(UHF_MIN_FREQ);
                setMinFreq(UHF_MIN_FREQ_US);
                setMaxFreq(UHF_MAX_FREQ_US);
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
        sendCommandToESP32(ESP32Command.STOP); // Tell ESP32 app to stop whatever it's doing.
        sendCommandToESP32(ESP32Command.GET_FIRMWARE_VER); // Ask for firmware ver.
        Log.d("DEBUG", "Telling firmware that radio is: " + (radioType.equals(RADIO_MODULE_UHF) ? "UHF" : "VHF"));
        sendBytesToESP32(radioType.getBytes());
        // The version is actually evaluated in handleESP32Data().

        // If we don't hear back from the ESP32, it means the firmware is either not
        // installed or it's somehow corrupt.
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mode != MODE_STARTUP || radioModuleNotFound) {
                    return;
                } else {
                    Log.d("DEBUG", "Error: Did not hear back from ESP32 after requesting its firmware version. Offering to flash.");
                    callbacks.missingFirmware();
                    setMode(MODE_BAD_FIRMWARE);
                }
            }
        }, 6000);
    }

    private void initAfterESP32Connected() {
        setMode(MODE_RX);

        // Start by prebuffering some audio
        restartAudioPrebuffer();

        // Turn off scanning if it was on (e.g. if radio was unplugged briefly and reconnected)
        setScanning(false);

        if (callbacks != null) {
            callbacks.radioConnected();
        }
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

            // If not marked as skipped, we tune to it and return.
            if (!candidate.skipDuringScan) {
                // Reset silence since we found an active memory.
                consecutiveSilenceBytes = 0;

                // If squelch is off (0), use squelch=1 during scanning.
                tuneToMemory(candidate, squelch > 0 ? squelch : 1, true);

                if (callbacks != null) {
                    callbacks.scannedToMemory(candidate.memoryId);
                }
                return;
            }

            // Otherwise, move on to the next memory in the list.
            nextIndex = (nextIndex + 1) % channelMemories.size();

            // Repeat until we loop back to the first tried index.
        } while (nextIndex != firstTriedIndex);

        // If we reach here, all memories are marked skipDuringScan.
        Log.d("DEBUG", "Warning: All memories are skipDuringScan, no next memory found to scan to.");
    }

    private byte[] applyMicGain(byte[] audioBuffer) {
        if (micGainBoost == MicGainBoost.NONE) {
            return audioBuffer; // No gain, just return original
        }

        byte[] newAudioBuffer = new byte[audioBuffer.length];
        float gain = MicGainBoost.toFloat(micGainBoost);

        for (int i = 0; i < audioBuffer.length; i++) {
            // Convert from [0..255] to [-128..127]
            int signedSample = (audioBuffer[i] & 0xFF) - 128;

            // Apply gain
            signedSample = (int)(signedSample * gain);

            // Clip to [-128..127]
            signedSample = Math.min(127, signedSample);
            signedSample = Math.max(-128, signedSample);

            // Convert back to [0..255]
            signedSample += 128;

            // Store in the new buffer
            newAudioBuffer[i] = (byte) signedSample;
        }

        return newAudioBuffer;
    }

    public void sendAudioToESP32(byte[] audioBuffer, boolean dataMode) {
        if (!dataMode) {
            audioBuffer = applyMicGain(audioBuffer);
        }

        if (audioBuffer.length <= TX_AUDIO_CHUNK_SIZE) {
            sendBytesToESP32(audioBuffer);
        } else {
            // If the audio is fairly long, we need to send it to ESP32 at the same rate
            // as audio sampling. Otherwise, we'll overwhelm its DAC buffer and some audio will
            // be lost.
            final Handler handler = new Handler(Looper.getMainLooper());
            final float msToSendOneChunk = (float) TX_AUDIO_CHUNK_SIZE / (float) AUDIO_SAMPLE_RATE * 1000f;
            float nextSendDelay = 0f;
            byte[] finalAudioBuffer = audioBuffer;
            for (int i = 0; i < audioBuffer.length; i += TX_AUDIO_CHUNK_SIZE) {
                final int chunkStart = i;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_BACKGROUND +
                                        android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
                        sendBytesToESP32(Arrays.copyOfRange(finalAudioBuffer, chunkStart,
                                Math.min(finalAudioBuffer.length, chunkStart + TX_AUDIO_CHUNK_SIZE)));
                    }
                }, (int) nextSendDelay);

                nextSendDelay += msToSendOneChunk;
            }

            // In data mode, also schedule PTT up after last audio chunk goes out.
            if (dataMode) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_BACKGROUND +
                                        android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
                        endPtt();
                    }
                }, (int) nextSendDelay);
            }
        }
    }

    public void sendCommandToESP32(ESP32Command command) {
        byte[] commandArray = { COMMAND_DELIMITER[0], COMMAND_DELIMITER[1],
                COMMAND_DELIMITER[2], COMMAND_DELIMITER[3], COMMAND_DELIMITER[4], COMMAND_DELIMITER[5],
                COMMAND_DELIMITER[6], COMMAND_DELIMITER[7], command.getByte() };
        sendBytesToESP32(commandArray);
        Log.d("DEBUG", "Sent command: " + command);
    }

    public void sendCommandToESP32(ESP32Command command, String paramsStr) {
        byte[] commandArray = { COMMAND_DELIMITER[0], COMMAND_DELIMITER[1],
                COMMAND_DELIMITER[2], COMMAND_DELIMITER[3], COMMAND_DELIMITER[4], COMMAND_DELIMITER[5],
                COMMAND_DELIMITER[6], COMMAND_DELIMITER[7], command.getByte() };
        byte[] combined = new byte[commandArray.length + paramsStr.length()];
        ByteBuffer buffer = ByteBuffer.wrap(combined);
        buffer.put(commandArray);
        buffer.put(paramsStr.getBytes(StandardCharsets.US_ASCII));
        combined = buffer.array();

        // Write it in a single call so the params have a better chance (?) to fit in receive buffer on mcu.
        // A little concerned there could be a bug here in rare chance that these bytes span receive
        // buffer size on mcu.
        // TODO implement a more robust way (in mcu code) of ensuring params are received by mcu
        sendBytesToESP32(combined);
        Log.d("DEBUG", "Sent command: " + command + " params: " + paramsStr);
    }

    public synchronized void sendBytesToESP32(byte[] newBytes) {
        if (mode == MODE_BAD_FIRMWARE) {
            Log.d("DEBUG", "Warning: Attempted to send bytes to ESP32 with bad firmware.");
            return;
        }

        if (mode == MODE_FLASHING) {
            Log.d("DEBUG", "Warning: Attempted to send bytes to ESP32 while in the process of flashing a new firmware.");
            return;
        }

        int usbRetries = 0;
        try {
            // usbIoManager.writeAsync(newBytes); // On MCUs like the ESP32 S2 this causes USB failures with concurrent USB rx/tx.
            int bytesWritten = 0;
            int totalBytes = newBytes.length;
            final int MAX_BYTES_PER_USB_WRITE = 128;
            do {
                try {
                    byte[] arrayPart = Arrays.copyOfRange(newBytes, bytesWritten, Math.min(bytesWritten + MAX_BYTES_PER_USB_WRITE, totalBytes));
                    serialPort.write(arrayPart, 200);
                    bytesWritten += MAX_BYTES_PER_USB_WRITE;
                    usbRetries = 0;
                } catch (SerialTimeoutException ste) {
                    // Do nothing, we'll try again momentarily. ESP32's serial buffer may be full.
                    usbRetries++;
                    Log.d("DEBUG", "usbRetries: " + usbRetries);
                }
            } while (bytesWritten < totalBytes && usbRetries < 10);
            // Log.d("DEBUG", "Wrote data: " + Arrays.toString(newBytes));
        } catch (Exception e) {
            e.printStackTrace();
            try {
                serialPort.close();
            } catch (Exception ex) {
                // Ignore. We did our best to close it!
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                // Ignore. This should only happen if the app is paused in this brief moment between USB retries, not a serious issue.
            }
            findESP32Device(); // Attempt to reconnect after the brief pause above.
        }
        if (usbRetries == 10) {
            Log.d("DEBUG", "sendBytesToESP32: Connected to ESP32 via USB serial, but could not send data after 10 retries.");
        }
    }

    public static UsbSerialPort getUsbSerialPort() {
        return serialPort;
    }

    private void handleESP32Data(byte[] data) {
        // Log.d("DEBUG", "Got bytes from ESP32: " + Arrays.toString(data));
         /* try {
            String dataStr = new String(data, "UTF-8");
            if (dataStr.length() < 100 && dataStr.length() > 0)
                Log.d("DEBUG", "Str data from ESP32: " + dataStr);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            } */
        // Log.d("DEBUG", "Num bytes from ESP32: " + data.length);

        if (mode == MODE_STARTUP) {
            try {
                // TODO rework this to use same command-handling as s-meter updates (below)
                String dataStr = new String(data, "UTF-8");
                versionStrBuffer += dataStr;
                if (versionStrBuffer.contains(VERSION_PREFIX)) {
                    int startIdx = versionStrBuffer.indexOf(VERSION_PREFIX) + VERSION_PREFIX.length();
                    String verStr = "";
                    try {
                        verStr = versionStrBuffer.substring(startIdx, startIdx + VERSION_LENGTH);
                    } catch (IndexOutOfBoundsException iobe) {
                        return; // Version string not yet fully received.
                    }
                    int verInt = Integer.parseInt(verStr);
                    if (verInt < FirmwareUtils.PACKAGED_FIRMWARE_VER) {
                        Log.d("DEBUG", "Error: ESP32 app firmware " + verInt + " is older than latest firmware " + FirmwareUtils.PACKAGED_FIRMWARE_VER);
                        if (callbacks != null) {
                            callbacks.outdatedFirmware(verInt);
                            versionStrBuffer = "";
                        }
                    } else {
                        Log.d("DEBUG", "Recent ESP32 app firmware version detected (" + verInt + ").");

                        String radioStatusStr = versionStrBuffer.substring(startIdx + VERSION_LENGTH, startIdx + VERSION_LENGTH + 1);
                        Log.d("DEBUG", "Radio status: '" + radioStatusStr + "'");

                        if (radioStatusStr.equals(RADIO_MODULE_NOT_FOUND)) {
                            radioModuleNotFound = true;
                        } else if (radioStatusStr.equals(RADIO_MODULE_FOUND)) {
                            radioModuleNotFound = false;
                        } else {
                            Log.d("DEBUG", "Error: unexpected radio status received '" + radioStatusStr + "'");
                        }

                        versionStrBuffer = ""; // Reset the version string buffer for next USB reconnect.

                        if (radioModuleNotFound) {
                            callbacks.radioModuleNotFound();
                            return;
                        }

                        initAfterESP32Connected();
                    }
                    return;
                }
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        if (mode == MODE_RX || mode == MODE_SCAN) {
            // Handle and remove any commands (e.g. S-meter updates) embedded in the audio.
            data = extractAudioAndHandleCommands(data);

            if (prebufferComplete && audioTrack != null) {
                synchronized (audioTrack) {
                    if (afskDemodulator != null) { // Avoid race condition at app start.
                        // Play the audio.
                        audioTrack.write(data, 0, data.length);

                        // Add the audio samples to the AFSK demodulator.
                        float[] audioAsFloats = convertPCM8ToFloatArray(data);
                        afskDemodulator.addSamples(audioAsFloats, audioAsFloats.length);
                    }

                    if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.play();
                    }
                }
            } else {
                for (int i = 0; i < data.length; i++) {
                    // Prebuffer the incoming audio data so AudioTrack doesn't run out of audio to play
                    // while we're waiting for more bytes.
                    rxBytesPrebuffer[rxPrebufferIdx++] = data[i];
                    if (rxPrebufferIdx == PRE_BUFFER_SIZE) {
                        prebufferComplete = true;
                        // Log.d("DEBUG", "Rx prebuffer full, writing to audioTrack.");
                        if (audioTrack != null) {
                            if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack.play();
                            }
                            synchronized (audioTrack) {
                                audioTrack.write(rxBytesPrebuffer, 0, PRE_BUFFER_SIZE);
                            }
                        }

                        rxPrebufferIdx = 0;
                        break; // Might drop a few audio bytes from data[], should be very minimal
                    }
                }
            }
        }

        if (mode == MODE_SCAN) {
            // Track consecutive silent bytes, so if we're scanning we can move to next after a while.
            for (int i = 0; i < data.length; i++) {
                if (data[i] == SILENT_BYTE) {
                    consecutiveSilenceBytes++;
                    // Log.d("DEBUG", "consecutiveSilenceBytes: " + consecutiveSilenceBytes);
                    checkScanDueToSilence();
                } else {
                    consecutiveSilenceBytes = 0;
                }
            }
        } else if (mode == MODE_TX) {
            // Print any data we get in MODE_TX (we're not expecting any, this is either leftover rx bytes or debug info).
            /* try {
                String dataStr = new String(data, "UTF-8");
                Log.d("DEBUG", "Unexpected data from ESP32 during MODE_TX: " + dataStr);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            } */
        }

        if (mode == MODE_BAD_FIRMWARE) {
            // Log.d("DEBUG", "Warning: Received data from ESP32 which was thought to have bad firmware.");
            // Just ignore any data we get in this mode, who knows what is programmed on the ESP32.
        }
    }

    private synchronized byte[] extractAudioAndHandleCommands(byte[] newData) {
        // 1. Append the new data to leftover.
        leftoverBuffer.write(newData, 0, newData.length);
        byte[] buffer = leftoverBuffer.toByteArray();

        ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
        int parsePos = 0;

        while (true) {
            int startDelim = indexOf(buffer, COMMAND_DELIMITER, parsePos);
            if (startDelim == -1) {
                // -- NO FULL DELIMITER FOUND IN [buffer] STARTING AT parsePos --

                // We might have a *partial* delimiter at the tail of [buffer].
                // Figure out how many trailing bytes might match the start of the next command.
                int partialLen = findPartialDelimiterTail(buffer, parsePos, buffer.length);

                // "pureAudioEnd" is where pure audio stops and partial leftover begins.
                int pureAudioEnd = buffer.length - partialLen;

                // Write the "definitely audio" portion to our output.
                if (pureAudioEnd > parsePos) {
                    audioOut.write(buffer, parsePos, pureAudioEnd - parsePos);
                }

                // Store ONLY the partial leftover so we can complete the delimiter/command next time.
                leftoverBuffer.reset();
                if (partialLen > 0) {
                    leftoverBuffer.write(buffer, pureAudioEnd, partialLen);
                }

                // Return everything we've decoded as audio so far.
                return audioOut.toByteArray();
            }

            // -- FOUND A DELIMITER --
            // Everything from parsePos..(startDelim) is audio
            if (startDelim > parsePos) {
                audioOut.write(buffer, parsePos, startDelim - parsePos);
            }

            // Check if we have enough bytes for "delimiter + cmd + paramLen"
            int neededBeforeParams = COMMAND_DELIMITER.length + 2;
            // (1 for cmd byte, 1 for paramLen byte)
            if (startDelim + neededBeforeParams > buffer.length) {
                // Not enough data => partial command leftover
                storeTailForNextTime(buffer, startDelim);
                return audioOut.toByteArray();
            }

            int cmdPos   = startDelim + COMMAND_DELIMITER.length;
            byte cmd     = buffer[cmdPos];
            int paramLen = (buffer[cmdPos + 1] & 0xFF);
            int paramStart = cmdPos + 2;
            int paramEnd   = paramStart + paramLen; // one past the last param byte

            if (paramEnd > buffer.length) {
                // Again, partial command leftover
                storeTailForNextTime(buffer, startDelim);
                return audioOut.toByteArray();
            }

            // We have a full command => handle it
            byte[] param = Arrays.copyOfRange(buffer, paramStart, paramEnd);
            handleParsedCommand(cmd, param);

            // Advance parsePos beyond this entire command block
            parsePos = paramEnd;
        }
    }

    /**
     * Stores the tail of 'buffer' from 'startIndex' to end into leftoverBuffer,
     * for the next invocation of extractAudioAndHandleCommands().
     */
    private void storeTailForNextTime(byte[] buffer, int startIndex) {
        leftoverBuffer.reset();
        leftoverBuffer.write(buffer, startIndex, buffer.length - startIndex);
    }

    /**
     * Finds the first occurrence of 'pattern' in 'data' at or after 'start'.
     * Returns -1 if not found.
     */
    private int indexOf(byte[] data, byte[] pattern, int start) {
        if (pattern.length == 0 || start >= data.length) {
            return -1;
        }
        for (int i = start; i <= data.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Checks how many trailing bytes in [data, from parsePos..end) might match the
     * *start* of our delimiter (or partial command).
     *
     * For example, if COMMAND_DELIMITER = { (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00 },
     * we see if the tail ends with 1, 2, or 3 bytes that match the first 1, 2, or 3 bytes
     * of COMMAND_DELIMITER.
     *
     * Return value: the number of trailing bytes that match
     * (range 0..COMMAND_DELIMITER.length - 1).
     */
    private int findPartialDelimiterTail(byte[] data, int start, int end) {
        final int dataLen = end - start;
        // We'll check from the largest possible partial (delimiter.length - 1) down to 1
        // because if a bigger partial matches, that's our answer.
        for (int checkSize = COMMAND_DELIMITER.length - 1; checkSize >= 1; checkSize--) {
            if (checkSize > dataLen) {
                continue; // can't match if leftover is too small
            }
            boolean match = true;
            // Compare data[end-checkSize .. end-1] to delimiter[0..checkSize-1]
            for (int j = 0; j < checkSize; j++) {
                if (data[end - checkSize + j] != COMMAND_DELIMITER[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                // We found the largest partial match
                return checkSize;
            }
        }
        // If no partial match, return 0
        return 0;
    }

    private void handleParsedCommand(byte cmd, byte[] param) {
        if (cmd == COMMAND_SMETER_REPORT) {
            if (param.length >= 1) {
                int sMeter255Value = (param[0] & 0xFF);
                // Log.d("DEBUG", "Raw s-meter value from ESP32 (0-255) = " + sMeter255Value);

                // Through empirical testing, it seems to scale from ~50 with no signal, and ~120 with a transmitter
                // right near it. So we normalize to match that to an S1-S9 scale. Note, we start more granular at
                // lower S-values so people can get a better sense of weak signals. This isn't a scientific db measurement...
                int sMeter9Value = 1;
                if (sMeter255Value >= 46) {
                    sMeter9Value = 2;
                }
                if (sMeter255Value >= 50) {
                    sMeter9Value = 3;
                }
                if (sMeter255Value >= 55) {
                    sMeter9Value = 4;
                }
                if (sMeter255Value >= 61) {
                    sMeter9Value = 5;
                }
                if (sMeter255Value >= 68) {
                    sMeter9Value = 6;
                }
                if (sMeter255Value >= 76) {
                    sMeter9Value = 7;
                }
                if (sMeter255Value >= 87) {
                    sMeter9Value = 8;
                }
                if (sMeter255Value >= 101) {
                    sMeter9Value = 9;
                }
                // Log.d("DEBUG", "Normalized s-meter (0-9) = " + sMeter9Value);

                callbacks.sMeterUpdate(sMeter9Value);
            }
        } else {
            Log.d("DEBUG", "Unknown cmd received from ESP32: 0x" + Integer.toHexString(cmd & 0xFF) +
                    " paramLen=" + param.length);
        }
    }

    private float[] convertPCM8ToFloatArray(byte[] pcm8Data) {
        // Create a float array of the same length as the input byte array
        float[] floatData = new float[pcm8Data.length];

        // Iterate through the byte array and convert each sample
        for (int i = 0; i < pcm8Data.length; i++) {
            // Convert unsigned 8-bit PCM to signed 8-bit value
            int signedValue = (pcm8Data[i] & 0xFF) - 128;

            // Normalize the signed 8-bit value to the range [-1.0, 1.0]
            floatData[i] = signedValue / 128.0f;
        }

        return floatData;
    }

    private byte convertFloatToPCM8(float floatValue) {
        // Clamp the float value to the range [-1.0, 1.0] to prevent overflow
        float clampedValue = Math.max(-1.0f, Math.min(1.0f, floatValue));

        // Convert float value in range [-1.0, 1.0] to signed 8-bit value
        int signedValue = Math.round(clampedValue * 128);

        // Convert signed 8-bit value to unsigned 8-bit PCM (range 0 to 255)
        return (byte) (signedValue + 128);
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
                if (callbacks != null) {
                    callbacks.packetReceived(aprsPacket);
                }
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
        if (getMode() != MODE_RX) { // Can only beacon in rx mode (e.g. not tx or scan)
            Log.d("DEBUG", "Skipping position beacon because not in RX mode");
            return;
        }

        LocationManager lm = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        Location location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); // Try to get cached location (fast)

        if (location == null) {
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
            return;
        }

        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        sendPositionBeacon(latitude, longitude);
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

    private void txAX25Packet(Packet ax25Packet) {
        if (!txAllowed) {
            Log.d("DEBUG", "Tried to send an AX.25 packet when tx is not allowed, did not send.");
            return;
        }

        Log.d("DEBUG", "Sending AX25 packet: " + ax25Packet.toString());

        // This strange approach to getting bytes seems to be a state machine in the AFSK library.
        afskModulator.prepareToTransmit(ax25Packet);
        float[] txSamples = afskModulator.getTxSamplesBuffer();
        int n;
        ArrayList<Byte> audioBytes = new ArrayList<Byte>();
        while ((n = afskModulator.getSamples()) > 0) {
            for (int i = 0; i < n; i++) {
                byte audioByte = convertFloatToPCM8(txSamples[i]);
                audioBytes.add(audioByte);
            }
        }
        byte[] simpleAudioBytes = ArrayUtils.toPrimitive(audioBytes.toArray(new Byte[0]));

        startPtt();
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Add some silence before and after the data.
                int bytesOfLeadInDelay = (AUDIO_SAMPLE_RATE / 1000 * MS_SILENCE_BEFORE_DATA);
                int bytesOfTailDelay = (AUDIO_SAMPLE_RATE / 1000 * MS_SILENCE_AFTER_DATA);
                byte[] combinedAudio = new byte[bytesOfLeadInDelay + simpleAudioBytes.length + bytesOfTailDelay];
                for (int i = 0; i < bytesOfLeadInDelay; i++) {
                    combinedAudio[i] = SILENT_BYTE;
                }
                for (int i = 0; i < simpleAudioBytes.length; i++) {
                    combinedAudio[i + bytesOfLeadInDelay] = simpleAudioBytes[i];
                }
                for (int i = (bytesOfLeadInDelay + simpleAudioBytes.length); i < combinedAudio.length; i++) {
                    combinedAudio[i] = SILENT_BYTE;
                }

                sendAudioToESP32(combinedAudio, true);
            }
        }, MS_DELAY_BEFORE_DATA_XMIT);
    }

    public AudioTrack getAudioTrack() {
        return audioTrack;
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