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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.usb.UsbManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.vagell.kv4pht.BR;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.APRSTypes;
import com.vagell.kv4pht.aprs.parser.InformationField;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.ObjectField;
import com.vagell.kv4pht.aprs.parser.PositionField;
import com.vagell.kv4pht.aprs.parser.Utilities;
import com.vagell.kv4pht.aprs.parser.WeatherField;
import com.vagell.kv4pht.data.APRSMessage;
import com.vagell.kv4pht.data.AppSetting;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.databinding.ActivityMainBinding;
import com.vagell.kv4pht.radio.RadioAudioService;
import com.vagell.kv4pht.radio.RadioModuleController;
import com.vagell.kv4pht.radio.RadioMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.google.android.material.snackbar.Snackbar.LENGTH_LONG;
import static com.vagell.kv4pht.radio.RadioAudioService.INTENT_OPEN_CHAT;

public class MainActivity extends AppCompatActivity {
    // For transmitting audio to ESP32 / radio
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private int minBufferSize = Math.max(
        AudioRecord.getMinBufferSize(RadioAudioService.AUDIO_SAMPLE_RATE, channelConfig, audioFormat),
        RadioAudioService.AUDIO_FRAME_SAMPLES * 2);

    private Thread recordingThread;

    private final Handler pttButtonDebounceHandler = new Handler(Looper.getMainLooper());

    // Active screen type (e.g. voice or chat)
    private ScreenType activeScreenType = ScreenType.SCREEN_VOICE;

    // Snackbars
    private Snackbar usbSnackbar = null;
    private Snackbar callsignSnackbar = null;
    private Snackbar versionSnackbar = null;
    private Snackbar radioModuleNotFoundSnackbar = null;

    // Android permission stuff
    private static final int REQUEST_ALL_PERMISSIONS = 1001;
    @Nullable private Consumer<Boolean> pendingGrantCallback = null;
    @Nullable private List<String> pendingPerms;

    private static final String ACTION_USB_PERMISSION = "com.vagell.kv4pht.USB_PERMISSION";

    // Radio params and related settings
    private String activeFrequencyStr = "0.0000";
    private String callsign = null;
    private boolean stickyPTT = false;
    private boolean disableAnimations = false;

    // Activity callback values
    public static final int REQUEST_ADD_MEMORY = 0;
    public static final int REQUEST_EDIT_MEMORY = 1;
    public static final int REQUEST_SETTINGS = 2;
    public static final int REQUEST_FIRMWARE = 3;
    public static final int REQUEST_FIND_REPEATERS = 4;

    private MainViewModel viewModel;
    private RecyclerView memoriesRecyclerView;
    private MemoriesAdapter memoriesAdapter;
    private RecyclerView aprsRecyclerView;
    private APRSAdapter aprsAdapter;

    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2, 10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

    private String selectedMemoryGroup = null; // null means unfiltered, no group selected
    private int activeMemoryId = -1; // -1 means we're in simplex mode

    // Tx audio visualizer constants
    private static int MAX_AUDIO_VIZ_SIZE = 500;
    private static int MIN_TX_AUDIO_VIZ_SIZE = 200;
    private static int RECORD_ANIM_FPS = 30;

    // The main service that handles USB with the ESP32, incoming and outgoing audio, data, etc.
    private RadioAudioService radioAudioService = null;
    private boolean radioAudioServiceBound = false;
    private final AtomicBoolean bindingInProgress = new AtomicBoolean(false);

    private boolean pendingInitialRadioUiSync = false;
    private boolean initialRadioUiSynced = false;

    // This receiver will listen for a broadcast from the RadioAudioService when it's shutting down
    // (this is so that when the user swipes-away the kv4p HT notification, the app closes).
    private final BroadcastReceiver serviceShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (RadioAudioService.ACTION_SERVICE_STOPPING.equals(intent.getAction())) {
                if (radioAudioServiceBound) {
                    unbindService(connection);
                    radioAudioServiceBound = false;
                }
                finish();
            }
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Bind data to the UI via the MainViewModel class
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setLifecycleOwner(this);
        binding.setVariable(BR.viewModel, viewModel);

        // Prepare a RecyclerView for the list of channel memories
        memoriesRecyclerView = findViewById(R.id.memoriesList);
        memoriesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        memoriesAdapter = new MemoriesAdapter(new MemoriesAdapter.MemoryListener() {
            @Override
            public void onMemoryClick(ChannelMemory memory) {
                // Actually tune to it.
                if (radioAudioService != null && radioAudioService.getMode() == RadioMode.SCAN) {
                    radioAudioService.setScanning(false);
                    setScanningUi(false);
                }
                if (radioAudioService != null) {
                    radioAudioService.tuneToMemory(memory);
                }

                // Highlight the tapped memory, unhighlight all the others.
                viewModel.highlightMemory(memory);
                memoriesAdapter.notifyDataSetChanged();
            }

            @Override
            public void onMemoryDelete(ChannelMemory memory) {
                String freq = memory.frequency;
                viewModel.deleteMemoryAsync(memory, () -> viewModel.loadDataAsync(() -> runOnUiThread(() -> {
                    memoriesAdapter.notifyDataSetChanged();
                    if (radioAudioService != null) {
                        radioAudioService.tuneToFreq(freq); // Stay on the same freq as the now-deleted memory
                    }
                })));
            }

            @Override
            public void onMemoryEdit(ChannelMemory memory) {
                Intent intent = new Intent("com.vagell.kv4pht.EDIT_MEMORY_ACTION");
                intent.putExtra("requestCode", REQUEST_EDIT_MEMORY);
                intent.putExtra("memoryId", memory.memoryId);
                intent.putExtra("isVhfRadio", (radioAudioService != null && radioAudioService.getRadioType() == RadioAudioService.RadioModuleType.VHF));
                startActivityForResult(intent, REQUEST_EDIT_MEMORY);
            }
        });
        memoriesRecyclerView.setAdapter(memoriesAdapter);

        // Observe the channel memories LiveData in MainViewModel (so the RecyclerView can populate with the memories)
        viewModel.getChannelMemories().observe(this, new Observer<List<ChannelMemory>>() {
            @Override
            public void onChanged(List<ChannelMemory> channelMemories) {
                memoriesAdapter.setMemoriesList(channelMemories);
                memoriesAdapter.notifyDataSetChanged();
                trySyncInitialRadioUi();
            }
        });

        // Prepare a RecyclerView for the list APRS messages we've received in the past
        aprsRecyclerView = findViewById(R.id.aprsRecyclerView);
        aprsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        aprsAdapter = new APRSAdapter();
        aprsRecyclerView.setAdapter(aprsAdapter);

        // Observe the APRS messages LiveData in MainViewModel (so the RecyclerView can populate with the APRS messages)
        viewModel.getAPRSMessages().observe(this, new Observer<List<APRSMessage>>() {
            @Override
            public void onChanged(List<APRSMessage> aprsMessages) {
                aprsAdapter.setAPRSMessageList(aprsMessages);
                aprsAdapter.notifyDataSetChanged();

                // Scroll to the bottom when a new message is added
                if (aprsMessages != null && !aprsMessages.isEmpty()) {
                    aprsRecyclerView.scrollToPosition(aprsMessages.size() - 1);
                }
            }
        });

