package com.vagell.kv4pht.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDeviceConnection;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.vagell.kv4pht.BR;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.databinding.ActivityMainBinding;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    // Must match the ESP32 device we support.
    // Idx 0 matches https://www.amazon.com/gp/product/B08D5ZD528
    // Idx 1 matches https://www.adafruit.com/product/5348
    public static final int[] ESP32_VENDOR_IDS = {4292, 9114};
    public static final int[] ESP32_PRODUCT_IDS = {60000, 33041};

    // For transmitting audio to ESP32 / radio
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private static final int AUDIO_SAMPLE_RATE = 8000;
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioFormat = AudioFormat.ENCODING_PCM_8BIT;
    private int minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, audioFormat) * 2; // Use twice minimum to avoid overruns
    private Thread recordingThread;
    private UsbManager usbManager;
    private UsbDevice esp32Device;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager usbIoManager;

    // For receiving audio from ESP32 / radio
    private AudioTrack audioTrack;
    private static final int PRE_BUFFER_SIZE = 2000;
    private byte[] rxBytesPrebuffer = new byte[PRE_BUFFER_SIZE];
    private int rxPrebufferIdx = 0;
    private boolean prebufferComplete = false;
    private static final float SEC_BETWEEN_SCANS = 0.5f; // how long to wait during silence to scan to next frequency in scan mode

    // Delimiter must match ESP32 code
    private static final byte[] COMMAND_DELIMITER = new byte[] {(byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00};

    private static final int REQUEST_AUDIO_PERMISSION_CODE = 1;

    private static final String ACTION_USB_PERMISSION = "com.vagell.kv4pht.USB_PERMISSION";

    private static final int MODE_RX = 0;
    private static final int MODE_TX = 1;
    private static final int MODE_SCAN = 2;
    private int mode = MODE_RX;

    // Radio params
    private String activeFrequencyStr = "144.0000";

    // Activity callback values
    public static final int REQUEST_ADD_MEMORY = 0;
    public static final int REQUEST_EDIT_MEMORY = 1;

    private Map<String, Integer> mTones = new HashMap<>();

    private MainViewModel viewModel;
    private RecyclerView recyclerView;
    private MemoriesAdapter adapter;

    private static final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2,
            2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

    private String selectedMemoryGroup = null; // null means unfiltered, no group selected
    private int activeMemoryId = -1; // -1 means we're in simplex mode
    private int consecutiveSilenceBytes = 0; // To determine when to move scan after silence

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
                tuneToMemory(memory);

                // Highlight the tapped memory, unhighlight all the others.
                viewModel.highlightMemory(memory);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onMemoryDelete(ChannelMemory memory) {
                String freq = memory.frequency;
                viewModel.deleteMemory(memory);
                viewModel.loadData();
                adapter.notifyDataSetChanged();
                tuneToFreq(freq); // Stay on the same freq as the now-deleted memory
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

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        requestAudioPermissions();
        findESP32Device();

        attachListeners();
        initAudioTrack();

        setupTones();

        viewModel.loadData();
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

    private void attachListeners() {
        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                boolean touchHandled = false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startPtt();
                        touchHandled = true;
                        break;
                    case MotionEvent.ACTION_UP:
                        endPtt();
                        touchHandled = true;
                        break;
                }

                return touchHandled;
            }
        });

        EditText activeFrequencyField = findViewById(R.id.activeFrequency);
        activeFrequencyField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                tuneToFreq(activeFrequencyField.getText().toString());
                hideKeyboard();
                activeFrequencyField.clearFocus();
                return true;
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(this.getCurrentFocus().getWindowToken(), 0);
    }

    // Reformats the given frequency as "xxx.xxx" and ensures it's the 2m US amateur band.
    // If the given frequency is unsalvageable, returns activeFrequencyStr.
    private String validateFrequency(String tempFrequency) {
        String newFrequency = padFrequency(tempFrequency);

        // Resort to the old frequency, the one the user inputted is unsalvageable.
        return newFrequency == null ? activeFrequencyStr : newFrequency;
    }

    private String padFrequency(String tempFrequency) {
        tempFrequency = tempFrequency.trim();

        // Pad any missing zeroes to match format expected by radio module.
        if (tempFrequency.matches("14[4-8]\\.[0-9][0-9][0-9]")) {
            return tempFrequency;
        } else if (tempFrequency.matches("14[4-8]\\.[0-9][0-9]")) {
            return tempFrequency + "0";
        } else if (tempFrequency.matches("14[4-8]\\.[0-9]")) {
            return tempFrequency + "00";
        } else if (tempFrequency.matches("14[4-8]\\.")) {
            return tempFrequency + "000";
        } else if (tempFrequency.matches("14[4-8]")) {
            return tempFrequency + ".000";
        } else if (tempFrequency.matches("14[4-8][0-9][0-9][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 6);
        } else if (tempFrequency.matches("14[4-8][0-9][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 5) + "0";
        } else if (tempFrequency.matches("14[4-8][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 4) + "00";
        }

        return null;
    }

    // Tell microcontroller to tune to the given frequency string, which must already be formatted
    // in the style the radio module expects.
    private void tuneToFreq(String frequencyStr) {
        mode = MODE_RX;
        activeFrequencyStr = validateFrequency(frequencyStr);
        activeMemoryId = -1;

        sendCommandToESP32(ESP32Command.TUNE_TO, makeSafe2MFreq(activeFrequencyStr) + makeSafe2MFreq(activeFrequencyStr) + "00"); // tx, rx, tone

        showMemoryName("Simplex");
        showFrequency(activeFrequencyStr);

        // Unhighlight all memory rows, since this is a simplex frequency.
        viewModel.highlightMemory(null);
        adapter.notifyDataSetChanged();

        // Reset audio prebuffer
        prebufferComplete = false;
        rxPrebufferIdx = 0;
    }

    private void tuneToMemory(int memoryId) {
        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == memoryId) {
                tuneToMemory(channelMemories.get(i));
                return;
            }
        }
    }

    private void tuneToMemory(ChannelMemory memory) {
        // TODO if user tapped on a memory explicitly during scan, stop scan and enter RX mode.

        activeFrequencyStr = validateFrequency(memory.frequency);
        activeMemoryId = memory.memoryId;

        sendCommandToESP32(ESP32Command.TUNE_TO,
                getTxFreq(memory.frequency, memory.offset) + makeSafe2MFreq(memory.frequency) + getToneIdxStr(memory.tone));

        showMemoryName(memory.name);
        showFrequency(activeFrequencyStr);

        // Reset audio prebuffer
        prebufferComplete = false;
        rxPrebufferIdx = 0;
    }

    private void checkScanDueToSilence() {
        // Note that we handle scanning explicitly like this rather than using dra->scan() because
        // as best I can tell the DRA818v chip has a defect where it always returns "S=1" (which
        // means there is no signal detected on the given frequency) even when there is. I did
        // extensive debugging and even rewrote large portions of the DRA818v library to determine
        // that this was the case. So in lieu of that, we scan using a timing/silence-based system.
        if (consecutiveSilenceBytes >= (AUDIO_SAMPLE_RATE * SEC_BETWEEN_SCANS)) { // 8kHz*3sec is 24kb (3 seconds of silence)
            consecutiveSilenceBytes = 0;
            nextScan();
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

    private String getTxFreq(String txFreq, int offset) {
        if (offset == ChannelMemory.OFFSET_NONE) {
            return txFreq;
        } else {
            Float freqFloat = Float.parseFloat(txFreq);
            if (offset == ChannelMemory.OFFSET_UP) {
                freqFloat += .600f;
            } else if (offset == ChannelMemory.OFFSET_DOWN){
                freqFloat -= .600f;
            }
            return makeSafe2MFreq(freqFloat.toString());
        }
    }

    private String makeSafe2MFreq(String strFreq) {
        Float freq = Float.parseFloat(strFreq);
        freq = Math.min(freq, 148.0f);
        freq = Math.max(freq, 144.0f);

        strFreq = String.format(java.util.Locale.US,"%.3f", freq);
        strFreq = padFrequency(strFreq);

        return strFreq;
    }

    private String getToneIdxStr(String toneStr) {
        if (toneStr == null) {
            toneStr = "None";
        }

        Integer toneIdx = mTones.get(toneStr);

        return toneIdx < 10 ? "0" + toneIdx : toneIdx.toString();
    }

    private void initAudioTrack() {
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
                .build();
    }

    protected void startPtt() {
        if (mode == MODE_TX) {
            return;
        }
        mode = MODE_TX;
        sendCommandToESP32(ESP32Command.PTT_DOWN);
        startRecording();
        audioTrack.stop();
    }

    protected void endPtt() {
        if (mode == MODE_RX) {
            return;
        }
        mode = MODE_RX;
        sendCommandToESP32(ESP32Command.PTT_UP);
        stopRecording();
        audioTrack.flush();
        prebufferComplete = false;
        rxPrebufferIdx = 0;
    }

    protected void requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission Needed")
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

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case REQUEST_AUDIO_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                    initAudioRecorder();
                } else {
                    // Permission denied, things will just be broken.
                    debugLog("Error: Need audio permission");
                }
                return;
            }
        }
    }

    private void debugLog(String text) {
        Log.d("DEBUG", text);
    }
    private void initAudioRecorder() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissions();
            return;
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AUDIO_SAMPLE_RATE, channelConfig,
                audioFormat, minBufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            debugLog("Audio init error");
        }
    }

    private void startRecording() {
        if (audioRecord == null) {
            initAudioRecorder();
        }

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
                sendAudioToESP32(Arrays.copyOfRange(audioBuffer, 0, bytesRead));
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
        }
    }

    private void findESP32Device() {
        debugLog("findESP32Device()");

        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();

        for (UsbDevice device : usbDevices.values()) {
            // Check for device's vendor ID and product ID
            if (isESP32Device(device)) {
                esp32Device = device;
                break;
            }
        }

        if (esp32Device == null) {
            debugLog("No ESP32 detected");
            showUSBRetrySnackbar();
        } else {
            debugLog("Found ESP32.");
            setupSerialConnection();
        }
    }

    private void showUSBRetrySnackbar() {
        CharSequence snackbarMsg = "KV4P-HT radio not found, plugged in?";
        CharSequence buttonLabel = "RETRY";
        Snackbar snackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setAction(buttonLabel, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        findESP32Device();
                    }
                }).setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE);

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        snackbar.show();
    }

    private boolean isESP32Device(UsbDevice device) {
        debugLog("isESP32Device()");

        int vendorId = device.getVendorId();
        int productId = device.getProductId();
        debugLog("vendorId: " + vendorId + " productId: " + productId + " name: " + device.getDeviceName());
        for (int i = 0; i < ESP32_VENDOR_IDS.length; i++) {
            if ((vendorId == ESP32_VENDOR_IDS[i]) && (productId == ESP32_PRODUCT_IDS[i])) {
                return true;
            }
        }
        return false;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            debugLog("usbReceiver.onReceive()");

            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    setupSerialConnection();
                }
            }
        }
    };

    private void setupSerialConnection() {
        debugLog("setupSerialConnection()");

        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            debugLog("Error: no available USB drivers.");
            showUSBRetrySnackbar();
            return;
        }

        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            debugLog("Error: couldn't open USB device.");
            showUSBRetrySnackbar();
            return;
        }

        serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        debugLog("serialPort: " + serialPort);
        try {
            serialPort.open(connection);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (IOException e) {
            debugLog("Error: couldn't open USB serial port.");
            showUSBRetrySnackbar();
            return;
        }

        try { // These settings needed for better data transfer on Adafruit QT Py ESP32-S2
            serialPort.setRTS(true);
            serialPort.setDTR(true);
        } catch (IOException e) {
            // Ignore, may not be supported on all devices.
        }

        usbIoManager = new SerialInputOutputManager(serialPort, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                handleESP32Data(data);
            }

            @Override
            public void onRunError(Exception e) {
                debugLog("Error reading from ESP32.");
                connection.close();
                try {
                    serialPort.close();
                } catch (IOException ex) {
                    // Ignore.
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                findESP32Device(); // Attempt to reconnect after the brief pause above.
                return;
            }
        });
        usbIoManager.setWriteBufferSize(1000); // Must not exceed receive buffer set on ESP32 (so we don't overflow it)
        usbIoManager.setReadBufferSize(1000); // Must be much larger than ESP32's send buffer (so we never block it)
        usbIoManager.setReadTimeout(1000); // Must not be 0 (infinite) or it may block on read() until a write() occurs.
        usbIoManager.start();

        debugLog("Connected to ESP32.");

        // Do things with the ESP32 that we were waiting to do.
        initAfterESP32Connected();
    }

    private void initAfterESP32Connected() {
        // Start by prebuffering some audio
        prebufferComplete = false;
        rxPrebufferIdx = 0;

        // Always start on VHF calling frequency
        // TODO instead start on the previous frequency or memory from last use
        tuneToFreq("146.520");
    }

    public void scanClicked(View view) {
        // Stop scanning
        if (mode == MODE_SCAN) {
            mode = MODE_RX;
            // TODO if squelch was off before we started scanning, turn it off again
        } else { // Start scanning
            mode = MODE_SCAN;
            nextScan();
        }
    }

    private void nextScan() {
        if (mode != MODE_SCAN) {
            return;
        }

        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
        ChannelMemory memoryToScanNext = null;

        // If we're in simplex, start by scanning to the first memory
        if (activeMemoryId == -1) {
            memoryToScanNext = channelMemories.get(0);
        }

        if (memoryToScanNext == null) {
            // Find the next memory after the one we last scanned
            for (int i = 0; i < channelMemories.size() - 1; i++) {
                if (channelMemories.get(i).memoryId == activeMemoryId) {
                    memoryToScanNext = channelMemories.get(i + 1);
                    break;
                }
            }
        }

        if (memoryToScanNext == null) {
            // If we hit the end of memories, go back to scanning from the start
            memoryToScanNext = channelMemories.get(0);
        }

        consecutiveSilenceBytes = 0;

        // debugLog("Scanning to: " + memoryToScanNext.name);
        tuneToMemory(memoryToScanNext);
    }

    public void importMemoriesClicked(View view) {
        // TODO let user select CSV file from Repeater Book to import
    }

    public void addMemoryClicked(View view) {
        Intent intent = new Intent("com.vagell.kv4pht.ADD_MEMORY_ACTION");
        intent.putExtra("requestCode", REQUEST_ADD_MEMORY);
        startActivityForResult(intent, REQUEST_ADD_MEMORY);
    }

    public void groupSelectorClicked(View view) {
        PopupMenu groupsMenu = new PopupMenu(this, view);
        groupsMenu.inflate(R.menu.groups_menu);

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<String> memoryGroups = MainViewModel.appDb.channelMemoryDao().getGroups();
                for (int i = 0; i < memoryGroups.size(); i++) {
                    groupsMenu.getMenu().add(memoryGroups.get(i));
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
                                            tuneToMemory(channelMemories.get(i));
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
            default:
                debugLog("Warning: Returned to MainActivity from unexpected request code: " + requestCode);
        }
    }

    public void settingsClicked(View view) {
        // TODO
    }

    private enum ESP32Command {
        PTT_DOWN((byte) 1),
        PTT_UP((byte) 2),
        TUNE_TO((byte) 3); // paramsStr contains freq, offset, tone details

        private byte commandByte;
        ESP32Command(byte commandByte) {
            this.commandByte = commandByte;
        }

        public byte getByte() {
            return commandByte;
        }
    }

    private void sendAudioToESP32(byte[] audioBuffer) {
        sendBytesToESP32(audioBuffer);
    }

    private void sendCommandToESP32(ESP32Command command) {
        byte[] commandArray = { COMMAND_DELIMITER[0], COMMAND_DELIMITER[1],
                COMMAND_DELIMITER[2], COMMAND_DELIMITER[3], COMMAND_DELIMITER[4], COMMAND_DELIMITER[5],
                COMMAND_DELIMITER[6], COMMAND_DELIMITER[7], command.getByte() };
        sendBytesToESP32(commandArray);
    }

    private void sendCommandToESP32(ESP32Command command, String paramsStr) {
        byte[] commandArray = { COMMAND_DELIMITER[0], COMMAND_DELIMITER[1],
                COMMAND_DELIMITER[2], COMMAND_DELIMITER[3], COMMAND_DELIMITER[4], COMMAND_DELIMITER[5],
                COMMAND_DELIMITER[6], COMMAND_DELIMITER[7], command.getByte() };
        byte[] combined = new byte[commandArray.length + paramsStr.length()];
        ByteBuffer buffer = ByteBuffer.wrap(combined);
        buffer.put(commandArray);
        buffer.put(paramsStr.getBytes(StandardCharsets.US_ASCII));
        combined = buffer.array();

        // Write it in a single call so the params are guaranteed (?) to be in receive buffer on mcu.
        // A little concerned there could be a bug here in rare chance that these bytes span receive
        // buffer size on mcu.
        // TODO implement a more robust way (in mcu code) of ensuring params are received by mcu
        sendBytesToESP32(combined);
    }

    private synchronized void sendBytesToESP32(byte[] newBytes) {
        try {
            // usbIoManager.writeAsync(newBytes);
            serialPort.write(newBytes, 200);
            debugLog("Wrote data: " + Arrays.toString(newBytes));
        } catch (Exception e) {
            e.printStackTrace();
            try {
                serialPort.close();
            } catch (IOException ex) {
                // Ignore.
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            findESP32Device(); // Attempt to reconnect after the brief pause above.
        }
    }

    private void handleESP32Data(byte[] data) {
        // debugLog("Got bytes from ESP32: " + Arrays.toString(data));
        try {
            String dataStr = new String(data, "UTF-8");
            if (dataStr.length() < 100 && dataStr.length() > 0)
                debugLog("Str data from ESP32: " + dataStr);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        // debugLog("Num bytes from ESP32: " + data.length);

        // Track consecutive silent bytes, so if we're scanning we can move to next after a while.
        if (mode == MODE_SCAN) {
            for (int i = 0; i < data.length; i++) {
                if (data[i] == -128) {
                    consecutiveSilenceBytes++;
                    // debugLog("consecutiveSilenceBytes: " + consecutiveSilenceBytes);
                    checkScanDueToSilence();
                } else {
                    consecutiveSilenceBytes = 0;
                }
            }
        }

        // If the prebuffer was already filled and sent to the audio track, we start
        // writing incoming data in realtime to keep the audio track prepped with audio.
        if (prebufferComplete) {
            audioTrack.write(data, 0, data.length);
        } else {
            for (int i = 0; i < data.length; i++) {
                // Prebuffer the incoming audio data so AudioTrack doesn't run out of audio to play
                // while we're waiting for more bytes.
                rxBytesPrebuffer[rxPrebufferIdx++] = data[i];
                if (rxPrebufferIdx == PRE_BUFFER_SIZE) {
                    prebufferComplete = true;
                    //debugLog("Rx prebuffer full, writing to audioTrack.");
                    if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.play();
                    }
                    audioTrack.write(rxBytesPrebuffer, 0, PRE_BUFFER_SIZE);
                    rxPrebufferIdx = 0;
                    break; // Might drop a few audio bytes from data[], should be very minimal
                }
            }
        }
    }
}