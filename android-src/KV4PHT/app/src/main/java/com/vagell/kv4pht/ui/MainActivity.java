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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.usb.UsbManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
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


import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    // For transmitting audio to ESP32 / radio
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioFormat = AudioFormat.ENCODING_PCM_8BIT;
    private int minBufferSize = AudioRecord.getMinBufferSize(RadioAudioService.AUDIO_SAMPLE_RATE, channelConfig, audioFormat) * 2;
    private Thread recordingThread;

    // Active screen type (e.g. voice or chat)
    private ScreenType activeScreenType = ScreenType.SCREEN_VOICE;

    // Snackbars
    private Snackbar usbSnackbar = null;
    private Snackbar callsignSnackbar = null;
    private Snackbar versionSnackbar = null;
    private Snackbar radioModuleNotFoundSnackbar = null;

    // Android permission stuff
    private static final int REQUEST_AUDIO_PERMISSION_CODE = 1;
    private static final int REQUEST_NOTIFICATIONS_PERMISSION_CODE = 2;
    private static final int REQUEST_LOCATION_PERMISSION_CODE = 3;
    private static final String ACTION_USB_PERMISSION = "com.vagell.kv4pht.USB_PERMISSION";

    // Radio params and related settings
    private String activeFrequencyStr = "144.0000";
    private int squelch = 0;
    private String callsign = null;
    private boolean stickyPTT = false;
    private boolean disableAnimations = false;

    // Activity callback values
    public static final int REQUEST_ADD_MEMORY = 0;
    public static final int REQUEST_EDIT_MEMORY = 1;
    public static final int REQUEST_SETTINGS = 2;
    public static final int REQUEST_FIRMWARE = 3;

    private MainViewModel viewModel;
    private RecyclerView memoriesRecyclerView;
    private MemoriesAdapter memoriesAdapter;
    private RecyclerView aprsRecyclerView;
    private APRSAdapter aprsAdapter;

    private ThreadPoolExecutor threadPoolExecutor = null;

    private String selectedMemoryGroup = null; // null means unfiltered, no group selected
    private int activeMemoryId = -1; // -1 means we're in simplex mode

    // Audio visualizers
    private Visualizer rxAudioVisualizer = null;
    private static int AUDIO_VISUALIZER_RATE = Visualizer.getMaxCaptureRate();
    private static int MAX_AUDIO_VIZ_SIZE = 500;
    private static int MIN_TX_AUDIO_VIZ_SIZE = 200;
    private static int RECORD_ANIM_FPS = 30;

    // Intents this Activity can handle besides the one that starts it in default mode.
    public static String INTENT_OPEN_CHAT = "com.vagell.kv4pht.OPEN_CHAT_ACTION";

    // The main service that handles USB with the ESP32, incoming and outgoing audio, data, etc.
    private RadioAudioService radioAudioService = null;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        threadPoolExecutor = new ThreadPoolExecutor(2,
                10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        // Bind data to the UI via the MainViewModel class
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        viewModel.setActivity(this);
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
                if (radioAudioService != null && radioAudioService.getMode() == RadioAudioService.MODE_SCAN) {
                    radioAudioService.setScanning(false);
                    setScanningUi(false);
                }
                if (radioAudioService != null) {
                    radioAudioService.tuneToMemory(memory, squelch, false);
                    tuneToMemoryUi(memory.memoryId);
                }

                // Highlight the tapped memory, unhighlight all the others.
                viewModel.highlightMemory(memory);
                memoriesAdapter.notifyDataSetChanged();
            }

            @Override
            public void onMemoryDelete(ChannelMemory memory) {
                String freq = memory.frequency;
                viewModel.setCallback(new MainViewModel.MainViewModelCallback() {
                    @Override
                    public void onDeleteMemoryDone() {
                        viewModel.loadData();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                memoriesAdapter.notifyDataSetChanged();

                                if (radioAudioService != null) {
                                    radioAudioService.tuneToFreq(freq, squelch, false); // Stay on the same freq as the now-deleted memory
                                    tuneToFreqUi(freq);
                                }

                                viewModel.setCallback(null);
                            }
                        });
                    }
                });
                viewModel.deleteMemory(memory);
            }

            @Override
            public void onMemoryEdit(ChannelMemory memory) {
                Intent intent = new Intent("com.vagell.kv4pht.EDIT_MEMORY_ACTION");
                intent.putExtra("requestCode", REQUEST_EDIT_MEMORY);
                intent.putExtra("memoryId", memory.memoryId);
                intent.putExtra("isVhfRadio", (radioAudioService != null && radioAudioService.getRadioType().equals(RadioAudioService.RADIO_MODULE_VHF)));
                startActivityForResult(intent, REQUEST_EDIT_MEMORY);
            }
        });
        memoriesRecyclerView.setAdapter(memoriesAdapter);

        // Observe the channel memories LiveData in MainViewModel (so the RecyclerView can populate with the memories)
        viewModel.getChannelMemories().observe(this, new Observer<List<ChannelMemory>>() {
            @Override
            public void onChanged(List<ChannelMemory> channelMemories) {
                // Update the adapter's data
                if (selectedMemoryGroup != null) {
                    for (int i = 0; i < channelMemories.size(); i++) {
                        if (!channelMemories.get(i).group.equals(selectedMemoryGroup)) {
                            channelMemories.remove(i--);
                        }
                    }
                }
                memoriesAdapter.setMemoriesList(channelMemories);
                memoriesAdapter.notifyDataSetChanged();
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

        requestAudioPermissions();
        requestNotificationPermissions(); // TODO store a boolean in our DB so we only ask for this once (in case they say no)
        attachListeners();

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        viewModel.setCallback(new MainViewModel.MainViewModelCallback() {
            @Override
            public void onLoadDataDone() {
                applySettings();
                viewModel.setCallback(null);
            }
        });
        viewModel.loadData();
    }

    final Context context = this;

    /** Defines callbacks for service binding, passed to bindService(). */
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            RadioAudioService.RadioBinder binder = (RadioAudioService.RadioBinder) service;
            radioAudioService = binder.getService();

            // Give the service other critical things it needs to work properly.
            RadioAudioService.RadioAudioServiceCallbacks callbacks = new RadioAudioService.RadioAudioServiceCallbacks() {
                @Override
                public void radioMissing() {
                    sMeterUpdate(0); // No rx when no radio
                    showUSBSnackbar();
                    findViewById(R.id.pttButton).setClickable(false);
                }

                @Override
                public void radioConnected() {
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
                    applySettings();
                    findViewById(R.id.pttButton).setClickable(true);
                }

                @Override
                public void radioModuleNotFound() {
                    showRadioModuleNotFoundSnackbar();
                }

                @Override
                public void audioTrackCreated() {
                    if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        createRxAudioVisualizer(radioAudioService.getAudioTrack());
                    }
                }

                @Override
                public void packetReceived(APRSPacket aprsPacket) {
                    handleChatPacket(aprsPacket);
                }

                @Override
                public void scannedToMemory(int memoryId) {
                    tuneToMemoryUi(memoryId);
                }

                @Override
                public void outdatedFirmware(int firmwareVer) {
                    showVersionSnackbar(firmwareVer);
                }

                @Override
                public void missingFirmware() {
                    showVersionSnackbar(-1);
                }

                @Override
                public void txAllowed(boolean allowed) {
                    // Only enable the PTT and send chat buttons when tx is allowed (e.g. within ham band).
                    findViewById(R.id.pttButton).setClickable(allowed);
                    findViewById(R.id.sendButton).setClickable(allowed);
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
                public void chatError(String snackbarMsg) {
                    Snackbar snackbar = Snackbar.make(context, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_LONG)
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
                    updateSMeter(value);
                }

                @Override
                public void aprsBeaconing(boolean beaconing, int accuracy) {
                    // If beaconing just started, let user know in case they didn't want this
                    // or forgot they turned it on. And warn them if they haven't set their callsign.
                    if (beaconing && (null == callsign || callsign.trim().length() == 0)) {
                        showCallsignSnackbar("Set your callsign to beacon your position");
                    } else if (beaconing) {
                        showBeaconingOnSnackbar(accuracy);
                    }
                }

                @Override
                public void sentAprsBeacon(double latitude, double longitude) {
                    // Show a mock-up of the beacon we sent, in our own chat log
                    APRSMessage myBeacon = new APRSMessage();
                    myBeacon.type = APRSMessage.POSITION_TYPE;
                    myBeacon.fromCallsign = callsign;
                    myBeacon.positionLat = latitude;
                    myBeacon.positionLong = longitude;
                    myBeacon.timestamp = java.time.Instant.now().getEpochSecond();

                    threadPoolExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            MainViewModel.appDb.aprsMessageDao().insertAll(myBeacon);
                            viewModel.loadData();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    aprsAdapter.notifyDataSetChanged();
                                }
                            });
                        }
                    });
                }

                @Override
                public void unknownLocation() {
                    showSimpleSnackbar("Can't find your location, no beacon sent");
                }

                @Override
                public void forceTunedToFreq(String newFreqStr) {
                    // This is called when RadioAudioService is changing bands, and we need
                    // to reflect that in the UI.
                    tuneToFreqUi(newFreqStr);
                }
            };

            radioAudioService.setCallbacks(callbacks);
            radioAudioService.setChannelMemories(viewModel.getChannelMemories());

            // Can only retrieve moduleType from DB async, so we do that and tell radioAudioService.
            threadPoolExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final AppSetting moduleTypeSetting = viewModel.appDb.appSettingDao().getByName("moduleType");

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            radioAudioService.setRadioType(moduleTypeSetting.value.equals("UHF") ?
                                    RadioAudioService.RADIO_MODULE_UHF : RadioAudioService.RADIO_MODULE_VHF);
                            radioAudioService.start();
                        }
                    });
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            radioAudioService = null;
            Log.d("DEBUG", "RadioAudioService disconnected from MainActivity.");
            // TODO if this is unexpected we should probably try to restart the service.
        }
    };

    @Override
    protected void onStart() {
        super.onStart();

        Intent intent = new Intent(this, RadioAudioService.class);
        intent.putExtra("callsign", callsign);
        intent.putExtra("squelch", squelch);
        intent.putExtra("activeMemoryId", activeMemoryId);
        intent.putExtra("activeFrequencyStr", activeFrequencyStr);

        // Binding to the RadioAudioService causes it to start (e.g. play back audio).
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            if (threadPoolExecutor != null) {
                threadPoolExecutor.shutdownNow();
                threadPoolExecutor = null;
            }
        } catch (Exception e) { }

        try {
            if (radioAudioService != null) {
                radioAudioService.unbindService(connection);
                radioAudioService = null;
            }
        } catch (Exception e) { }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (threadPoolExecutor != null) {
            threadPoolExecutor.shutdownNow();
            threadPoolExecutor = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // TODO unclear why threadPoolExecutor sometimes breaks when we start another activity, but
        // we recreate it here as a workaround.
        threadPoolExecutor = new ThreadPoolExecutor(2,
                10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        viewModel.setCallback(new MainViewModel.MainViewModelCallback() {
            @Override
            public void onLoadDataDone() {
                applySettings();
                viewModel.setCallback(null);
            }
        });
        viewModel.loadData();

        // If we lost reference to the radioAudioService, re-establish it
        if (null == radioAudioService) {
            Intent intent = new Intent(this, RadioAudioService.class);
            intent.putExtra("callsign", callsign);
            intent.putExtra("squelch", squelch);
            intent.putExtra("activeMemoryId", activeMemoryId);
            intent.putExtra("activeFrequencyStr", activeFrequencyStr);

            // Binding to the RadioAudioService causes it to start (e.g. play back audio).
            bindService(intent, connection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // If we arrived here from an APRS text chat notification, open text chat.
        if (intent != null && intent.getAction().equals(INTENT_OPEN_CHAT)) {
            showScreen(ScreenType.SCREEN_CHAT);
        }
    }

    private void handleChatPacket(APRSPacket aprsPacket) {
        // We use duck-typing for APRS messages since the spec is pretty loose with all the ways
        // you can define different fields and values. Once we know the type, we set aprsMessage.type.

        APRSMessage aprsMessage = new APRSMessage();
        InformationField infoField = aprsPacket.getAprsInformation();
        WeatherField weatherField = (WeatherField) infoField.getAprsData(APRSTypes.T_WX);
        PositionField positionField = (PositionField) infoField.getAprsData(APRSTypes.T_POSITION);
        ObjectField objectField = (ObjectField) infoField.getAprsData(APRSTypes.T_OBJECT);
        aprsMessage.timestamp = java.time.Instant.now().getEpochSecond();

        // Get the fromCallsign (all APRS messages must have this)
        aprsMessage.fromCallsign = aprsPacket.getSourceCall();

        // Get the position, if included.
        if (null != positionField) {
            aprsMessage.type = APRSMessage.POSITION_TYPE; // Anything with a position is POSITION_TYPE unless we determine more specific type later.
            aprsMessage.positionLat = positionField.getPosition().getLatitude();
            aprsMessage.positionLong = positionField.getPosition().getLongitude();
        }

        // Try to find a comment (could be at multiple levels in the packet).
        String comment = aprsPacket.getComment();
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
            MessagePacket messagePacket = new MessagePacket(infoField.getRawBytes(), aprsPacket.getDestinationCall());
            aprsMessage.toCallsign = messagePacket.getTargetCallsign();

            if (messagePacket.isAck()) {
                aprsMessage.wasAcknowledged = true;
                try {
                    String msgNumStr = messagePacket.getMessageNumber();
                    if (msgNumStr != null) {
                        aprsMessage.msgNum = Integer.parseInt(msgNumStr.trim());
                    }
                } catch (Exception e) {
                    Log.d("DEBUG", "Warning: Bad message number in APRS ack, ignoring: '" + messagePacket.getMessageNumber() + "'");
                    e.printStackTrace();
                    return;
                }
                // Log.d("DEBUG", "Message ack received");
            } else {
                aprsMessage.msgBody = messagePacket.getMessageBody();
                // Log.d("DEBUG", "Message packet received");
            }
        } else if (infoField.getDataTypeIdentifier() == ';') { // APRS "object"
            aprsMessage.type = APRSMessage.OBJECT_TYPE;
            if (null != objectField) {
                aprsMessage.objName = objectField.getObjectName();
                // Log.d("DEBUG", "Object packet received");
            }
        }

        // If there is a fault in the packet, or the message type is unknown, we at least display the raw contents as a comment.
        if (aprsPacket.hasFault() || aprsMessage.type == APRSMessage.UNKNOWN_TYPE && (null == comment || comment.trim().length() == 0)) {
            if (null != infoField) {
                try {
                    comment = "Raw: " + new String(infoField.getRawBytes(), "UTF-8");
                    aprsMessage.comment = comment;
                } catch (Exception e) { }
            }
        }

        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                APRSMessage oldAPRSMessage = null;
                if (aprsMessage.wasAcknowledged) {
                    // When this is an ack, we don't insert anything in the DB, we try to find that old message to ack it.
                    oldAPRSMessage = MainViewModel.appDb.aprsMessageDao().getMsgToAck(aprsMessage.toCallsign, aprsMessage.msgNum);
                    if (null == oldAPRSMessage) {
                        Log.d("DEBUG", "Can't ack unknown APRS message from: " + aprsMessage.toCallsign + " with msg number: " + aprsMessage.msgNum);
                        return;
                    } else {
                        // Ack an old message
                        oldAPRSMessage.wasAcknowledged = true;
                        MainViewModel.appDb.aprsMessageDao().update(oldAPRSMessage);
                    }
                } else {
                    // Not an ack, add a message
                    MainViewModel.appDb.aprsMessageDao().insertAll(aprsMessage);
                }

                viewModel.loadData();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        aprsAdapter.notifyDataSetChanged();
                    }
                });
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
        findViewById(R.id.voiceModeLineHolder).setVisibility(screenType == ScreenType.SCREEN_CHAT ? View.GONE : View.VISIBLE);
        findViewById(R.id.pttButton).setVisibility(screenType == ScreenType.SCREEN_CHAT ? View.GONE : View.VISIBLE);
        findViewById(R.id.memoriesList).setVisibility(screenType == ScreenType.SCREEN_CHAT ? View.GONE : View.VISIBLE);
        findViewById(R.id.voiceModeBottomControls).setVisibility(screenType == ScreenType.SCREEN_CHAT ? View.GONE : View.VISIBLE);

        // Controls for text mode
        findViewById(R.id.textModeContainer).setVisibility(screenType == ScreenType.SCREEN_CHAT ? View.VISIBLE : View.GONE);

        if (screenType == ScreenType.SCREEN_CHAT) {
            // Stop scanning when we enter chat mode, we don't want to tx data on an unexpected
            // frequency. User must set it manually (or select it before coming to chat mode, but
            // can't be scanning).
            if (radioAudioService != null) {
                radioAudioService.setScanning(false, true);
            }
            setScanningUi(false);

            // If their callsign is not set, display a snackbar asking them to set it before they
            // can transmit.
            if (callsign == null || callsign.length() == 0) {
                showCallsignSnackbar("Set your callsign to send text chat");
                ImageButton sendButton = findViewById(R.id.sendButton);
                sendButton.setEnabled(false);
                findViewById(R.id.sendButtonOverlay).setVisibility(View.VISIBLE);
            } else {
                ImageButton sendButton = findViewById(R.id.sendButton);
                sendButton.setEnabled(true);
                if (callsignSnackbar != null) {
                    callsignSnackbar.dismiss();
                }
                findViewById(R.id.sendButtonOverlay).setVisibility(View.GONE);
            }
        } else if (screenType == ScreenType.SCREEN_VOICE){
            hideKeyboard();
            findViewById(R.id.frequencyContainer).setVisibility(View.VISIBLE);
            findViewById(R.id.rxAudioCircle).setVisibility(View.VISIBLE);

            if (callsignSnackbar != null) {
                callsignSnackbar.dismiss();
            }
        }

        activeScreenType = screenType;
    }

    private void showCallsignSnackbar(CharSequence snackbarMsg) {
        callsignSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setAction("Set now", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        callsignSnackbar.dismiss();
                        settingsClicked(null);
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

    private void showBeaconingOnSnackbar(int accuracy) {
        if (null != usbSnackbar && usbSnackbar.isShown()) { // No radio connected, that's more important.
            return;
        }

        String accuracyStr = (accuracy == RadioAudioService.APRS_POSITION_EXACT) ? "exact" : "approx";
        CharSequence snackbarMsg = "Beaconing your " + accuracyStr + " position on active frequency";
        Snackbar beaconingSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_LONG)
                .setAction("Settings", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        settingsClicked(null);
                    }
                })
                .setBackgroundTint(getResources().getColor(R.color.primary))
                .setTextColor(getResources().getColor(R.color.medium_gray))
                .setActionTextColor(getResources().getColor(R.color.black))
                .setAnchorView(findViewById(R.id.bottomNavigationView));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) beaconingSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) beaconingSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        beaconingSnackbar.show();
    }

    public void sendButtonOverlayClicked(View view) {
        if (callsign == null || callsign.length() == 0) {
            showCallsignSnackbar("Set your callsign to send text chat");
            ImageButton sendButton = findViewById(R.id.sendButton);
            sendButton.setEnabled(false);
        }
    }

    public void sendTextClicked(View view) {
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

        if (threadPoolExecutor == null) {
            return;
        }

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
                MainViewModel.appDb.aprsMessageDao().insertAll(aprsMessage);
                viewModel.loadData();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        aprsAdapter.notifyDataSetChanged();
                    }
                });
            }
        });

        findViewById(R.id.textChatInput).requestFocus();
    }

    private void createRxAudioVisualizer(AudioTrack audioTrack) {
        rxAudioVisualizer = new Visualizer(audioTrack.getAudioSessionId());
        rxAudioVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
            @Override
            public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                if (disableAnimations) { return; }

                float rxVolume = Math.max(0f, (((float) waveform[0] + 128f) / 256) - 0.4f); // 0 to 1
                ImageView rxAudioView = findViewById(R.id.rxAudioCircle);
                ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) rxAudioView.getLayoutParams();
                layoutParams.width = (int) (MAX_AUDIO_VIZ_SIZE * rxVolume);
                layoutParams.height = (int) (MAX_AUDIO_VIZ_SIZE * rxVolume);
                rxAudioView.setLayoutParams(layoutParams);
            }

            @Override
            public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) { }
        }, AUDIO_VISUALIZER_RATE, true, false);
        rxAudioVisualizer.setEnabled(true);
    }

    private void updateRecordingVisualization(int waitMs, byte audioByte) {
        if (disableAnimations) { return; }

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        float txVolume = ((float) audioByte + 128f) / 256; // 0 to 1
                        ImageView txAudioView = findViewById(R.id.txAudioCircle);
                        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) txAudioView.getLayoutParams();
                        int mode = radioAudioService != null ? radioAudioService.getMode() : -1;
                        layoutParams.width = audioByte == RadioAudioService.SILENT_BYTE ||
                                mode == RadioAudioService.MODE_RX ? 0 : (int) (MAX_AUDIO_VIZ_SIZE * txVolume) + MIN_TX_AUDIO_VIZ_SIZE;
                        layoutParams.height = audioByte == RadioAudioService.SILENT_BYTE ||
                                mode == RadioAudioService.MODE_RX ? 0 : (int) (MAX_AUDIO_VIZ_SIZE * txVolume) + MIN_TX_AUDIO_VIZ_SIZE;
                        txAudioView.setLayoutParams(layoutParams);
                    }
                });
            }
        }, waitMs); // waitMs gives us the fps we desire, see RECORD_ANIM_FPS constant.
    }

    private void applySettings() {
        if (viewModel.appDb == null || threadPoolExecutor == null) {
            return; // DB not yet loaded (e.g. radio attached before DB init completed)
        }

        Activity thisActivity = this;

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting callsignSetting = viewModel.appDb.appSettingDao().getByName("callsign");
                AppSetting squelchSetting = viewModel.appDb.appSettingDao().getByName("squelch");
                AppSetting moduleTypeSetting = viewModel.appDb.appSettingDao().getByName("moduleType");
                AppSetting emphasisSetting = viewModel.appDb.appSettingDao().getByName("emphasis");
                AppSetting highpassSetting = viewModel.appDb.appSettingDao().getByName("highpass");
                AppSetting lowpassSetting = viewModel.appDb.appSettingDao().getByName("lowpass");
                AppSetting stickyPTTSetting = viewModel.appDb.appSettingDao().getByName("stickyPTT");
                AppSetting disableAnimationsSetting = viewModel.appDb.appSettingDao().getByName("disableAnimations");
                AppSetting aprsBeaconPosition = viewModel.appDb.appSettingDao().getByName("aprsBeaconPosition");
                AppSetting aprsPositionAccuracy = viewModel.appDb.appSettingDao().getByName("aprsPositionAccuracy");
                AppSetting bandwidthSetting = viewModel.appDb.appSettingDao().getByName("bandwidth");
                AppSetting maxFreqSetting = viewModel.appDb.appSettingDao().getByName("maxFreq");
                AppSetting micGainBoostSetting = viewModel.appDb.appSettingDao().getByName("micGainBoost");
                AppSetting lastMemoryId = viewModel.appDb.appSettingDao().getByName("lastMemoryId");
                AppSetting lastFreq = viewModel.appDb.appSettingDao().getByName("lastFreq");
                AppSetting lastGroupSetting = viewModel.appDb.appSettingDao().getByName("lastGroup");

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // The module type setting is most important, because if it changed then
                        // we need to reconnect to the ESP32 (it had incorrect module type).
                        if (moduleTypeSetting != null) {
                            if (radioAudioService != null) {
                                radioAudioService.setRadioType(moduleTypeSetting.value.equals("UHF") ?
                                        RadioAudioService.RADIO_MODULE_UHF : RadioAudioService.RADIO_MODULE_VHF);
                            }
                        }

                        if (callsignSetting != null) {
                            callsign = callsignSetting.value;

                            // Enable or prevent APRS texting depending on if callsign was set.
                            if (callsign.length() == 0) {
                                findViewById(R.id.sendButton).setEnabled(false);
                                findViewById(R.id.sendButtonOverlay).setVisibility(View.VISIBLE);
                            } else {
                                findViewById(R.id.sendButton).setEnabled(true);
                                findViewById(R.id.sendButtonOverlay).setVisibility(View.GONE);
                            }

                            if (radioAudioService != null) {
                                radioAudioService.setCallsign(callsign);
                            }
                        }

                        if (lastGroupSetting != null && !lastGroupSetting.value.equals("")) {
                            selectMemoryGroup(lastGroupSetting.value);
                        }

                        if (lastMemoryId != null && !lastMemoryId.value.equals("-1")) {
                            activeMemoryId = Integer.parseInt(lastMemoryId.value);

                            if (radioAudioService != null) {
                                radioAudioService.setActiveMemoryId(activeMemoryId);
                            }
                        } else {
                            activeMemoryId = -1;
                            if (lastFreq != null) {
                                activeFrequencyStr = lastFreq.value;
                            } else {
                                activeFrequencyStr = "144.0000";
                            }

                            if (radioAudioService != null) {
                                radioAudioService.setActiveMemoryId(activeMemoryId);
                                radioAudioService.setActiveFrequencyStr(activeFrequencyStr);
                            }
                        }

                        if (bandwidthSetting != null) {
                            if (radioAudioService != null) {
                                radioAudioService.setBandwidth(bandwidthSetting.value);
                            }
                        }

                        if (maxFreqSetting != null) {
                            int maxFreq = Integer.parseInt(maxFreqSetting.value);
                            if (radioAudioService != null) {
                                radioAudioService.setMaxFreq(maxFreq); // Called statically so static frequency formatter can use it.
                            }
                        }

                        if (micGainBoostSetting != null) {
                            String micGainBoost = micGainBoostSetting.value;
                            if (radioAudioService != null) {
                                radioAudioService.setMicGainBoost(micGainBoost);
                            }
                        }

                        if (squelchSetting != null) {
                            squelch = Integer.parseInt(squelchSetting.value); // The tuneTo() calls below pass squelch to RadioAudioService.
                        }
                        if (activeMemoryId > -1) {
                            if (radioAudioService != null) {
                                radioAudioService.tuneToMemory(activeMemoryId, squelch, radioAudioService.getMode() == RadioAudioService.MODE_RX);
                                tuneToMemoryUi(activeMemoryId);
                            }
                        } else {
                            if (radioAudioService != null) {
                                radioAudioService.tuneToFreq(activeFrequencyStr, squelch, radioAudioService.getMode() == RadioAudioService.MODE_RX);
                                tuneToFreqUi(activeFrequencyStr);
                            }
                        }
                        if (radioAudioService != null) {
                            radioAudioService.setSquelch(squelch); // We do this after tuning, so tuneToMemory and tuneToFreq will apply a new squelch if it was changed.
                        }

                        boolean emphasis = true; // Default all filters to on first time app is installed (best audio experience for most users).
                        boolean highpass = true;
                        boolean lowpass = true;
                        if (emphasisSetting != null) {
                            emphasis = Boolean.parseBoolean(emphasisSetting.value);
                        }

                        if (highpassSetting != null) {
                            highpass = Boolean.parseBoolean(highpassSetting.value);
                        }

                        if (lowpassSetting != null) {
                            lowpass = Boolean.parseBoolean(lowpassSetting.value);
                        }

                        final boolean finalEmphasis = emphasis;
                        final boolean finalHighpass = highpass;
                        final boolean finalLowpass = lowpass;

                        if (threadPoolExecutor != null) { // Could be null if app is in background, and user is just listening to scanning.
                            threadPoolExecutor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    if (radioAudioService != null) {
                                        if (radioAudioService.getMode() != RadioAudioService.MODE_STARTUP &&
                                                radioAudioService.getMode() != RadioAudioService.MODE_SCAN) {
                                            radioAudioService.setMode(RadioAudioService.MODE_RX);
                                            radioAudioService.setFilters(finalEmphasis, finalHighpass, finalLowpass);
                                        }
                                    }
                                }
                            });
                        }

                        if (stickyPTTSetting != null) {
                            stickyPTT = Boolean.parseBoolean(stickyPTTSetting.value);
                        }

                        if (disableAnimationsSetting != null) {
                            disableAnimations = Boolean.parseBoolean(disableAnimationsSetting.value);
                            if (disableAnimations) {
                                // Hide the rx audio visualization
                                ImageView rxAudioView = findViewById(R.id.rxAudioCircle);
                                ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) rxAudioView.getLayoutParams();
                                layoutParams.width = 0;
                                layoutParams.height = 0;
                                rxAudioView.setLayoutParams(layoutParams);

                                // Hide the tx audio visualization
                                updateRecordingVisualization(100, RadioAudioService.SILENT_BYTE);
                            }
                        }

                        // Get this first, since we show a butter if beaconing is enabled afterwards, and want to include accuracy in it.
                        if (aprsPositionAccuracy != null) {
                            if (threadPoolExecutor != null)
                                threadPoolExecutor.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (radioAudioService != null) {
                                            radioAudioService.setAprsPositionAccuracy(
                                                    aprsPositionAccuracy.value.equals("Exact") ?
                                                            RadioAudioService.APRS_POSITION_EXACT :
                                                            RadioAudioService.APRS_POSITION_APPROX);
                                        }
                                    }
                                });
                        }

                        if (aprsBeaconPosition != null) {
                            if (threadPoolExecutor != null)
                                threadPoolExecutor.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (radioAudioService != null) {
                                            radioAudioService.setAprsBeaconPosition(Boolean.parseBoolean(aprsBeaconPosition.value));
                                        }
                                    }
                                });

                            if (Boolean.parseBoolean(aprsBeaconPosition.value)) {
                                requestPositionPermissions();
                            }
                        }
                    }
                });
            }
        });
    }

    private void attachListeners() {
        final Context ctx = this;

        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                boolean touchHandled = false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!((ImageButton) v).isClickable()) {
                            touchHandled = true;
                            break;
                        }
                        if (stickyPTT) {
                            if (radioAudioService != null && radioAudioService.getMode() == RadioAudioService.MODE_RX) {
                                ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                                if (radioAudioService != null) {
                                    // If the user tries to transmit, stop scanning so we don't
                                    // move to a different frequency during or after the tx.
                                    radioAudioService.setScanning(false, false);
                                    setScanningUi(false);
                                    radioAudioService.startPtt();
                                }
                                startPttUi(false);
                            } else if (radioAudioService != null && radioAudioService.getMode() == RadioAudioService.MODE_TX) {
                                ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                                if (radioAudioService != null) {
                                    radioAudioService.endPtt();
                                }
                                endPttUi();
                            }
                        } else {
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
                        touchHandled = true;
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!((ImageButton) v).isClickable()) {
                            touchHandled = true;
                            break;
                        }
                        if (!stickyPTT) {
                            if (radioAudioService != null) {
                                radioAudioService.endPtt();
                            }
                            endPttUi();
                        }
                        touchHandled = true;
                        break;
                }

                return touchHandled;
            }
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

                if (radioAudioService != null && radioAudioService.getMode() == RadioAudioService.MODE_RX) {
                    ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                    if (radioAudioService != null) {
                        radioAudioService.startPtt();
                    }
                    startPttUi(false);
                } else if (radioAudioService != null && radioAudioService.getMode() == RadioAudioService.MODE_TX) {
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
                    radioAudioService.tuneToFreq(activeFrequencyField.getText().toString(), squelch, false);
                    tuneToFreqUi(RadioAudioService.makeSafe2MFreq(activeFrequencyField.getText().toString())); // Fixes any invalid freq user may have entered.
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
                    frequencyView.setVisibility(View.GONE);
                    rxAudioCircleView.setVisibility(View.GONE);
                } else {
                    // Keyboard is hidden, show the top view
                    frequencyView.setVisibility(View.VISIBLE);
                    rxAudioCircleView.setVisibility(View.VISIBLE);
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
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (null != imm) {
            imm.hideSoftInputFromWindow(findViewById(R.id.mainTopLevelLayout).getWindowToken(), 0);
        }
    }

    /**
     * Updates the UI to represent that we've tuned to the given frequency. Does not actually
     * interact with the radio (use RadioAudioService for that).
     */
    private void tuneToFreqUi(String frequencyStr) {
        final Context ctx = this;
        activeFrequencyStr = radioAudioService.validateFrequency(frequencyStr);
        activeMemoryId = -1;

        // Save most recent freq so we can restore it on app restart
        if (threadPoolExecutor == null) {
            return;
        }
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                synchronized(ctx) { // Avoid 2 threads checking if something is set / setting it at once.
                    AppSetting lastFreqSetting = viewModel.appDb.appSettingDao().getByName("lastFreq");
                    if (lastFreqSetting != null) {
                        lastFreqSetting.value = frequencyStr;
                        viewModel.appDb.appSettingDao().update(lastFreqSetting);
                    } else {
                        lastFreqSetting = new AppSetting("lastFreq", frequencyStr);
                        viewModel.appDb.appSettingDao().insertAll(lastFreqSetting);
                    }

                    // And clear out any saved memory ID, so we restore to a simplex freq on restart.
                    AppSetting lastMemoryIdSetting = viewModel.appDb.appSettingDao().getByName("lastMemoryId");
                    if (lastMemoryIdSetting != null) {
                        lastMemoryIdSetting.value = "-1";
                        viewModel.appDb.appSettingDao().update(lastMemoryIdSetting);
                    } else {
                        lastMemoryIdSetting = new AppSetting("lastMemoryId", "-1");
                        viewModel.appDb.appSettingDao().insertAll(lastMemoryIdSetting);
                    }
                }
            }
        });

        showMemoryName("Simplex");
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
                activeFrequencyStr = radioAudioService.validateFrequency(channelMemories.get(i).frequency);
                activeMemoryId = memoryId;

                showMemoryName(channelMemories.get(i).name);
                showFrequency(activeFrequencyStr);

                // Save most recent memory so we can restore it on app restart
                if (threadPoolExecutor != null) { // Could be null if user is just listening to scan in another app, etc.
                    threadPoolExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            AppSetting lastMemoryIdSetting = viewModel.appDb.appSettingDao().getByName("lastMemoryId");
                            if (lastMemoryIdSetting != null) {
                                lastMemoryIdSetting.value = "" + memoryId;
                                viewModel.appDb.appSettingDao().update(lastMemoryIdSetting);
                            } else {
                                lastMemoryIdSetting = new AppSetting("lastMemoryId", "" + memoryId);
                                viewModel.appDb.appSettingDao().insertAll(lastMemoryIdSetting);
                            }
                        }
                    });
                }
                return;
            }
        }
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
                Log.d("DEBUG", "showFrequency: " + frequency);
                EditText activeFrequencyField = findViewById(R.id.activeFrequency);
                activeFrequencyField.setText(frequency);
                activeFrequencyStr = frequency;
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

    protected void requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs the audio recording permission")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.RECORD_AUDIO},
                                        REQUEST_AUDIO_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_AUDIO_PERMISSION_CODE);
            }
        } else {
            // Permission has already been granted
        }
    }

    protected void requestPositionPermissions() {
        // Check that the user allows our app to get position, otherwise ask for the permission.
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs the fine location permission")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                        REQUEST_LOCATION_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_LOCATION_PERMISSION_CODE);
            }
        }
    }

    protected void requestNotificationPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.POST_NOTIFICATIONS)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs to be able to send notifications")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                        REQUEST_NOTIFICATIONS_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS_PERMISSION_CODE);
            }
        } else {
            // Permission has already been granted
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case REQUEST_AUDIO_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                    if (radioAudioService != null) {
                        AudioTrack audioTrack = radioAudioService.getAudioTrack();
                        if (audioTrack != null) {
                            createRxAudioVisualizer(audioTrack); // Visualizer requires RECORD_AUDIO permission (even if not visualizing the mic input).
                        }
                    }
                    initAudioRecorder();
                } else {
                    // Permission denied, things will just be broken.
                    Log.d("DEBUG", "Error: Need audio permission");
                }
                return;
            }
            case REQUEST_NOTIFICATIONS_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                } else {
                    // Permission denied
                    Log.d("DEBUG", "Warning: Need notifications permission to be able to send APRS chat message notifications");
                }
                return;
            }
            case REQUEST_LOCATION_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                } else {
                    // Permission denied
                    Log.d("DEBUG", "Warning: Need fine location permission to include in APRS messages (user turned this setting on)");
                }
                return;
            }
        }
    }

    private void initAudioRecorder() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissions();
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

        // Whenever we start recording we immediately silence any audio playback so the mic
        // doesn't pick it up.
        radioAudioService.getAudioTrack().pause();
        radioAudioService.getAudioTrack().flush();

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
        while (isRecording) {
            byte[] audioBuffer = new byte[minBufferSize];
            int bytesRead;
            bytesRead = audioRecord.read(audioBuffer, 0, minBufferSize);

            if (bytesRead > 0) {
                if (radioAudioService != null) {
                    radioAudioService.sendAudioToESP32(Arrays.copyOfRange(audioBuffer, 0, bytesRead), false);

                    int bytesPerAnimFrame = RadioAudioService.AUDIO_SAMPLE_RATE / RECORD_ANIM_FPS;
                    long audioChunkByteTotal = 0;
                    int waitMs = 0;
                    for (int i = 0; i < bytesRead; i++) {
                        if (i > 0 && i % bytesPerAnimFrame == 0) {
                            audioChunkByteTotal += audioBuffer[i];
                            updateRecordingVisualization(waitMs, (byte) (audioChunkByteTotal / bytesPerAnimFrame));
                            waitMs += (1.0f / RECORD_ANIM_FPS * 1000);
                            audioChunkByteTotal = 0;
                        } else {
                            audioChunkByteTotal += audioBuffer[i];
                        }
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
            updateRecordingVisualization(100, RadioAudioService.SILENT_BYTE);
        }

        radioAudioService.getAudioTrack().play();

        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setBackground(getDrawable(R.drawable.ptt_button));
    }

    private void showUSBSnackbar() {
        CharSequence snackbarMsg = "kv4p HT radio not found, plugged in?";
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

    private void showRadioModuleNotFoundSnackbar() {
        CharSequence snackbarMsg = "Radio module not responding to ESP32, check PCB solder joints";
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
        CharSequence snackbarMsg = firmwareVer == -1 ? "No firmware installed" : "New firmware available";
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
            Log.d("DEBUG", "usbReceiver.onReceive()");

            String action = intent.getAction();
            synchronized (this) {
                if (ACTION_USB_PERMISSION.equals(action) || UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
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
        setScanningUi((radioAudioService != null) && (radioAudioService.getMode()) != RadioAudioService.MODE_SCAN); // Toggle scanning on/off
        if (radioAudioService != null) {
            radioAudioService.setScanning(radioAudioService.getMode() != RadioAudioService.MODE_SCAN, true);
        }
    }

    public void singleBeaconButtonClicked(View view) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPositionPermissions();
            return;
        }

        if (null != radioAudioService) {
            if (null == callsign || callsign.trim().length() == 0) {
                showCallsignSnackbar("Set your callsign to beacon your position");
                return;
            }

            radioAudioService.sendPositionBeacon();
        }
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
                    scanButton.setText("SCAN");
                }
            });

            // If squelch was off before we started scanning, turn it off again
            if (squelch == 0) {
                tuneToMemoryUi(activeMemoryId);
            }
        } else { // Start scanning
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanButton.setText("STOP SCAN");
                }
            });
        }
    }

    public void addMemoryClicked(View view) {
        Intent intent = new Intent("com.vagell.kv4pht.ADD_MEMORY_ACTION");
        intent.putExtra("requestCode", REQUEST_ADD_MEMORY);
        intent.putExtra("activeFrequencyStr", activeFrequencyStr);
        intent.putExtra("selectedMemoryGroup", selectedMemoryGroup);
        intent.putExtra("isVhfRadio", (radioAudioService != null && radioAudioService.getRadioType().equals(RadioAudioService.RADIO_MODULE_VHF)));

        startActivityForResult(intent, REQUEST_ADD_MEMORY);
    }

    public void groupSelectorClicked(View view) {
        Context themedContext = new ContextThemeWrapper(this, R.style.Custom_PopupMenu);
        PopupMenu groupsMenu = new PopupMenu(themedContext, view);
        groupsMenu.inflate(R.menu.groups_menu);

        if (threadPoolExecutor == null) {
            return;
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<String> memoryGroups = MainViewModel.appDb.channelMemoryDao().getGroups();
                for (int i = 0; i < memoryGroups.size(); i++) {
                    String groupName = memoryGroups.get(i);
                    if (groupName != null && groupName.trim().length() > 0) {
                        groupsMenu.getMenu().add(memoryGroups.get(i));
                    }
                }

                groupsMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        selectMemoryGroup(item.getTitle().toString());
                        return true;
                    }
                });

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        groupsMenu.show();
                    }
                });
            }
        });
    }

    private void selectMemoryGroup(String groupName) {
        this.selectedMemoryGroup = groupName.equals("All memories") ? null : groupName;
        viewModel.loadData();

        // Add drop-down arrow to end of selected group to suggest it's tappable
        TextView groupSelector = findViewById(R.id.groupSelector);
        groupSelector.setText(groupName + " ▼");

        // Save most recent group selection so we can restore it on app restart
        if (threadPoolExecutor == null) {
            return;
        }
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting lastGroupSetting = viewModel.appDb.appSettingDao().getByName("lastGroup");
                if (lastGroupSetting != null) {
                    lastGroupSetting.value = groupName == null ? "" : groupName;
                    viewModel.appDb.appSettingDao().update(lastGroupSetting);
                } else {
                    lastGroupSetting = new AppSetting("lastGroup", "" + groupName == null ? "" : groupName);
                    viewModel.appDb.appSettingDao().insertAll(lastGroupSetting);
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
                    viewModel.loadData();
                    memoriesAdapter.notifyDataSetChanged();
                }
                break;
            case REQUEST_EDIT_MEMORY:
                if (resultCode == Activity.RESULT_OK) {
                    // Add an observer to the model so we know when it's done reloading
                    // the edited memory, so we can tune to it.
                    final int editedMemoryId = data.getExtras().getInt("memoryId");
                    viewModel.setCallback(new MainViewModel.MainViewModelCallback() {
                        @Override
                        public void onLoadDataDone() {
                            super.onLoadDataDone();

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    memoriesAdapter.notifyDataSetChanged();
                                    // Tune to the edited memory to force any changes to be applied (e.g. new tone
                                    // or frequency).
                                    List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
                                    for (int i = 0; i < channelMemories.size(); i++) {
                                        if (channelMemories.get(i).memoryId == editedMemoryId) {
                                            viewModel.highlightMemory(channelMemories.get(i));

                                            if (radioAudioService != null) {
                                                radioAudioService.tuneToMemory(channelMemories.get(i), squelch, false);
                                            }

                                            tuneToMemoryUi(channelMemories.get(i).memoryId);
                                        }
                                    }
                                    viewModel.setCallback(null);
                                }
                            });
                        }
                    });
                    viewModel.loadData();
                }
                break;
            case REQUEST_SETTINGS:
                // Don't need to do anything here, since settings are applied in onResume() anyway.
                break;
            case REQUEST_FIRMWARE:
                if (resultCode == Activity.RESULT_OK) {
                    showSimpleSnackbar("Successfully updated firmware");

                    // Try to reconnect now that the kv4p HT firmware should be present
                    if (null != radioAudioService) {
                        radioAudioService.reconnectViaUSB();
                    }
                }
                break;
            default:
                Log.d("DEBUG", "Warning: Returned to MainActivity from unexpected request code: " + requestCode);
        }
    }

    private void showSimpleSnackbar(String msg) {
        Snackbar simpleSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), msg, Snackbar.LENGTH_LONG)
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
        radioAudioService.setMode(RadioAudioService.MODE_FLASHING);

        // Actually start the firmware activity
        Intent intent = new Intent("com.vagell.kv4pht.FIRMWARE_ACTION");
        intent.putExtra("requestCode", REQUEST_FIRMWARE);
        startActivityForResult(intent, REQUEST_FIRMWARE);
    }

    public void settingsClicked(View view) {
        if (radioAudioService != null) {
            radioAudioService.setScanning(false); // Stop scanning when settings brought up, so we don't get in a bad state after.
            radioAudioService.endPtt(); // Be safe, just in case we are somehow transmitting when settings is tapped.
        }
        endPttUi();
        setScanningUi(false);

        Intent intent = new Intent("com.vagell.kv4pht.SETTINGS_ACTION");
        intent.putExtra("requestCode", REQUEST_SETTINGS);
        startActivityForResult(intent, REQUEST_SETTINGS);
    }
}