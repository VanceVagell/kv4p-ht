package com.vagell.kv4pht.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.room.Room;

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
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.data.AppDatabase;
import com.vagell.kv4pht.data.ChannelMemory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
    // private TextView debugText;
    private SerialInputOutputManager usbIoManager;
    private Queue<byte[]> serialOutBufferQueue;

    // For receiving audio from ESP32 / radio
    private AudioTrack audioTrack;
    private static final int PRE_BUFFER_SIZE = 2000;
    private byte[] rxBytesPrebuffer = new byte[PRE_BUFFER_SIZE];
    private int rxPrebufferIdx = 0;
    private boolean prebufferComplete = false;

    // Delimiter must match ESP32 code
    private static final byte[] COMMAND_DELIMITER = new byte[] {(byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00};

    private static final int REQUEST_AUDIO_PERMISSION_CODE = 1;

    private static final String ACTION_USB_PERMISSION = "com.vagell.kv4pht.USB_PERMISSION";

    private static final int MODE_RX = 0;
    private static final int MODE_TX = 1;
    private int mode = MODE_RX;

    // Radio params
    private String activeFrequencyStr = "144.0000";

    // Various user-defined app parameters
    public static AppDatabase appDb = null;

    // Activity callback values
    public static final int REQUEST_ADD_MEMORY = 0;
    public static final int REQUEST_EDIT_MEMORY = 1;

    private static final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(2,
            2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

    private Map<String, Integer> mTones = new HashMap<>();

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // debugText = findViewById(R.id.debugText);
        // debugText.setMovementMethod(new ScrollingMovementMethod());

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        serialOutBufferQueue = new LinkedList<>();

        requestAudioPermissions();
        findESP32Device();

        attachListeners();
        initAudioTrack();

        setupTones();

        appDb = Room.databaseBuilder(getApplicationContext(),
                AppDatabase.class, "kv4pht-db").build();
        loadSettings();
        loadChannelMemories();
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

    private void loadSettings() {
        // TODO use appDb
    }

    private void loadChannelMemories() {
        final Activity activity = this;
        threadPoolExecutor.execute(
            new Runnable() {
               @Override
               public void run() {
                   final List<ChannelMemory> channelMemories = appDb.channelMemoryDao().getAll();

                   activity.runOnUiThread(new Runnable() {
                       @Override
                       public void run() {
                           LayoutInflater inflater = (LayoutInflater)getApplicationContext().getSystemService
                                   (Context.LAYOUT_INFLATER_SERVICE);

                           final LinearLayoutCompat memoriesList = findViewById(R.id.memoriesList);
                           memoriesList.removeAllViews();

                           for (int i = 0; i < channelMemories.size(); i++) {
                               View memoryRow = inflater.inflate(R.layout.memory_row, null);
                               final ChannelMemory memory = channelMemories.get(i);
                               ((TextView) memoryRow.findViewById(R.id.memoryName)).setText(memory.name);
                               ((TextView) memoryRow.findViewById(R.id.memoryFrequency)).setText(memory.frequency);

                               // Make tapping this row tune to the memory.
                               memoryRow.setOnClickListener(new View.OnClickListener() {
                                   @Override
                                   public void onClick(View v) {
                                       // Actually tune to it.
                                       tuneToMemory(memory);

                                       // Unhighlight all rows.
                                       int memoryRows = memoriesList.getChildCount();
                                       for (int i = 0; i < memoryRows; i++) {
                                           View tempMemoryRow = memoriesList.getChildAt(i);
                                           setMemoryRowHighlighted(activity, tempMemoryRow, false);
                                       }

                                       // Highlight the tapped row.
                                       setMemoryRowHighlighted(activity, memoryRow, true);
                                   }
                               });

                               // Make tapping this row's menu bring up a couple options.
                               final View memoryMenuButton = memoryRow.findViewById(R.id.memoryMenu);
                               memoryMenuButton.setOnClickListener(new View.OnClickListener() {
                                   @Override
                                   public void onClick(View v) {
                                       PopupMenu memoryMenu = new PopupMenu(activity, memoryMenuButton);
                                       memoryMenu.inflate(R.menu.memory_row_menu);
                                       memoryMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                                           @Override
                                           public boolean onMenuItemClick(MenuItem item) {
                                               if (item.getTitle().equals("Delete")) {
                                                   threadPoolExecutor.execute(
                                                       new Runnable() {
                                                           @Override
                                                           public void run() {
                                                               appDb.channelMemoryDao().delete(memory);
                                                               loadChannelMemories();
                                                           }
                                                       });
                                               } else if (item.getTitle().equals("Edit")) {
                                                   Intent intent = new Intent("com.vagell.kv4pht.EDIT_MEMORY_ACTION");
                                                   intent.putExtra("requestCode", REQUEST_EDIT_MEMORY);
                                                   intent.putExtra("memoryId", memory.memoryId);
                                                   startActivityForResult(intent, REQUEST_EDIT_MEMORY);
                                               }
                                               return true;
                                           }
                                       });
                                       memoryMenu.show();
                                   }
                               });

                               memoriesList.addView(memoryRow);
                           }
                       }
                   });
               }
           });
    }

    private static void setMemoryRowHighlighted(Context context, View memoryRow, boolean highlighted) {
        if (highlighted) {
            memoryRow.findViewById(R.id.memoryContainer)
                    .setBackgroundColor(context.getResources().getColor(R.color.primary_veryfaint));
            memoryRow.findViewById(R.id.memoryMenu).setVisibility(View.VISIBLE);
            ((TextView) memoryRow.findViewById(R.id.memoryName)).setTextColor(context.getResources().getColor(R.color.primary));
            ((TextView) memoryRow.findViewById(R.id.memoryFrequency)).setTextColor(context.getResources().getColor(R.color.primary));
        } else {
            memoryRow.setBackgroundColor(context.getResources().getColor(R.color.clear));
            memoryRow.findViewById(R.id.memoryMenu).setVisibility(View.GONE);
            ((TextView) memoryRow.findViewById(R.id.memoryName)).setTextColor(context.getResources().getColor(R.color.primary_deselected));
            ((TextView) memoryRow.findViewById(R.id.memoryFrequency)).setTextColor(context.getResources().getColor(R.color.primary_deselected));
        }
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
        activeFrequencyStr = validateFrequency(frequencyStr);

        sendCommandToESP32(ESP32Command.TUNE_TO, makeSafe2MFreq(activeFrequencyStr) + makeSafe2MFreq(activeFrequencyStr) + "00"); // tx, rx, tone

        showMemoryName("Simplex");
        showFrequency(activeFrequencyStr);

        // Unhighlight all memory rows, since this is a simplex frequency.
        LinearLayoutCompat memoriesList = findViewById(R.id.memoriesList);
        int memoryRows = memoriesList.getChildCount();
        for (int i = 0; i < memoryRows; i++) {
            View tempMemoryRow = memoriesList.getChildAt(i);
            setMemoryRowHighlighted(this, tempMemoryRow, false);
        }

        // Reset audio prebuffer
        prebufferComplete = false;
        rxPrebufferIdx = 0;
    }

    private void tuneToMemory(ChannelMemory memory) {
        activeFrequencyStr = validateFrequency(memory.frequency);

        sendCommandToESP32(ESP32Command.TUNE_TO,
                getTxFreq(memory.frequency, memory.offset) + makeSafe2MFreq(memory.frequency) + getToneIdxStr(memory.tone));

        showMemoryName(memory.name);
        showFrequency(activeFrequencyStr);

        // Reset audio prebuffer
        prebufferComplete = false;
        rxPrebufferIdx = 0;
    }

    private void showMemoryName(String name) {
        TextView activeFrequencyField = findViewById(R.id.activeMemoryName);
        activeFrequencyField.setText(name);
    }

    private void showFrequency(String frequency) {
        EditText activeFrequencyField = findViewById(R.id.activeFrequency);
        activeFrequencyField.setText(frequency);
        activeFrequencyStr = frequency;
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
        /* runOnUiThread(new Runnable() {
            @Override
            public void run() {
                debugText.append("\n" + text);
                debugText.post(new Runnable() {
                    @Override
                    public void run() {
                        final int scrollAmount = debugText.getLayout().getLineTop(debugText.getLineCount()) - debugText.getHeight();
                        if (scrollAmount > 0)
                            debugText.scrollTo(0, scrollAmount);
                        else
                            debugText.scrollTo(0, 0);
                    }
                });
            }
        }); */
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
            requestUSBPermission();
        }
    }

    private void showUSBRetrySnackbar() {
        CharSequence snackbarMsg = "Radio not found, is it plugged in to USB?";
        CharSequence buttonLabel = "RETRY";
        Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
                .setAction(buttonLabel, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        findESP32Device();
                    }
                }).setBackgroundTint(Color.RED).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE).show();
    }

    private boolean isESP32Device(UsbDevice device) {
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

    private void requestUSBPermission() {
        if (usbManager.hasPermission(esp32Device)) {
            synchronized (this) {
                setupSerialConnection();
                return;
            }
        }

        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
        usbManager.requestPermission(esp32Device, permissionIntent);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    setupSerialConnection();
                }
            }
        }
    };

    private void setupSerialConnection() {
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
            serialPort.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
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
        usbIoManager.setReadTimeout(0); // In ms; if 0/infinite, writes may block until a read happens
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
        // TODO
    }

    public void importMemoriesClicked(View view) {
        // TODO let user select CSV file from Repeater Book to import
    }

    public void addMemoryClicked(View view) {
        Intent intent = new Intent("com.vagell.kv4pht.ADD_MEMORY_ACTION");
        intent.putExtra("requestCode", REQUEST_ADD_MEMORY);
        startActivityForResult(intent, REQUEST_ADD_MEMORY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ADD_MEMORY:
            case REQUEST_EDIT_MEMORY:
                if (resultCode == Activity.RESULT_OK) {
                    loadChannelMemories();
                }
                break;
            default:
                debugLog("Warning: Returned to MainActivity from unexpected request code: " + requestCode);
        }
    }

    // TODO editMemoryClicked
    // intent.putExtra("memoryId", memory.getId()\);

    public void settingsClicked(View view) {
        // TODO
    }

    private enum ESP32Command {
        PTT_DOWN((byte) 1),
        PTT_UP((byte) 2),
        TUNE_TO((byte) 3); // paramsStr length of 8 (xxx.xxxx frequency)

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
        }
    }

    private void handleESP32Data(byte[] data) {
        // debugLog("Got bytes from ESP32: " + Arrays.toString(data));
        // debugLog("Str data from ESP32: " + new String(data, "UTF-8"));
        // debugLog("Num bytes from ESP32: " + data.length);

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