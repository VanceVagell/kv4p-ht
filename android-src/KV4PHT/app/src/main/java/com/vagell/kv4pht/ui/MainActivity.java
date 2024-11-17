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
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
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
import com.vagell.kv4pht.aprs.parser.InformationField;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.data.AppSetting;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.databinding.ActivityMainBinding;
import com.vagell.kv4pht.firmware.FirmwareUtils;
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

    // Android permission stuff
    private static final int REQUEST_AUDIO_PERMISSION_CODE = 1;
    private static final int REQUEST_NOTIFICATIONS_PERMISSION_CODE = 2;
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
    private RecyclerView recyclerView;
    private MemoriesAdapter adapter;

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

        threadPoolExecutor = new ThreadPoolExecutor(2,
                10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        // Bind data to the UI via the MainViewModel class
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        viewModel.setActivity(this);
        ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setLifecycleOwner(this);
        binding.setVariable(BR.viewModel, viewModel);

        // Prepare a RecyclerView for the list of channel memories
        recyclerView = findViewById(R.id.memoriesList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MemoriesAdapter(new MemoriesAdapter.MemoryListener() {
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
                adapter.notifyDataSetChanged();
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
                                adapter.notifyDataSetChanged();

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
                startActivityForResult(intent, REQUEST_EDIT_MEMORY);
            }
        });
        recyclerView.setAdapter(adapter);

        // Observe the LiveData in MainViewModel (so the RecyclerView can populate with the memories)
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
                adapter.setMemoriesList(channelMemories);
                adapter.notifyDataSetChanged();
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
                    applySettings();
                    findViewById(R.id.pttButton).setClickable(true);
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
            };

            radioAudioService.setCallbacks(callbacks);
            radioAudioService.setChannelMemories(viewModel.getChannelMemories());
            radioAudioService.start();
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
        final String finalString;

        // Reformat the packet to be more human readable.
        InformationField infoField = aprsPacket.getAprsInformation();
        if (infoField.getDataTypeIdentifier() == ':') { // APRS "message" type. What we expect for our text chat.
            MessagePacket messagePacket = new MessagePacket(infoField.getRawBytes(), aprsPacket.getDestinationCall());
            finalString = aprsPacket.getSourceCall() + " to " + messagePacket.getTargetCallsign() + ": " + messagePacket.getMessageBody();
        } else { // Raw APRS packet. Useful for things like monitoring 144.39 for misc APRS traffic.
            // TODO add better implementation of other message types (especially Location and Object, which are common on 144.390MHz).
            finalString = aprsPacket.toString();
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView chatLog = findViewById(R.id.textChatLog);
                chatLog.append(finalString + "\n");

                ScrollView textChatScrollView = findViewById(R.id.textChatScrollView);
                textChatScrollView.fullScroll(View.FOCUS_DOWN);
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
                showCallsignSnackbar();
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

            if (callsignSnackbar != null) {
                callsignSnackbar.dismiss();
            }
        }

        activeScreenType = screenType;
    }

    private void showCallsignSnackbar() {
        CharSequence snackbarMsg = "Set your callsign to send text chat";
        callsignSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_LONG)
                .setAction("Set now", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        settingsClicked(null);
                    }
                })
                .setBackgroundTint(getResources().getColor(R.color.primary))
                .setTextColor(getResources().getColor(R.color.medium_gray))
                .setActionTextColor(getResources().getColor(R.color.black))
                .setAnchorView(findViewById(R.id.textChatInput));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) callsignSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) callsignSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        callsignSnackbar.show();
    }

    public void sendButtonOverlayClicked(View view) {
        if (callsign == null || callsign.length() == 0) {
            showCallsignSnackbar();
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

        if (radioAudioService != null) {
            radioAudioService.sendChatMessage(targetCallsign, outText);
        }

        ((EditText) findViewById(R.id.textChatInput)).setText("");

        TextView chatLog = findViewById(R.id.textChatLog);
        chatLog.append(callsign + " to " + targetCallsign + ": " + outText + "\n");

        ScrollView scrollView = findViewById(R.id.textChatScrollView);
        scrollView.fullScroll(View.FOCUS_DOWN);

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

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting callsignSetting = viewModel.appDb.appSettingDao().getByName("callsign");
                AppSetting squelchSetting = viewModel.appDb.appSettingDao().getByName("squelch");
                AppSetting emphasisSetting = viewModel.appDb.appSettingDao().getByName("emphasis");
                AppSetting highpassSetting = viewModel.appDb.appSettingDao().getByName("highpass");
                AppSetting lowpassSetting = viewModel.appDb.appSettingDao().getByName("lowpass");
                AppSetting stickyPTTSetting = viewModel.appDb.appSettingDao().getByName("stickyPTT");
                AppSetting disableAnimationsSetting = viewModel.appDb.appSettingDao().getByName("disableAnimations");
                AppSetting maxFreqSetting = viewModel.appDb.appSettingDao().getByName("maxFreq");
                AppSetting lastMemoryId = viewModel.appDb.appSettingDao().getByName("lastMemoryId");
                AppSetting lastFreq = viewModel.appDb.appSettingDao().getByName("lastFreq");
                AppSetting lastGroupSetting = viewModel.appDb.appSettingDao().getByName("lastGroup");

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
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
                                activeFrequencyStr = "146.5200"; // VHF calling freq
                            }

                            if (radioAudioService != null) {
                                radioAudioService.setActiveMemoryId(activeMemoryId);
                                radioAudioService.setActiveFrequencyStr(activeFrequencyStr);
                            }
                        }

                        if (maxFreqSetting != null) {
                            int maxFreq = Integer.parseInt(maxFreqSetting.value);
                            RadioAudioService.setMaxFreq(maxFreq); // Called statically so static frequency formatter can use it.
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
                // If the user tries to transmit, stop scanning so we don't
                // move to a different frequency during or after the tx.
                if (radioAudioService != null) {
                    radioAudioService.setScanning(false, false);
                }
                setScanningUi(false);

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
                                    radioAudioService.startPtt(false);
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
                                radioAudioService.startPtt(false);
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
                        radioAudioService.startPtt(false);
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
                } else {
                    // Keyboard is hidden, show the top view
                    frequencyView.setVisibility(View.VISIBLE);
                }
            }
        });
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
        activeFrequencyStr = radioAudioService.validateFrequency(frequencyStr);
        activeMemoryId = -1;

        // Save most recent freq so we can restore it on app restart
        if (threadPoolExecutor == null) {
            return;
        }
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
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
        });

        showMemoryName("Simplex");
        showFrequency(activeFrequencyStr);

        // Unhighlight all memory rows, since this is a simplex frequency.
        viewModel.highlightMemory(null);
        adapter.notifyDataSetChanged();
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
        }
    }

    private void initAudioRecorder() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissions();
            return;
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RadioAudioService.AUDIO_SAMPLE_RATE, channelConfig,
                audioFormat, minBufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.d("DEBUG", "Audio init error");
        }
    }

    private void startRecording() {
        if (audioRecord == null) {
            initAudioRecorder();
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

        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setBackground(getDrawable(R.drawable.ptt_button));
    }

    private void showUSBSnackbar() {
        CharSequence snackbarMsg = "kv4p HT radio not found, plugged in?";
        usbSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
            .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE);

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        usbSnackbar.show();
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
                });

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
                    adapter.notifyDataSetChanged();
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
                                    adapter.notifyDataSetChanged();
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