        // Set up behavior on the bottom nav
        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigationView);
        bottomNav.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.voice_mode) {
                    showScreen(ScreenType.SCREEN_VOICE);
                } else if (itemId == R.id.text_chat_mode) {
                    showScreen(ScreenType.SCREEN_CHAT);
                }
                return true;
            }
        });
        attachListeners();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(this, serviceShutdownReceiver, new IntentFilter(RadioAudioService.ACTION_SERVICE_STOPPING), ContextCompat.RECEIVER_NOT_EXPORTED);
        viewModel.loadDataAsync(this::applySettings);
    }

    final MainActivity context = this;

    /** Defines callbacks for service binding, passed to bindService(). */
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            radioAudioService = ((RadioAudioService.RadioBinder) service).getService();
            radioAudioServiceBound = true;

            // Give the service other critical things it needs to work properly.
            RadioAudioService.RadioAudioServiceCallbacks callbacks = new RadioAudioService.RadioAudioServiceCallbacks() {
                @Override
                public void radioMissing() {
                    showBand(BandType.BAND_UNKNOWN);
                    sMeterUpdate(0); // No rx when no radio
                    resetActiveRadioUi();
                    showUSBSnackbar();
                    findViewById(R.id.pttButton).setClickable(false);
                }

                @Override
                public void radioConnected() {
                    hideSnackBar();
                    applySettings();
                    findViewById(R.id.pttButton).setClickable(true);
                }

                @Override
                public void setRadioType(RadioAudioService.RadioModuleType radioType) {
                    if (radioType.equals(RadioAudioService.RadioModuleType.VHF)) {
                        showBand(BandType.BAND_VHF);
                    } else if (radioType.equals(RadioAudioService.RadioModuleType.UHF)) {
                        showBand(BandType.BAND_UHF);
                    } else {
                        showBand(BandType.BAND_UNKNOWN);
                    }
                    runOnUiThread(MainActivity.this::updateMemoryBandFilter);
                }

                @Override
                public void hideSnackBar() {
                    if (usbSnackbar != null) {
                        usbSnackbar.dismiss();
                        usbSnackbar = null;
                    }
                    if (versionSnackbar != null) {
                        versionSnackbar.dismiss();
                        versionSnackbar = null;
                    }
                    if (radioModuleNotFoundSnackbar != null) {
                        radioModuleNotFoundSnackbar.dismiss();
                        radioModuleNotFoundSnackbar = null;
                    }

                }

                @Override
                public void radioModuleHandshake() {
                    showHandshakeSnackbar();
                }

                @Override
                public void radioModuleNotFound() {
                    showRadioModuleNotFoundSnackbar();
                }

                @Override
                public void audioTrackCreated() { }

                @Override
                public void packetReceived(APRSPacket aprsPacket) {
                    handleChatPacket(aprsPacket);
                }

                @Override
                public void startingAprsBeacon(String frequencyStr) {
                    runOnUiThread(() -> showSimpleSnackbar(getString(R.string.beaconing_aprs_on, frequencyStr)));
                }

                @Override
                public void scannedToMemory(int memoryId) {
                    tuneToMemoryUi(memoryId);
                }

                @Override
                public void tunedToFreq(String frequencyStr) {
                    runOnUiThread(() -> tuneToFreqUi(frequencyStr));
                }

                @Override
                public void outdatedFirmware(int firmwareVer) {
                    showVersionSnackbar(firmwareVer);
                }

                @Override
                public void initialDeviceStateReceived() {
                    runOnUiThread(() -> {
                        initialRadioUiSynced = false;
                        pendingInitialRadioUiSync = true;
                        trySyncInitialRadioUi();
                    });
                }

                @Override
                public void missingFirmware() {
                    showVersionSnackbar(-1);
                }

                @Override
                public void txStarted() {
                    if (activeMemoryId == -1) {
                        return;
                    }

                    // Display any offset while transmitting.
                    List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
                    if (channelMemories == null) {
                        return;
                    }
                    for (int i = 0; i < channelMemories.size(); i++) {
                        if (channelMemories.get(i).memoryId == activeMemoryId) {
                            ChannelMemory memory = channelMemories.get(i);
                            if (memory.offset == ChannelMemory.OFFSET_NONE) {
                                return; // No offset, can just leave current frequency visible.
                            }

                            Float freq = Float.parseFloat(memory.frequency);
                            freq = (memory.offset == ChannelMemory.OFFSET_UP) ? (freq + (0f + memory.offsetKhz / 1000f)) : (freq - (0f + memory.offsetKhz / 1000f));
                            showFrequency(radioAudioService.validateFrequency("" + freq));
                            break;
                        }
                    }
                }

                @Override
                public void txEnded() {
                    if (activeMemoryId == -1) {
                        return;
                    }

                    // Stop displaying any offset now that transmit is done.
                    List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
                    if (channelMemories == null) {
                        return;
                    }
                    for (int i = 0; i < channelMemories.size(); i++) {
                        if (channelMemories.get(i).memoryId == activeMemoryId) {
                            ChannelMemory memory = channelMemories.get(i);
                            Float freq = Float.parseFloat(memory.frequency);
                            showFrequency(radioAudioService.validateFrequency("" + freq));
                            break;
                        }
                    }
                }

                @Override
                public void moduleTxStateChanged(boolean txActive) {
                    runOnUiThread(() -> showModuleTxState(txActive));
                }

                /**
                 * Shows firmware-reported TX activity even when Android did not initiate PTT,
                 * for example while firmware is transmitting an APRS packet.
                 */
                private void showModuleTxState(boolean txActive) {
                    int bandColor = ContextCompat.getColor(MainActivity.this, txActive ? R.color.accent : R.color.band);
                    int sMeterColor = ContextCompat.getColor(MainActivity.this, txActive ? R.color.accent : R.color.primary);

                    TextView activeBand = findViewById(R.id.activeBand);
                    activeBand.setTextColor(bandColor);

                    int[] sMeterIds = {
                        R.id.sMeter1, R.id.sMeter2, R.id.sMeter3,
                        R.id.sMeter4, R.id.sMeter5, R.id.sMeter6,
                        R.id.sMeter7, R.id.sMeter8, R.id.sMeter9
                    };
                    for (int sMeterId : sMeterIds) {
                        findViewById(sMeterId).setBackgroundColor(sMeterColor);
                    }
                }

                @Override
                public void chatError(String text) {
                    Snackbar snackbar = Snackbar.make(context, findViewById(R.id.mainTopLevelLayout), text, LENGTH_LONG)
                            .setBackgroundTint(Color.rgb(140, 20, 0))
                            .setTextColor(Color.WHITE)
                            .setAnchorView(findViewById(R.id.textChatInput));

                    // Make the text of the snackbar larger.
                    TextView snackbarTextView = (TextView) snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
                    snackbarTextView.setTextSize(20);

                    snackbar.show();
                }

                @Override
                public void sMeterUpdate(int value) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateSMeter(value);
                        }
                    });
                }

                @Override
                public void sentAprsBeacon(double latitude, double longitude, String frequencyStr, boolean wasSwitch) {
                    // Show a mock-up of the beacon we sent, in our own chat log
                    APRSMessage myBeacon = new APRSMessage();
                    myBeacon.type = APRSMessage.POSITION_TYPE;
                    myBeacon.fromCallsign = callsign;
                    myBeacon.positionLat = latitude;
                    myBeacon.positionLong = longitude;
                    myBeacon.timestamp = java.time.Instant.now().getEpochSecond();
                    threadPoolExecutor.execute(() -> {
                        viewModel.getAppDb().aprsMessageDao().insertAll(myBeacon);
                        viewModel.loadDataAsync(() -> runOnUiThread(() -> aprsAdapter.notifyDataSetChanged()));
                    });
                }

                @Override
                public void unknownLocation() {
                    showSimpleSnackbar("Can't find your location, no beacon sent");
                }

                @Override
                public void forcedPttStart() { // When user pushes physical PTT.
                    startPttUi(false);
                }

                @Override
                public void forcedPttEnd() { // When user releases physical PTT.
                    endPttUi();
                }

                @Override
                public void showNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {
                    doShowNotification(notificationChannelId, notificationTypeId, title, message, tapIntentName);
                }
            };
            initAudioRecorder();
            radioAudioService.setCallbacks(callbacks);
            applySettings(); // Some settings require radioAudioService to exist to apply.
            radioAudioService.setChannelMemories(viewModel.getChannelMemories());
            runOnUiThread(() -> radioAudioService.start());
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            radioAudioService = null;
            radioAudioServiceBound = false;
            Log.d("DEBUG", "RadioAudioService disconnected from MainActivity.");
            // TODO if this is unexpected we should probably try to restart the service.
        }
    };

    /**
     * Returns the set of runtime permissions required before starting
     * {@link android.app.Service#startForeground(int, android.app.Notification, int)}
     * with the types used by {@code RadioAudioService}.
     * <p>
     * Specifically:
     * <ul>
     *   <li>{@link Manifest.permission#RECORD_AUDIO} — required for
     *       {@code FOREGROUND_SERVICE_TYPE_MICROPHONE} when capturing audio.</li>
     *   <li>{@link Manifest.permission#ACCESS_FINE_LOCATION} — required for
     *       {@code FOREGROUND_SERVICE_TYPE_LOCATION} when including GPS data
     *       in APRS beaconing.</li>
     *   <li>{@link Manifest.permission#POST_NOTIFICATIONS} (API 33+) — required
     *       to show the persistent foreground notification that accompanies any
     *       foreground service.</li>
     * </ul>
     * These must be granted <b>before</b> calling
     * {@link android.content.Context#startForegroundService(Intent)} to avoid
     * {@link SecurityException} on Android 34+.
     *
     * @return immutable list of required runtime permissions
     */
    private List<String> foregroundServicePermissions() {
        return List.of(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS);
    }

    private void startAndBindRadioAudioService() {
        if (radioAudioServiceBound && radioAudioService != null) {
            return;
        }
        if (!bindingInProgress.compareAndSet(false, true)) {
            return;
        }
        ensurePermissions(foregroundServicePermissions(), allGranted -> {
            if (Boolean.TRUE.equals(allGranted)) {
                final Intent svc = new Intent(this, RadioAudioService.class)
                    .putExtra(AppSetting.SETTING_CALLSIGN, callsign)
                    .putExtra("activeMemoryId", activeMemoryId)
                    .putExtra("activeFrequencyStr", activeFrequencyStr);
                startForegroundService(svc);
                bindService(svc, connection, Context.BIND_AUTO_CREATE);
                bindingInProgress.set(false);
            } else {
                showSimpleSnackbar("App can't work without required permissions");
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        startAndBindRadioAudioService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
        unregisterReceiver(serviceShutdownReceiver);
        try {
            threadPoolExecutor.shutdownNow();
        } catch (Exception ignored) { }

        try {
            if (radioAudioServiceBound) {
                unbindService(connection);
                radioAudioServiceBound = false;
            }
        } catch (Exception e) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If we lost reference to the radioAudioService, startAndBindRadioAudioService();
        startAndBindRadioAudioService();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // If we arrived here from an APRS text chat notification, open text chat.
        if (intent != null && intent.getAction() != null && intent.getAction().equals(INTENT_OPEN_CHAT)) {
            showScreen(ScreenType.SCREEN_CHAT);
        }
    }

    private void handleChatPacket(APRSPacket rawAprsPacket) {
        // We use duck-typing for APRS messages since the spec is pretty loose with all the ways
        // you can define different fields and values. Once we know the type, we set aprsMessage.type.

        final APRSPacket aprsPacketFinal;
        APRSMessage aprsMessage = new APRSMessage();
        InformationField infoField = rawAprsPacket.getPayload();

        // Handle third-party relayed packets
        String relayCallsign = null;
        com.vagell.kv4pht.aprs.parser.ThirdPartyField thirdPartyField = (com.vagell.kv4pht.aprs.parser.ThirdPartyField) infoField.getAprsData(APRSTypes.T_THIRDPARTY);
        if (thirdPartyField != null) {
            relayCallsign = rawAprsPacket.getSourceCall();
            com.vagell.kv4pht.aprs.parser.APRSPacket innerPacket = thirdPartyField.getInnerPacket();
            if (innerPacket == null || innerPacket.hasFault()) {
                aprsMessage.type = APRSMessage.UNKNOWN_TYPE;
                aprsMessage.fromCallsign = rawAprsPacket.getSourceCall();
                aprsMessage.timestamp = java.time.Instant.now().getEpochSecond();
                aprsMessage.relayCallsign = relayCallsign;
                try {
                    aprsMessage.comment = "Raw: " + new String(infoField.getRawBytes(), "UTF-8");
                } catch (Exception e) { }
                threadPoolExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        viewModel.getAppDb().aprsMessageDao().insertAll(aprsMessage);
                        viewModel.loadDataAsync(() -> runOnUiThread(() -> aprsAdapter.notifyDataSetChanged()));
                    }
                });
                return;
            }
            aprsPacketFinal = innerPacket;
            infoField = innerPacket.getPayload();
        } else {
            aprsPacketFinal = rawAprsPacket;
        }

        WeatherField weatherField = (WeatherField) infoField.getAprsData(APRSTypes.T_WX);
        PositionField positionField = (PositionField) infoField.getAprsData(APRSTypes.T_POSITION);
        ObjectField objectField = (ObjectField) infoField.getAprsData(APRSTypes.T_OBJECT);
        aprsMessage.timestamp = java.time.Instant.now().getEpochSecond();

        // Get the fromCallsign (all APRS messages must have this)
        aprsMessage.fromCallsign = aprsPacketFinal.getSourceCall();

        // Get the position, if included.
        if (null != positionField) {
            aprsMessage.type = APRSMessage.POSITION_TYPE; // Anything with a position is POSITION_TYPE unless we determine more specific type later.
            aprsMessage.positionLat = positionField.getPosition().getLatitude();
            aprsMessage.positionLong = positionField.getPosition().getLongitude();
        }

        // Try to find a comment (could be at multiple levels in the packet).
        String comment = aprsPacketFinal.getComment();
        if (null != infoField && (null == comment || comment.trim().length() == 0)) {
            comment = infoField.getComment();
        }
        if (null != positionField && (null == comment || comment.trim().length() == 0)) {
            comment = positionField.getComment();
        }
        if (null != objectField && (null == comment || comment.trim().length() == 0)) {
            comment = objectField.getComment();
        }
        if (null != weatherField && (null == comment || comment.trim().length() == 0)) {
            comment = weatherField.getComment();
        }
        if (null != comment && comment.trim().length() > 0) {
            aprsMessage.comment = comment;
        }

        if (null != weatherField) { // APRS "weather" (i.e. any message with weather data attached)
            aprsMessage.type = APRSMessage.WEATHER_TYPE;
            aprsMessage.temperature = (null == weatherField.getTemp()) ? 0 : weatherField.getTemp();
            aprsMessage.humidity = (null == weatherField.getHumidity()) ? 0 : weatherField.getHumidity();
            aprsMessage.pressure = (null == weatherField.getPressure()) ? 0 : weatherField.getPressure();
            aprsMessage.rain = (null == weatherField.getRainLast24Hours()) ? 0 : weatherField.getRainLast24Hours(); // TODO don't ignore other rain measurements
            aprsMessage.snow = (null == weatherField.getSnowfallLast24Hours()) ? 0 : weatherField.getSnowfallLast24Hours();
            aprsMessage.windForce = (null == weatherField.getWindSpeed()) ? 0 : weatherField.getWindSpeed();
            aprsMessage.windDir = (null == weatherField.getWindDirection()) ? "" : Utilities.degressToCardinal(weatherField.getWindDirection());

            // Log.d("DEBUG", "Weather packet received");
        } else if (infoField.getDataTypeIdentifier() == ':') { // APRS "message" type. What we expect for our text chat.
            aprsMessage.type = APRSMessage.MESSAGE_TYPE;
            MessagePacket messagePacket = new MessagePacket(infoField.getRawBytes(), aprsPacketFinal.getDestinationCall());
            aprsMessage.toCallsign = messagePacket.getTargetCallsign();

            String msgNumStr = messagePacket.getMessageNumber();
            if (msgNumStr != null && !msgNumStr.trim().isEmpty()) {
                try {
                    aprsMessage.msgNum = Integer.parseInt(msgNumStr.trim());
                } catch (NumberFormatException e) {
                    aprsMessage.msgNum = -1;
                }
            } else {
                aprsMessage.msgNum = -1;
            }

            if (messagePacket.isAck()) {
                aprsMessage.wasAcknowledged = true;
                if (aprsMessage.msgNum == -1) {
                    Log.d("DEBUG", "Warning: Bad message number in APRS ack, ignoring: '" + messagePacket.getMessageNumber() + "'");
                    return;
                }
                // Log.d("DEBUG", "Message ack received");
            } else {
                aprsMessage.msgBody = messagePacket.getMessageBody();
                // Log.d("DEBUG", "Message packet received");

                // Handle messages addressed to the current callsign
                if (callsign != null && aprsMessage.toCallsign != null && aprsMessage.toCallsign.trim().equalsIgnoreCase(callsign.trim())) {
                    doShowNotification(
                            RadioAudioService.MESSAGE_NOTIFICATION_CHANNEL_ID,
                            RadioAudioService.MESSAGE_NOTIFICATION_TO_YOU_ID,
                            aprsPacketFinal.getSourceCall() + " messaged you",
                            aprsMessage.msgBody,
                            RadioAudioService.INTENT_OPEN_CHAT);

                    if (aprsMessage.msgNum != -1) { // APRS spec says only ack if msg num provided
                        // Send acknowledgment after a delay
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (radioAudioService != null) {
                                radioAudioService.sendAckMessage(aprsPacketFinal.getSourceCall().toUpperCase(), String.valueOf(aprsMessage.msgNum));
                            }
                        }, 1000);
                    }
                }
            }
        } else if (infoField.getDataTypeIdentifier() == ';') { // APRS "object"
            aprsMessage.type = APRSMessage.OBJECT_TYPE;
            if (null != objectField) {
                aprsMessage.objName = objectField.getObjectName();
                // Log.d("DEBUG", "Object packet received");
            }
        }

        // If there is a fault in the packet, or the message type is unknown, we at least display the raw contents as a comment.
        if (aprsPacketFinal.hasFault() || aprsMessage.type == APRSMessage.UNKNOWN_TYPE && (null == comment || comment.trim().length() == 0)) {
            if (null != infoField) {
                try {
                    comment = "Raw: " + new String(infoField.getRawBytes(), "UTF-8");
                    aprsMessage.comment = comment;
                } catch (Exception e) { }
            }
        }

        aprsMessage.relayCallsign = relayCallsign;

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                APRSMessage oldAPRSMessage = null;
                if (aprsMessage.wasAcknowledged) {
                    // When this is an ack, we don't insert anything in the DB, we try to find that old message to ack it.
                    oldAPRSMessage = viewModel.getAppDb().aprsMessageDao().getMsgToAck(aprsMessage.toCallsign, aprsMessage.msgNum);
                    if (null == oldAPRSMessage) {
                        Log.d("DEBUG", "Can't ack unknown APRS message from: " + aprsMessage.toCallsign + " with msg number: " + aprsMessage.msgNum);
                        return;
                    } else {
                        // Ack an old message
                        oldAPRSMessage.wasAcknowledged = true;
                        viewModel.getAppDb().aprsMessageDao().update(oldAPRSMessage);
                    }
                } else {
                    // Not an ack, add a message

                    // Deduplicate incoming APRS messages with sequence numbers against recent history
                    if (aprsMessage.type == APRSMessage.MESSAGE_TYPE && aprsMessage.msgNum != -1) {
                        if (viewModel.getAppDb().aprsMessageDao().isRecentDuplicate(
                                aprsMessage.fromCallsign,
                                aprsMessage.msgBody,
                                aprsMessage.msgNum)) {
                            Log.d("DEBUG", "Discarding duplicate APRS message from " +
                                    aprsMessage.fromCallsign + " with msgNum " + aprsMessage.msgNum);
                            return;
                        }
                    }

                    viewModel.getAppDb().aprsMessageDao().insertAll(aprsMessage);
                }

                viewModel.loadDataAsync(() -> runOnUiThread(() -> aprsAdapter.notifyDataSetChanged()));
            }
        });
    }

    private enum ScreenType {
        SCREEN_VOICE,
        SCREEN_CHAT
    };

    private void showScreen(ScreenType screenType) {
        // TODO The right way to implement the bottom nav toggling the UI would be with Fragments.
        // Controls for voice mode
        findViewById(R.id.voiceModeLineHolder).setVisibility(screenType == ScreenType.SCREEN_CHAT ? GONE : VISIBLE);
        findViewById(R.id.pttButton).setVisibility(screenType == ScreenType.SCREEN_CHAT ? GONE : VISIBLE);
        findViewById(R.id.memoriesList).setVisibility(screenType == ScreenType.SCREEN_CHAT ? GONE : VISIBLE);
        findViewById(R.id.voiceModeBottomControls).setVisibility(screenType == ScreenType.SCREEN_CHAT ? GONE : VISIBLE);

        // Controls for text mode
        findViewById(R.id.textModeContainer).setVisibility(screenType == ScreenType.SCREEN_CHAT ? VISIBLE : GONE);

        if (screenType == ScreenType.SCREEN_CHAT) {
            if (radioAudioService != null) {
                // Stop scanning when we enter chat mode, we don't want to tx data on an unexpected
                // frequency. User must set it manually (or select it before coming to chat mode, but
                // can't be scanning).
                radioAudioService.setScanning(false, true);
            }
            setScanningUi(false);

            // If their callsign is not set, display a snackbar asking them to set it before they
            // can transmit.
            if (callsign == null || callsign.length() == 0) {
                showCallsignSnackbar(getString(R.string.set_your_callsign_to_send_text_chat));
                ImageButton sendButton = findViewById(R.id.sendButton);
                sendButton.setEnabled(false);
                findViewById(R.id.sendButtonOverlay).setVisibility(VISIBLE);
            } else {
                ImageButton sendButton = findViewById(R.id.sendButton);
                sendButton.setEnabled(true);
                if (callsignSnackbar != null) {
                    callsignSnackbar.dismiss();
                }
                findViewById(R.id.sendButtonOverlay).setVisibility(GONE);
            }
        } else if (screenType == ScreenType.SCREEN_VOICE){
            hideKeyboard();
            findViewById(R.id.frequencyContainer).setVisibility(VISIBLE);
            findViewById(R.id.rxAudioCircle).setVisibility(VISIBLE);

            if (callsignSnackbar != null) {
                callsignSnackbar.dismiss();
            }

            if (radioAudioService != null) {
                radioAudioService.setRssi(true);
                findViewById(R.id.sMeter).setVisibility(VISIBLE);
            }
        }

        activeScreenType = screenType;
    }

    private void showCallsignSnackbar(CharSequence snackbarMsg) {
        callsignSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.set_now, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        callsignSnackbar.dismiss();
                        startSettingsActivity();
                    }
                })
                .setBackgroundTint(getResources().getColor(R.color.primary))
                .setTextColor(getResources().getColor(R.color.medium_gray))
                .setActionTextColor(getResources().getColor(R.color.black))
                .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) callsignSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) callsignSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        callsignSnackbar.show();
    }

    public void sendButtonOverlayClicked(View view) {
        if (callsign == null || callsign.length() == 0) {
            showCallsignSnackbar(getString(R.string.set_your_callsign_to_send_text_chat));
            ImageButton sendButton = findViewById(R.id.sendButton);
            sendButton.setEnabled(false);
        }
    }

    public void sendTextClicked(View view) {
        if (null != radioAudioService && !radioAudioService.isTxAllowed()) {
            showSimpleSnackbar(getString(R.string.can_t_tx_outside_ham_band));
            return;
        }

        String targetCallsign = ((EditText) findViewById(R.id.textChatTo)).getText().toString().trim();
        if (targetCallsign.length() == 0) {
            targetCallsign = "CQ";
        } else {
            targetCallsign = targetCallsign.toUpperCase();
        }
        ((EditText) findViewById(R.id.textChatTo)).setText(targetCallsign);

        String outText = ((EditText) findViewById(R.id.textChatInput)).getText().toString();
        if (outText.length() == 0) {
            return; // Nothing to send.
        }

        int msgNum = -1;
        if (radioAudioService != null) {
            msgNum = radioAudioService.sendChatMessage(targetCallsign, outText);
        }

        ((EditText) findViewById(R.id.textChatInput)).setText("");
        hideKeyboard();

        final APRSMessage aprsMessage = new APRSMessage();
        aprsMessage.type = APRSMessage.MESSAGE_TYPE;
        aprsMessage.fromCallsign = callsign.toUpperCase().trim();
        aprsMessage.toCallsign = targetCallsign.toUpperCase().trim();
        aprsMessage.msgBody = outText.trim();
        aprsMessage.timestamp = java.time.Instant.now().getEpochSecond();
        aprsMessage.msgNum = msgNum;

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                viewModel.getAppDb().aprsMessageDao().insertAll(aprsMessage);
                viewModel.loadDataAsync(() -> runOnUiThread(() -> aprsAdapter.notifyDataSetChanged()));
            }
        });

    }

    private void updateRecordingVisualization(int waitMs, float txVolume) {
        if (disableAnimations) { return; }
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> runOnUiThread(() -> {
            ImageView txAudioView = findViewById(R.id.txAudioCircle);
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) txAudioView.getLayoutParams();
            RadioMode mode = radioAudioService != null ? radioAudioService.getMode() : RadioMode.UNKNOWN;
            layoutParams.width = Math.abs(txVolume) < 0.001 ||
                    mode == RadioMode.RX ? 0 : (int) (MAX_AUDIO_VIZ_SIZE * txVolume) + MIN_TX_AUDIO_VIZ_SIZE;
            layoutParams.height = Math.abs(txVolume) < 0.001 ||
                    mode == RadioMode.RX ? 0 : (int) (MAX_AUDIO_VIZ_SIZE * txVolume) + MIN_TX_AUDIO_VIZ_SIZE;
            txAudioView.setLayoutParams(layoutParams);
        }), waitMs); // waitMs gives us the fps we desire, see RECORD_ANIM_FPS constant.
    }

    private void applySettings() {
        if (!viewModel.isLoaded()) {
            return;
        }
        threadPoolExecutor.execute(() -> {
            final Map<String, String> settings = viewModel.getAppDb().appSettingDao().getAll().stream()
                .collect(Collectors.toMap(AppSetting::getName, AppSetting::getValue));
            runOnUiThread(() -> {
                applyCallSignSetting(settings);
                applyGroupSetting(settings);
                applyTxFreqLimitsSettings(settings);
                applyMicGainSetting(settings);
                applyAccessibilitySettings(settings);
                applyAprsSettings(settings);
            });
        });
    }

    private void applyCallSignSetting(Map<String, String> settings) {
        this.callsign = settings.getOrDefault(AppSetting.SETTING_CALLSIGN, "");
        boolean empty = callsign.isEmpty();
        findViewById(R.id.sendButton).setEnabled(!empty);
        findViewById(R.id.sendButtonOverlay).setVisibility(empty ? VISIBLE : GONE);
        if (radioAudioService != null) {
            radioAudioService.setCallsign(callsign);
        }
    }

    /**
     * Restores the previously selected memory group filter after settings are loaded.
     */
    private void applyGroupSetting(Map<String, String> settings) {
        String group = settings.get(AppSetting.SETTING_LAST_GROUP);
        if (group != null && !group.isEmpty()) {
            selectMemoryGroup(group);
        }
    }

    private void applyTxFreqLimitsSettings(Map<String, String> settings) {
        if (radioAudioService == null) return;
        String min2m = settings.get(AppSetting.SETTING_MIN_2_M_TX_FREQ);
        String max2m = settings.get(AppSetting.SETTING_MAX_2_M_TX_FREQ);
        String min70 = settings.get(AppSetting.SETTING_MIN_70_CM_TX_FREQ);
        String max70 = settings.get(AppSetting.SETTING_MAX_70_CM_TX_FREQ);
        if (min2m != null) radioAudioService.setMin2mTxFreq(Integer.parseInt(min2m));
        if (max2m != null) radioAudioService.setMax2mTxFreq(Integer.parseInt(max2m));
        if (min70 != null) radioAudioService.setMin70cmTxFreq(Integer.parseInt(min70));
        if (max70 != null) radioAudioService.setMax70cmTxFreq(Integer.parseInt(max70));
        radioAudioService.updateTxLimitsForBand();
    }

    /**
     * Applies the saved microphone gain preference to the bound radio service.
     */
    private void applyMicGainSetting(Map<String, String> settings) {
        if (radioAudioService == null) return;
        String gain = settings.get(AppSetting.SETTING_MIC_GAIN_BOOST);
        if (gain != null) radioAudioService.setMicGainBoost(gain);
    }

    private void applyAccessibilitySettings(Map<String, String> settings) {
        disableAnimations = Boolean.parseBoolean(settings.getOrDefault(AppSetting.SETTING_DISABLE_ANIMATIONS, "false"));
        if (disableAnimations) {
            ImageView rxAudioView = findViewById(R.id.rxAudioCircle);
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) rxAudioView.getLayoutParams();
            layoutParams.width = 0;
            layoutParams.height = 0;
            rxAudioView.setLayoutParams(layoutParams);
            updateRecordingVisualization(100, 0.0f);
        }

        stickyPTT = Boolean.parseBoolean(settings.getOrDefault(AppSetting.SETTING_STICKY_PTT, "false"));
    }

    private void applyAprsSettings(Map<String, String> settings) {
        String accuracy = settings.get(AppSetting.SETTING_APRS_POSITION_ACCURACY);
        String beacon = settings.get(AppSetting.SETTING_APRS_BEACON_POSITION);
        String beaconFreq = settings.get(AppSetting.SETTING_APRS_BEACON_FREQUENCY);
        String aprsIcon = settings.get(AppSetting.SETTING_APRS_ICON);

        if (accuracy != null && radioAudioService != null) {
            threadPoolExecutor.execute(() -> radioAudioService.setAprsPositionAccuracy(
                accuracy.equals(getString(R.string.exact)) ?
                    RadioAudioService.APRS_POSITION_EXACT :
                    RadioAudioService.APRS_POSITION_APPROX));
        }

        if (beaconFreq != null && radioAudioService != null) {
            threadPoolExecutor.execute(() -> radioAudioService.setAprsBeaconFrequency(beaconFreq));
        }

        if (radioAudioService != null && beacon != null) {
            boolean enabled = Boolean.parseBoolean(beacon);
            Runnable action = () -> radioAudioService.setAprsBeaconPosition(enabled);
            if (enabled) {
                ensurePermissions(List.of(Manifest.permission.ACCESS_FINE_LOCATION), allGranted -> {
                    if (Boolean.TRUE.equals(allGranted)) {
                        threadPoolExecutor.execute(action);
                    }});
            } else {
                threadPoolExecutor.execute(action);
            }
        }

        if (radioAudioService != null && aprsIcon != null) {
            radioAudioService.setAprsPositionIcon(SettingsActivity.getAPRSIconFromSettingChoice(getResources(), aprsIcon));
        }

        String digipeat = settings.get(AppSetting.SETTING_DIGIPEAT_PACKETS);
        if (digipeat != null && radioAudioService != null) {
            radioAudioService.setDigipeatPackets(Boolean.parseBoolean(digipeat));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachListeners() {
        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setOnTouchListener((v, event) -> {
            boolean touchHandled = false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (!v.isClickable()) {
                        touchHandled = true;
                        break;
                    }

                    if (null != radioAudioService && !radioAudioService.isTxAllowed()) {
                        touchHandled = true;
                        showSimpleSnackbar(getString(R.string.can_t_tx_outside_ham_band));
                        break;
                    }

                    pttButtonDebounceHandler.removeCallbacksAndMessages(null);
                    if (stickyPTT) {
                        if (radioAudioService != null && radioAudioService.getMode() == RadioMode.RX) {
                            ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                            if (radioAudioService != null) {
                                // If the user tries to transmit, stop scanning so we don't
                                // move to a different frequency during or after the tx.
                                radioAudioService.setScanning(false, false);
                                setScanningUi(false);
                                radioAudioService.startPtt();
                            }
                            startPttUi(false);
                        } else if (radioAudioService != null && radioAudioService.getMode() == RadioMode.TX) {
                            ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                            if (radioAudioService != null) {
                                radioAudioService.endPtt();
                            }
                            endPttUi();
                        }
                    } else {
                        if (!isRecording) {
                            ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                            if (radioAudioService != null) {
                                // If the user tries to transmit, stop scanning so we don't
                                // move to a different frequency during or after the tx.
                                radioAudioService.setScanning(false, false);
                                setScanningUi(false);
                                radioAudioService.startPtt();
                            }
                            startPttUi(false);
                        }
                    }
                    touchHandled = true;
                    break;
                case MotionEvent.ACTION_UP:
                    if (!v.isClickable()) {
                        touchHandled = true;
                        break;
                    }
                    pttButtonDebounceHandler.removeCallbacksAndMessages(null);
                    if (!stickyPTT) {
                        pttButtonDebounceHandler.postDelayed(() -> {
                            if (radioAudioService != null) {
                                radioAudioService.endPtt();
                            }
                            endPttUi();
                        }, 250);
                    }
                    touchHandled = true;
                    break;
            }
            return touchHandled;
        });

        pttButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // This click handler is only for TalkBack users who also have stickyPTT enabled.
                // It's so they can use the typical quick double-tap to toggle PTT on and off. So
                // if stickyPTT isn't being used, don't handle a click on the PTT button (they need
                // to hold since it's not sticky).
                if (!stickyPTT) {
                    return;
                }

                if (null != radioAudioService && !radioAudioService.isTxAllowed()) {
                    showSimpleSnackbar(getString(R.string.can_t_tx_outside_ham_band));
                    return;
                }

                if (radioAudioService != null && radioAudioService.getMode() == RadioMode.RX) {
                    ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                    if (radioAudioService != null) {
                        radioAudioService.startPtt();
                    }
                    startPttUi(false);
                } else if (radioAudioService != null && radioAudioService.getMode() == RadioMode.TX) {
                    ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                    if (radioAudioService != null) {
                        radioAudioService.endPtt();
                    }
                    endPttUi();
                }
            }
        });

        EditText activeFrequencyField = findViewById(R.id.activeFrequency);
        activeFrequencyField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (radioAudioService != null) {
                    radioAudioService.tuneToFreq(activeFrequencyField.getText().toString());
                }

                hideKeyboard();
                activeFrequencyField.clearFocus();
                return true;
            }
        });

        final View rootView = findViewById(android.R.id.content);
        final View frequencyView = findViewById(R.id.frequencyContainer);
        final EditText activeFrequencyEditText = findViewById(R.id.activeFrequency);
        final View rxAudioCircleView = findViewById(R.id.rxAudioCircle);

        // Track if keyboard is likely visible (and/or screen got short for some reason), so we can
        // make room for critical UI components that must be visible.
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // When in chat, we need enough vertical space for the user to see their text
                // input box, and any prior chat message they may be replying to. Not necessary in
                // voice mode.
                if (activeScreenType != ScreenType.SCREEN_CHAT) {
                    return;
                }

                // If they're editing the frequency, don't change the layout (they need to see it).
                if (activeFrequencyEditText.hasFocus()) {
                    return;
                }

                // Get the height of the visible display area
                Rect r = new Rect();
                rootView.getWindowVisibleDisplayFrame(r);
                int screenHeight = rootView.getRootView().getHeight();
                int visibleHeight = r.height();

                // Check if the keyboard is likely visible
                int heightDiff = screenHeight - visibleHeight;

                if (heightDiff > screenHeight * 0.25) { // If more than 25% of the screen height is reduced
                    // Keyboard is visible, hide the top view
                    frequencyView.setVisibility(GONE);
                    rxAudioCircleView.setVisibility(GONE);
                } else {
                    // Keyboard is hidden, show the top view
                    frequencyView.setVisibility(VISIBLE);
                    rxAudioCircleView.setVisibility(VISIBLE);
                }
            }
        });
    }

    private void updateSMeter(int value) {
        if (value < 0 || value > 9) {
            Log.d("DEBUG", "Warning: Unexpected S-Meter value (" + value + ") in updateSMeter().");
            return;
        }

        final float S_METER_ON_ALPHA = 1.0f;
        final float S_METER_OFF_ALPHA = 0.2f;

        findViewById(R.id.sMeter1).setAlpha(value > 0 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter2).setAlpha(value > 1 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter3).setAlpha(value > 2 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter4).setAlpha(value > 3 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter5).setAlpha(value > 4 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter6).setAlpha(value > 5 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter7).setAlpha(value > 6 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter8).setAlpha(value > 7 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);
        findViewById(R.id.sMeter9).setAlpha(value > 8 ? S_METER_ON_ALPHA : S_METER_OFF_ALPHA);

        View sMeterView = findViewById(R.id.sMeter);
        sMeterView.setContentDescription("S meter " + value + " of 9");
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        View tokenView = focus != null ? focus : findViewById(R.id.mainTopLevelLayout);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (null != imm) {
            imm.hideSoftInputFromWindow(tokenView.getWindowToken(), 0);
        }
    }

    /**
     * Updates the UI to represent that we've tuned to the given frequency. Does not actually
     * interact with the radio (use RadioAudioService for that).
     */
    private void tuneToFreqUi(String frequencyStr) {
        activeFrequencyStr = radioAudioService.validateFrequency(frequencyStr);
        activeMemoryId = -1;

        showMemoryName(getString(R.string.simplex));
        showFrequency(activeFrequencyStr);

        // Unhighlight all memory rows, since this is a simplex frequency.
        viewModel.highlightMemory(null);
        memoriesAdapter.notifyDataSetChanged();
    }

    /**
     * Updates the UI to represent that we've tuned to the given memory by ID. Does not actually
     * interact with the radio (use RadioAudioService for that).
     */
    private void tuneToMemoryUi(int memoryId) {
        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
        if (channelMemories == null) {
            return;
        }
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == memoryId) {
                activeFrequencyStr = radioAudioService != null ? radioAudioService.validateFrequency(channelMemories.get(i).frequency) : channelMemories.get(i).frequency;
                activeMemoryId = memoryId;

                showMemoryName(channelMemories.get(i).name);
                showFrequency(activeFrequencyStr);
                return;
            }
        }
    }

    /**
     * Seeds the UI from firmware's reported DeviceState after connect.
     *
     * Radio settings now live in module NVS, so Android uses the firmware state as the source of truth
     * instead of restoring app settings. This may no-op if radio config or memories are not loaded yet;
     * in that case the sync stays pending and is retried later.
     */
    private void trySyncInitialRadioUi() {
        if (initialRadioUiSynced || !pendingInitialRadioUiSync || radioAudioService == null) {
            return;
        }
        RadioModuleController radioModule = radioAudioService.getRadioModule();
        if (!radioModule.hasRadioConfig()) {
            return;
        }
        if (radioModule.getMemoryId() >= 0 && viewModel.getChannelMemories().getValue() == null) {
            return;
        }

        ChannelMemory memory = findMatchingMemoryForState();
        if (memory != null) {
            tuneToMemoryUi(memory.memoryId);
            viewModel.highlightMemory(memory);
            memoriesAdapter.notifyDataSetChanged();
        } else {
            tuneToFreqUi(String.format(java.util.Locale.US, "%.4f", radioModule.getRxFrequency()));
        }
        radioAudioService.updateNotificationFromCurrentState();
        pendingInitialRadioUiSync = false;
        initialRadioUiSynced = true;
    }

    /**
     * Finds the stored memory that corresponds to firmware's current memory ID and radio settings.
     * Returns null when firmware is in VFO/simplex mode or the stored memory no longer matches.
     */
    @Nullable
    private ChannelMemory findMatchingMemoryForState() {
        RadioModuleController radioModule = radioAudioService.getRadioModule();
        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
        if (channelMemories == null || radioModule.getMemoryId() < 0) {
            return null;
        }
        for (ChannelMemory memory : channelMemories) {
            if (memory.memoryId == radioModule.getMemoryId() && memoryMatchesDeviceState(memory)) {
                return memory;
            }
        }
        return null;
    }

    /**
     * Checks whether a stored memory still describes the radio configuration reported by firmware.
     */
    private boolean memoryMatchesDeviceState(ChannelMemory memory) {
        try {
            RadioModuleController radioModule = radioAudioService.getRadioModule();
            float rxFreq = Float.parseFloat(radioAudioService.validateFrequency(memory.frequency));
            float txFreq = calculateMemoryTxFrequency(memory);
            return closeEnough(txFreq, radioModule.getTxFrequency())
                && closeEnough(rxFreq, radioModule.getRxFrequency())
                && Math.max(0, ToneHelper.getToneIndex(memory.txTone)) == radioModule.getTxTone()
                && Math.max(0, ToneHelper.getToneIndex(memory.rxTone)) == radioModule.getRxTone();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Calculates the transmit frequency for a memory, including repeater offset.
     */
    private float calculateMemoryTxFrequency(ChannelMemory memory) {
        float txFreq = Float.parseFloat(memory.frequency);
        if (memory.offset == ChannelMemory.OFFSET_UP) {
            txFreq += memory.offsetKhz / 1000f;
        } else if (memory.offset == ChannelMemory.OFFSET_DOWN) {
            txFreq -= memory.offsetKhz / 1000f;
        }
        return Float.parseFloat(radioAudioService.validateFrequency(Float.toString(txFreq)));
    }

    /**
     * Compares MHz values while tolerating small float formatting/rounding differences.
     */
    private boolean closeEnough(float left, float right) {
        return Math.abs(left - right) < 0.0002f;
    }

    /**
     * Filters visible memories to the connected module's supported frequency range.
     * When no module is connected, all memories remain visible so users can still edit/delete them.
     */
    private void updateMemoryBandFilter() {
        if (memoriesAdapter == null) {
            return;
        }
        if (radioAudioService == null || RadioAudioService.RadioModuleType.UNKNOWN.equals(radioAudioService.getRadioType())) {
            memoriesAdapter.clearBandFilter();
        } else {
            memoriesAdapter.setBandFilter(radioAudioService.getMinRadioFreq(), radioAudioService.getMaxRadioFreq());
        }
        memoriesAdapter.notifyDataSetChanged();
    }

    /**
     * Restores the idle welcome text shown when no radio state is available, such as after disconnect.
     */
    @SuppressWarnings("java:S3398")
    private void resetActiveRadioUi() {
        activeMemoryId = -1;
        TextView activeMemoryName = findViewById(R.id.activeMemoryName);
        EditText activeFrequency = findViewById(R.id.activeFrequency);
        activeMemoryName.setText(getString(R.string.welcome_to_display));
        activeFrequency.setText(getString(R.string.kv4p_ht));
    }

    private void showMemoryName(String name) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView activeFrequencyField = findViewById(R.id.activeMemoryName);
                activeFrequencyField.setText(name);
            }
        });
    }

    private void showFrequency(String frequency) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                EditText activeFrequencyField = findViewById(R.id.activeFrequency);
                activeFrequencyField.setText(frequency);
                activeFrequencyStr = frequency;
            }
        });
    }

    public enum BandType {
        BAND_VHF, BAND_UHF, BAND_UNKNOWN
    }

    @SuppressWarnings("java:S3398")
    private void showBand(BandType bandType) {
        runOnUiThread(() -> {
            EditText bandField = findViewById(R.id.activeBand);
            if (bandField == null) return;
            switch (bandType) {
                case BAND_VHF:
                    bandField.setText(getString(R.string.vhf));
                    bandField.setVisibility(View.VISIBLE);
                    break;
                case BAND_UHF:
                    bandField.setText(getString(R.string.uhf));
                    bandField.setVisibility(View.VISIBLE);
                    break;
                default:
                    bandField.setVisibility(View.INVISIBLE);
                    break;
            }
        });
    }


    protected void startPttUi(boolean dataMode) {
        if (!dataMode) {
            startRecording();
        }
    }

    protected void endPttUi() {
        stopRecording();
    }

    public void ensurePermissions(List<String> requestedPerms, Consumer<Boolean> callback) {
        // Filter: drop POST_NOTIFICATIONS on < 33
        List<String> needed = requestedPerms.stream()
            .filter(p -> !(Manifest.permission.POST_NOTIFICATIONS.equals(p) && Build.VERSION.SDK_INT < 33))
            .filter(p -> checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
            .collect(Collectors.toList());
        if (needed.isEmpty()) {
            callback.accept(true);
            return;
        }
        pendingGrantCallback = callback;
        pendingPerms = needed;
        // If any missing permission suggests showing a rationale, show ONE dialog
        boolean showRationale = pendingPerms.stream()
            .anyMatch(this::shouldShowRequestPermissionRationale);
        if (showRationale) {
            new MaterialAlertDialogBuilder(this)
                .setTitle("Permissions needed")
                .setMessage(buildRationaleMessage(pendingPerms.toArray(new String[0])))
                .setPositiveButton("Continue", (d, i) ->
                    requestPermissions(pendingPerms.toArray(new String[0]), REQUEST_ALL_PERMISSIONS))
                .setNegativeButton("Cancel", (d, i) -> {
                    // user cancelled; clear state
                    pendingGrantCallback = null;
                    pendingPerms = null;
                    callback.accept(false);
                })
                .show();
        } else {
            requestPermissions(pendingPerms.toArray(new String[0]), REQUEST_ALL_PERMISSIONS);
        }
    }

    private String buildRationaleMessage(String[] perms) {
        List<String> reasons = new ArrayList<>();
        for (String p : perms) {
            if (Manifest.permission.RECORD_AUDIO.equals(p)) {
                reasons.add("• Microphone — to capture audio for radio/voice features");
            } else if (Manifest.permission.ACCESS_FINE_LOCATION.equals(p)) {
                reasons.add("• Precise location — to include GPS in APRS/beacons");
            } else if (Manifest.permission.POST_NOTIFICATIONS.equals(p) && Build.VERSION.SDK_INT >= 33) {
                reasons.add("• Notifications — to alert you about APRS messages and status");
            } else if (Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(p)) {
                reasons.add("• Background location — to send beacons with the screen off");
            }
        }
        return "We need these permissions for core functionality:\n\n" + TextUtils.join("\n", reasons);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_ALL_PERMISSIONS) {
            return;
        }
        boolean allGranted = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        Consumer<Boolean> done = pendingGrantCallback;
        pendingGrantCallback = null;
        pendingPerms = null;
        if (done != null) {
            done.accept(allGranted);
        }
    }

    private void initAudioRecorder() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (null != audioRecord) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RadioAudioService.AUDIO_SAMPLE_RATE,
                channelConfig,
                audioFormat,
                minBufferSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.d("DEBUG", "Audio init error");
        }
    }

    private void startRecording() {
        if (audioRecord == null) {
            initAudioRecorder();
        }

        // After attempting to initialize, check if it's usable.
        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.d("DEBUG", "AudioRecord not ready, cannot start recording.");
            // If it's not null, it's in a bad state. Release it so we can try again next time.
            if (audioRecord != null) {
                audioRecord.release();
                audioRecord = null;
            }
            return;
        }

        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setBackground(getDrawable(R.drawable.ptt_button_on));

        audioRecord.startRecording();
        isRecording = true;

        recordingThread = new Thread(new Runnable() {
            public void run() {
                processAudioStream();
            }
        }, "AudioRecorder Thread");

        recordingThread.start();
    }

    private void processAudioStream() {
        float audioChunkSampleTotal = 0.0f; // Accumulate across buffers
        int accumulatedSamples = 0; // Track count of samples
        int samplesPerAnimFrame = RadioAudioService.AUDIO_SAMPLE_RATE / RECORD_ANIM_FPS;
        short[] audioBuffer = new short[RadioAudioService.AUDIO_FRAME_SAMPLES];
        while (isRecording) {
            int samples = audioRecord.read(audioBuffer, 0, RadioAudioService.AUDIO_FRAME_SAMPLES, AudioRecord.READ_BLOCKING);
            if (samples == RadioAudioService.AUDIO_FRAME_SAMPLES) {
                if (null == radioAudioService || !radioAudioService.isRadioConnected()) {
                    Log.d("DEBUG", "Error: Could not contact radio in processAudioStream() while recording.");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stopRecording();
                        }
                    });
                    return;
                }
                radioAudioService.sendAudioToESP32(audioBuffer, false);
                // Accumulate samples across buffers
                for (int i = 0; i < samples; i++) {
                    audioChunkSampleTotal += Math.abs(audioBuffer[i] / 32768.0f) * 8.0f;
                    accumulatedSamples++;
                    // If we have enough samples, update visualization
                    if (accumulatedSamples >= samplesPerAnimFrame) {
                        updateRecordingVisualization(0, audioChunkSampleTotal / accumulatedSamples);
                        // Reset accumulators
                        audioChunkSampleTotal = 0.0f;
                        accumulatedSamples = 0;
                    }
                }
            }
        }
    }

    private void stopRecording() {
        if (audioRecord != null) {
            isRecording = false;
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
            recordingThread = null;
            updateRecordingVisualization(100, 0.0f);
        }
        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setBackground(getDrawable(R.drawable.ptt_button));
    }

    private void showUSBSnackbar() {
        CharSequence snackbarMsg = getString(R.string.radio_not_found);
        usbSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
            .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE)
            .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        usbSnackbar.show();
    }

    private void showHandshakeSnackbar() {
        CharSequence snackbarMsg = getString(R.string.handshake_message);
        usbSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
            .setBackgroundTint(getResources().getColor(R.color.primary))
            .setTextColor(getResources().getColor(R.color.medium_gray))
            .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        usbSnackbar.show();
    }

    private void showRadioModuleNotFoundSnackbar() {
        CharSequence snackbarMsg = getString(R.string.module_not_found_message);
        radioModuleNotFoundSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE)
                .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) radioModuleNotFoundSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) radioModuleNotFoundSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        radioModuleNotFoundSnackbar.show();
    }

    /**
     * Alerts the user to missing or old firmware with the option to flash the latest.
     * @param firmwareVer The currently installed firmware version, or -1 if no firmware installed.
     */
    private void showVersionSnackbar(int firmwareVer) {
        final Context ctx = this;
        CharSequence snackbarMsg = firmwareVer == -1 ? getString(R.string.no_firmware_installed) : getString(R.string.new_firmware_available);
        versionSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE)
                .setAction("Flash now", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        startFirmwareActivity();
                    }
                })
                .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) versionSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) versionSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        versionSnackbar.show();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Thread thread = Thread.currentThread();
            Log.d("DEBUG", "usbReceiver.onReceive() action=" + intent.getAction()
                + " thread=" + thread.getName() + "#" + thread.getId());

            String action = intent.getAction();
            synchronized (this) {
                if (ACTION_USB_PERMISSION.equals(action) || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    if (ACTION_USB_PERMISSION.equals(action)
                        && !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.w("DEBUG", "USB permission denied by user.");
                        if (radioAudioService != null) {
                            radioAudioService.onUsbPermissionDenied();
                        }
                        return;
                    }
                    if (radioAudioService != null) {
                        radioAudioService.reconnectViaUSB();
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    if (radioAudioService != null) {
                        radioAudioService.setScanning(false, true);
                    }
                    setScanningUi(false);
                }
            }
        }
    };

    public void scanClicked(View view) {
        setScanningUi((radioAudioService != null) && (radioAudioService.getMode()) != RadioMode.SCAN); // Toggle scanning on/off
        if (radioAudioService != null) {
            radioAudioService.setScanning(radioAudioService.getMode() != RadioMode.SCAN, true);
        }
    }

    @SuppressLint("MissingPermission")
    public void singleBeaconButtonClicked(View view) {
        if (null != radioAudioService && !radioAudioService.isTxAllowed()) {
            showSimpleSnackbar(getString(R.string.can_t_tx_outside_ham_band));
            return;
        }
        if (null == callsign || callsign.trim().isEmpty()) {
            showCallsignSnackbar(getString(R.string.set_your_callsign_to_beacon_your_position));
            return;
        }
        if (null == radioAudioService) {
            return;
        }
        ensurePermissions(List.of(Manifest.permission.ACCESS_FINE_LOCATION), allGranted -> {
            if (Boolean.TRUE.equals(allGranted)) {
                radioAudioService.sendPositionBeacon();
            }
        });
    }

    /**
     * Update the UI to reflect the scanning state. Does not actually interact with the radio,
     * that's handled by RadioAudioService.setScanning().
     */
    private void setScanningUi(boolean scanning) {
        AppCompatButton scanButton = findViewById(R.id.scanButton);
        if (!scanning) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanButton.setText(R.string.scan);
                }
            });

        } else { // Start scanning
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanButton.setText(R.string.stop_scan);
                }
            });
        }
    }

    public void addMemoryClicked(View view) {
        Intent intent = new Intent("com.vagell.kv4pht.ADD_MEMORY_ACTION");
        intent.putExtra("requestCode", REQUEST_ADD_MEMORY);
        intent.putExtra("activeFrequencyStr", activeFrequencyStr);
        intent.putExtra("selectedMemoryGroup", selectedMemoryGroup);
        intent.putExtra("isVhfRadio", (radioAudioService != null && radioAudioService.getRadioType().equals(RadioAudioService.RadioModuleType.VHF)));

        startActivityForResult(intent, REQUEST_ADD_MEMORY);
    }

    public void groupSelectorClicked(View view) {
        Context themedContext = new ContextThemeWrapper(this, R.style.Custom_PopupMenu);
        PopupMenu groupsMenu = new PopupMenu(themedContext, view);
        groupsMenu.inflate(R.menu.groups_menu);

        for (String groupName : memoriesAdapter.visibleGroups()) {
            groupsMenu.getMenu().add(groupName);
        }

        groupsMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                selectMemoryGroup(item.getTitle().toString());
                return true;
            }
        });

        groupsMenu.show();
    }

    private void selectMemoryGroup(String groupName) {
        this.selectedMemoryGroup = groupName.equals(getString(R.string.all_memories)) ? null : groupName;
        memoriesAdapter.setGroupFilter(selectedMemoryGroup);
        memoriesAdapter.notifyDataSetChanged();
        // Add drop-down arrow to end of selected group to suggest it's tappable
        TextView groupSelector = findViewById(R.id.groupSelector);
        groupSelector.setText(groupName + " ▼");

        // Save most recent group selection so we can restore it on app restart
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting lastGroupSetting = viewModel.getAppDb().appSettingDao().getByName(AppSetting.SETTING_LAST_GROUP);
                if (lastGroupSetting != null) {
                    lastGroupSetting.value = groupName;
                    viewModel.getAppDb().appSettingDao().update(lastGroupSetting);
                } else {
                    lastGroupSetting = new AppSetting(AppSetting.SETTING_LAST_GROUP, groupName);
                    viewModel.getAppDb().appSettingDao().insertAll(lastGroupSetting);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ADD_MEMORY:
                if (resultCode == Activity.RESULT_OK) {
                    viewModel.loadDataAsync(() -> runOnUiThread(() -> memoriesAdapter.notifyDataSetChanged()));
                }
                break;
            case REQUEST_EDIT_MEMORY:
                if (resultCode == Activity.RESULT_OK) {
                    // Add an observer to the model so we know when it's done reloading
                    // the edited memory, so we can tune to it.
                    final int editedMemoryId = data.getExtras().getInt("memoryId");
                    viewModel.loadDataAsync(() -> runOnUiThread(() -> {
                        memoriesAdapter.notifyDataSetChanged();
                        // Tune to the edited memory to force any changes to be applied (e.g. new tone
                        // or frequency).
                        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
                        if (channelMemories != null) {
                            for (int i = 0; i < channelMemories.size(); i++) {
                                if (channelMemories.get(i).memoryId == editedMemoryId) {
                                    viewModel.highlightMemory(channelMemories.get(i));

                                    if (radioAudioService != null) {
                                        radioAudioService.tuneToMemory(channelMemories.get(i));
                                    }
                                }
                            }
                        }
                    }));
                }
                break;
            case REQUEST_SETTINGS:
                if (resultCode == Activity.RESULT_OK && data != null && radioAudioService != null) {
                    applyRadioSettingsResult(data);
                }
                viewModel.loadDataAsync(this::applySettings);
                break;
            case REQUEST_FIRMWARE:
                if (resultCode == Activity.RESULT_OK) {
                    showSimpleSnackbar(getString(R.string.successfully_updated_firmware));
                    // Try to reconnect now that the kv4p HT firmware should be present
                    if (radioAudioService != null) {
                        radioAudioService.renegotiateAfterFlashing();
                    }
                }
                break;
            case REQUEST_FIND_REPEATERS:
                if (resultCode == Activity.RESULT_OK) {
                    viewModel.loadDataAsync(() -> runOnUiThread(() -> memoriesAdapter.notifyDataSetChanged()));
                }
                break;
            default:
                Log.d("DEBUG", "Warning: Returned to MainActivity from unexpected request code: " + requestCode);
        }
    }

    private void showSimpleSnackbar(String msg) {
        Snackbar simpleSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), msg, LENGTH_LONG)
                .setBackgroundTint(getResources().getColor(R.color.primary))
                .setTextColor(getResources().getColor(R.color.medium_gray));

        // Make the text of the snackbar larger.
        TextView snackbarTextView = (TextView) simpleSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        simpleSnackbar.show();
    }

    private void startFirmwareActivity() {
        // Stop any scanning or transmitting
        if (radioAudioService != null) {
            radioAudioService.setScanning(false);
            radioAudioService.endPtt();
        }
        endPttUi();
        setScanningUi(false);

        // Tell the radioAudioService to hold on while we flash.
        radioAudioService.setMode(RadioMode.FLASHING);

        // Actually start the firmware activity
        Intent intent = new Intent("com.vagell.kv4pht.FIRMWARE_ACTION");
        intent.putExtra("requestCode", REQUEST_FIRMWARE);
        startActivityForResult(intent, REQUEST_FIRMWARE);
    }

    private void startFindRepeatersActivity() {
        // Stop any scanning or transmitting
        if (radioAudioService != null) {
            radioAudioService.setScanning(false);
            radioAudioService.endPtt();
        }
        endPttUi();
        setScanningUi(false);

        // Actually start the find repeaters activity
        Intent intent = new Intent("com.vagell.kv4pht.FIND_REPEATERS");
        startActivityForResult(intent, REQUEST_FIND_REPEATERS);
    }

    public void startSettingsActivity() {
        if (radioAudioService != null) {
            radioAudioService.setScanning(false); // Stop scanning when settings brought up, so we don't get in a bad state after.
            radioAudioService.endPtt(); // Be safe, just in case we are somehow transmitting when settings is tapped.
        }
        endPttUi();
        setScanningUi(false);

        Intent intent = new Intent("com.vagell.kv4pht.SETTINGS_ACTION");
        intent.putExtra("requestCode", REQUEST_SETTINGS);
        if (radioAudioService != null && radioAudioService.isRadioConnected()) {
            intent.putExtra("hasHighLowPowerSwitch", radioAudioService.isHasHighLowPowerSwitch());
            intent.putExtra("firmwareVersion", radioAudioService.getRadioModule().getFirmwareVersionNumber());
            intent.putExtra(SettingsActivity.EXTRA_RF_POWER_HIGH, radioAudioService.getRadioModule().isHighPowerEnabled());
            intent.putExtra(SettingsActivity.EXTRA_BANDWIDTH, radioAudioService.getRadioModule().getBandwidthLabel());
            intent.putExtra(SettingsActivity.EXTRA_SQUELCH, radioAudioService.getRadioModule().getDesiredSquelch());
            intent.putExtra(SettingsActivity.EXTRA_FILTER_PRE, radioAudioService.getRadioModule().isPreEmphasisEnabled());
            intent.putExtra(SettingsActivity.EXTRA_FILTER_HIGH, radioAudioService.getRadioModule().isHighpassEnabled());
            intent.putExtra(SettingsActivity.EXTRA_FILTER_LOW, radioAudioService.getRadioModule().isLowpassEnabled());
        }
        startActivityForResult(intent, REQUEST_SETTINGS);
    }

    /**
     * Applies settings screen radio changes as one desired-state update to avoid intermediate firmware writes.
     */
    private void applyRadioSettingsResult(Intent data) {
        radioAudioService.getRadioModule().beginUpdate();
        try {
            radioAudioService.getRadioModule().setHighPower(data.getBooleanExtra(SettingsActivity.EXTRA_RF_POWER_HIGH, true));
            radioAudioService.getRadioModule().setBandwidth(data.getStringExtra(SettingsActivity.EXTRA_BANDWIDTH));
            radioAudioService.getRadioModule().setSquelch(data.getIntExtra(SettingsActivity.EXTRA_SQUELCH, radioAudioService.getRadioModule().getDesiredSquelch()));
            radioAudioService.getRadioModule().setFilters(
                data.getBooleanExtra(SettingsActivity.EXTRA_FILTER_PRE, false),
                data.getBooleanExtra(SettingsActivity.EXTRA_FILTER_HIGH, false),
                data.getBooleanExtra(SettingsActivity.EXTRA_FILTER_LOW, false));
        } finally {
            radioAudioService.getRadioModule().endUpdate();
        }

        // This simple setting is needed by RadioAudioService, but doesn't need to be sent to the module.
        radioAudioService.setAprsPositionIcon(
                SettingsActivity.getAPRSIconFromSettingChoice(
                        getResources(), data.getStringExtra(SettingsActivity.EXTRA_APRS_ICON)));
    }

    public void moreClicked(View view) {
        Context themedContext = new ContextThemeWrapper(this, R.style.Custom_PopupMenu);
        PopupMenu moreMenu = new PopupMenu(themedContext, view);
        moreMenu.inflate(R.menu.more_menu);
        MainActivity activity = this;
        moreMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.import_from_repeaterbook) {
                    startFindRepeatersActivity();
                } else if (item.getItemId() == R.id.flash_firmware) {
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(getString(R.string.flash_firmware_title))
                            .setMessage(getString(R.string.flash_firmware_message))
                            .setPositiveButton(getString(R.string.flash_firmware_button), (d, i) -> {
                                startFirmwareActivity();
                            })
                            .setNegativeButton(getString(R.string.cancel_display), (d, i) -> {
                                // Do nothing.
                            })
                            .show();
                } else if (item.getItemId() == R.id.settings) {
                    startSettingsActivity();
                }
                return true;
            }
        });

        boolean showRadioOptions = radioAudioService != null && radioAudioService.isRadioConnected();
        moreMenu.getMenu().findItem(R.id.flash_firmware).setEnabled(showRadioOptions);
        moreMenu.getMenu().findItem(R.id.import_from_repeaterbook).setEnabled(showRadioOptions);
        moreMenu.show();
    }

    /**
     * Shows a notification to the user.
     *
     * @param notificationChannelId The ID of the notification channel.
     * @param notificationTypeId    The ID for the notification type.
     * @param title                 The title of the notification.
     * @param message               The message content of the notification.
     * @param tapIntentName         The intent action name to handle taps on the notification.
     */
    public void doShowNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {
        if (notificationChannelId == null || title == null || message == null) {
            Log.d("DEBUG", "Unexpected null in showNotification.");
            return;
        }
        // Has the user disallowed notifications?
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
