package com.vagell.kv4pht.radio;

import android.Manifest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.app.Application;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Looper;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.MutableLiveData;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.javAX25.ax25.Packet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RadioAudioServiceTest {
    @Rule public final InstantTaskExecutorRule liveDataRule = new InstantTaskExecutorRule();
    private RadioAudioService service;

    @Before public void setUp() {
        // Attach an Android context without starting onCreate's database/network workers.
        service = Robolectric.buildService(RadioAudioService.class).get();
        Shadows.shadowOf((Application) service.getApplicationContext())
                .grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);
        selectBand(Protocol.RfModuleType.RF_SA818_VHF, 134f, 174f);
    }

    @Test public void bindingRestoresFrequencyAndSquelchAndReturnsService() {
        Intent intent = new Intent().putExtra("callsign", "VK3TEST")
            .putExtra("squelch", 4).putExtra("activeMemoryId", -1)
            .putExtra("activeFrequencyStr", "146.5200");
        RadioAudioService.RadioBinder binder = (RadioAudioService.RadioBinder) service.onBind(intent);
        assertSame(service, binder.getService());
        assertEquals("146.5200", service.getActiveFrequencyStr());
        assertEquals(4, service.getRadioModule().getDesiredSquelch());
    }

    @Test public void bindingWithoutExtrasPreservesExistingSettings() {
        service.getRadioModule().seedDesiredSquelch(3);
        assertSame(service, ((RadioAudioService.RadioBinder) service.onBind(new Intent())).getService());
        assertEquals(3, service.getRadioModule().getDesiredSquelch());
        assertEquals(RadioMode.STARTUP, service.getMode());
    }

    @Test public void frequencyInputNormalizesClampsAndFallsBackWithinSelectedBand() {
        assertEquals("146.7000", service.validateFrequency("1467"));
        assertEquals("134.0000", service.validateFrequency("100"));
        assertEquals("174.0000", service.validateFrequency("200"));
        assertEquals("144.0000", service.validateFrequency("invalid"));
        selectBand(Protocol.RfModuleType.RF_SA818_UHF, 400f, 480f);
        assertEquals("435.1250", service.validateFrequency("435125"));
        assertEquals("420.0000", service.validateFrequency("invalid"));
    }

    @Test public void frequencyFormattingRemainsProtocolCompatibleInCommaLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("146.5200", service.validateFrequency("146.52"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test public void startupRejectsTuningUntilInitializationFinishes() {
        service.tuneToFreq("146.5200");
        service.tuneToMemory(memory(1, "146.5200"));
        assertEquals("", service.getActiveFrequencyStr());
        assertEquals(0f, service.getRadioModule().getDesiredTxFrequency(), 0f);
    }

    @Test public void simplexTuningEnforcesTransmitBandEdgesIncludingBandwidth() {
        service.setMode(RadioMode.RX);
        service.tuneToFreq("146.5200");
        assertTrue(service.isTxAllowed());
        assertEquals(146.52f, service.getRadioModule().getDesiredTxFrequency(), 0.0001f);
        service.tuneToFreq("144.0000");
        assertFalse(service.isTxAllowed());
        service.tuneToFreq("148.0000");
        assertFalse(service.isTxAllowed());
        service.tuneToFreq("150.0000");
        assertFalse(service.isTxAllowed());
    }

    @Test public void repeaterOffsetUsesTransmitFrequencyForPermission() {
        service.setMode(RadioMode.RX);
        ChannelMemory memory = memory(1, "147.6000");
        memory.offset = ChannelMemory.OFFSET_UP;
        memory.offsetKhz = 600;
        service.tuneToMemory(memory);
        assertEquals(148.2f, service.getRadioModule().getDesiredTxFrequency(), 0.0001f);
        assertFalse(service.isTxAllowed());
        memory.offset = ChannelMemory.OFFSET_DOWN;
        service.tuneToMemory(memory);
        assertEquals(147f, service.getRadioModule().getDesiredTxFrequency(), 0.0001f);
        assertTrue(service.isTxAllowed());
    }

    @Test public void ax25OverrideUsesRequestedFrequencyWhenChannelTxIsDisallowed() throws Exception {
        service.setMode(RadioMode.RX);
        service.tuneToFreq("150.0000");
        assertFalse(service.isTxAllowed());

        List<byte[]> frames = new ArrayList<>();
        Field senderField = RadioAudioService.class.getDeclaredField("hostToEsp32");
        senderField.setAccessible(true);
        senderField.set(service, new Protocol.Sender(frames::add, false));
        Method send = RadioAudioService.class.getDeclaredMethod("txAX25PacketOnFrequency", Packet.class, float.class);
        send.setAccessible(true);
        Packet packet = new Packet(new byte[16]);

        assertTrue((boolean) send.invoke(service, packet, 146.520f));
        assertEquals(1, frames.size());
        assertFalse((boolean) send.invoke(service, packet, 150.000f));
        assertEquals(1, frames.size());
    }

    @Test public void aprsSubmissionUsesModeAndConnectionInsteadOfChannelTxFlag() throws Exception {
        Class<?> callbackType = Class.forName(RadioAudioService.class.getName() + "$ServiceAprsCallbacks");
        Constructor<?> constructor = callbackType.getDeclaredConstructor(RadioAudioService.class);
        constructor.setAccessible(true);
        Object callbacks = constructor.newInstance(service);
        Method canTransmit = callbackType.getDeclaredMethod("canTransmitAprs");
        canTransmit.setAccessible(true);
        assertFalse((boolean) canTransmit.invoke(callbacks));

        service.setMode(RadioMode.RX);
        service.tuneToFreq("150.0000");
        assertFalse(service.isTxAllowed());
        Field sender = RadioAudioService.class.getDeclaredField("hostToEsp32");
        sender.setAccessible(true);
        sender.set(service, new Protocol.Sender(frame -> { }, false));
        assertTrue((boolean) canTransmit.invoke(callbacks));
    }

    @Test public void manualBeaconChecksEffectiveTxFrequency() {
        service.setMode(RadioMode.RX);
        service.tuneToFreq("150.0000");
        assertFalse(service.canTransmitPositionBeacon());
        service.setAprsBeaconFrequency("146.5200");
        assertTrue(service.canTransmitPositionBeacon());
        service.setAprsBeaconFrequency("150.0000");
        assertFalse(service.canTransmitPositionBeacon());
        service.tuneToFreq("146.5200");
        service.setAprsBeaconFrequency("Current");
        assertTrue(service.canTransmitPositionBeacon());
    }

    @Test public void disconnectedPositionBeaconStopsBeforeLocationLookup() {
        service.sendPositionBeacon();
        assertFalse(service.isRadioConnected());
    }

    @Test public void connectedPositionBeaconContinuesToLocationCheck() throws Exception {
        service.setMode(RadioMode.RX);
        Field sender = RadioAudioService.class.getDeclaredField("hostToEsp32");
        sender.setAccessible(true);
        sender.set(service, new Protocol.Sender(frame -> { }, false));
        Field transport = RadioAudioService.class.getDeclaredField("activeTransport");
        transport.setAccessible(true);
        transport.set(service, new RadioTransport() {
            @Override public void start(Listener listener) { throw new UnsupportedOperationException(); }
            @Override public void close() { throw new UnsupportedOperationException(); }
            @Override public boolean isReady() { return true; }
            @Override public void writeAsync(byte[] bytes) { throw new UnsupportedOperationException(); }
            @Override public boolean supportsFirmwareFlashing() { return false; }
            @Override public boolean prepareForFirmwareFlashing() { return false; }
            @Override public String getName() { return "test"; }
        });
        boolean[] locationUnavailable = {false};
        service.setCallbacks(new RadioAudioService.RadioAudioServiceCallbacks() {
            @Override public void unknownLocation() { locationUnavailable[0] = true; }
        });

        assertTrue(service.isRadioConnected());
        service.sendPositionBeacon();
        assertTrue(locationUnavailable[0]);
    }

    @Test public void disallowedPositionBeaconStopsBeforeSubmission() throws Exception {
        service.setMode(RadioMode.RX);
        service.tuneToFreq("150.0000");
        Method perform = RadioAudioService.class.getDeclaredMethod("performPositionBeacon", double.class, double.class);
        perform.setAccessible(true);
        perform.invoke(service, -37.8d, 144.9d);
        assertFalse(service.canTransmitPositionBeacon());
    }

    @Test public void allowedOverrideBeaconContinuesToSubmission() throws Exception {
        service.setMode(RadioMode.RX);
        service.tuneToFreq("150.0000");
        service.setAprsBeaconFrequency("146.5200");
        List<String> started = new ArrayList<>();
        service.setCallbacks(new RadioAudioService.RadioAudioServiceCallbacks() {
            @Override public void startingAprsBeacon(String frequency) { started.add(frequency); }
        });
        Method perform = RadioAudioService.class.getDeclaredMethod("performPositionBeacon", double.class, double.class);
        perform.setAccessible(true);

        perform.invoke(service, -37.8d, 144.9d);

        assertFalse(service.isTxAllowed());
        assertEquals(List.of("146.5200"), started);
    }

    @Test public void scanningSkipsInvalidSkippedAndOtherBandMemoriesAndWraps() {
        ChannelMemory skipped = memory(2, "146.0000");
        skipped.skipDuringScan = true;
        service.setChannelMemories(new MutableLiveData<>(List.of(
            memory(1, "invalid"), skipped, memory(3, "435.0000"),
            memory(4, "146.5200"), memory(5, "145.5000"))));
        service.setMode(RadioMode.RX);
        service.setScanning(true);
        assertEquals("146.5200", service.getActiveFrequencyStr());
        assertEquals(7, service.getRadioModule().getDesiredSquelch());
        service.nextScan();
        assertEquals("145.5000", service.getActiveFrequencyStr());
        service.nextScan();
        assertEquals("146.5200", service.getActiveFrequencyStr());
        service.setScanning(false);
        assertEquals(RadioMode.RX, service.getMode());
        assertEquals(0, service.getRadioModule().getDesiredSquelch());
    }

    @Test public void scanningWithNoEligibleMemoriesLeavesFrequencyUnchanged() {
        service.setMode(RadioMode.RX);
        service.tuneToFreq("146.5200");
        service.setChannelMemories(new MutableLiveData<>(List.of(memory(1, "435.0000"))));
        service.setScanning(true);
        assertEquals("146.5200", service.getActiveFrequencyStr());
        service.setScanning(false);
    }

    @Test public void disconnectedServiceRejectsFlashingAndUnsupportedDigitalMode() {
        assertFalse(service.isRadioConnected());
        assertFalse(service.canFlashFirmware());
        service.setMode(RadioMode.FLASHING);
        assertEquals(RadioMode.STARTUP, service.getMode());
        service.setFreeDv2400bEnabled(true);
        assertFalse(service.isFreeDv2400bEnabled());
        assertFalse(service.isVoiceCaptureActive());
    }

    @Test @Config(sdk = 32) public void bleMicRouteDoesNotSwitchMediaAudioMode() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)));
        audio.setAvailableCommunicationDevices(List.of(
                audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
                audioDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));

        assertTrue(requestBluetoothCaptureRoute(audioManager));
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, audioManager.getCommunicationDevice().getType());
        assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());

        releaseBluetoothCaptureRoute();
        assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
    }

    @Test @Config(sdk = 32) public void bleMediaOutputCanUseBleMicrophone() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        audio.setAvailableCommunicationDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));

        assertTrue(requestBluetoothCaptureRoute(audioManager));
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, audioManager.getCommunicationDevice().getType());
        releaseBluetoothCaptureRoute();
    }

    @Test @Config(sdk = 32) public void scoCommunicationDeviceUsedWhenBleIsUnavailable() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)));
        audio.setAvailableCommunicationDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)));
        Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setProfileConnectionState(
                BluetoothProfile.HEADSET, BluetoothAdapter.STATE_CONNECTED);

        assertTrue(requestBluetoothCaptureRoute(audioManager));
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, audioManager.getCommunicationDevice().getType());
        assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());

        releaseBluetoothCaptureRoute();
    }

    @Test @Config(sdk = 32) public void scoRouteRequiresConnectedHandsFreeProfile() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)));
        audio.setAvailableCommunicationDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)));

        assertFalse(requestBluetoothCaptureRoute(audioManager));
        assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
    }

    @Test @Config(sdk = 32) public void bluetoothRoutingRequiresConnectPermissionForSco() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)));
        audio.setAvailableCommunicationDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)));
        Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setProfileConnectionState(
                BluetoothProfile.HEADSET, BluetoothAdapter.STATE_CONNECTED);
        Shadows.shadowOf((Application) service.getApplicationContext())
                .denyPermissions(Manifest.permission.BLUETOOTH_CONNECT);

        assertFalse(requestBluetoothCaptureRoute(audioManager));
        assertNull(audioManager.getCommunicationDevice());
    }

    @Test public void legacyScoNeedsHeadsetOutputEvenWhenProfileIsConnected() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)));
        Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setProfileConnectionState(
                BluetoothProfile.HEADSET, BluetoothAdapter.STATE_CONNECTED);

        assertFalse(requestBluetoothCaptureRoute(audioManager));
        assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
    }

    @Test @Config(sdk = 32) public void bluetoothRecorderNeedsAHeadsetInputAndMicPermission()
            throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        Shadows.shadowOf(audioManager).setInputDevices(List.of(audioInputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC)));
        assertNull(createBluetoothRecorder(audioManager));

        Shadows.shadowOf(audioManager).setInputDevices(List.of(audioInputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        Shadows.shadowOf((Application) service.getApplicationContext())
                .denyPermissions(Manifest.permission.RECORD_AUDIO);
        assertNull(createBluetoothRecorder(audioManager));
    }

    @Test public void phoneRecorderStillStartsIfBuiltInMicCannotBeEnumerated() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        Shadows.shadowOf(audioManager).setInputDevices(List.of());

        AudioRecord recorder = createPhoneRecorder(audioManager, true);
        assertNotNull(recorder);
        try {
            assertNull(recorder.getPreferredDevice());
        } finally {
            recorder.stop();
            recorder.release();
        }
    }

    @Test public void interruptedBluetoothWaitStopsPromptly() throws Exception {
        Method wait = RadioAudioService.class.getDeclaredMethod("waitForBluetoothRoute");
        wait.setAccessible(true);
        Thread.currentThread().interrupt();
        try {
            assertFalse((boolean) wait.invoke(service));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void revokedMicrophonePermissionPreventsPttCapture() throws Exception {
        Shadows.shadowOf((Application) service.getApplicationContext())
                .denyPermissions(Manifest.permission.RECORD_AUDIO);
        assertFalse(startVoiceCapture(false));
        assertNull(createPhoneRecorder(service.getSystemService(AudioManager.class), true));
        assertFalse(service.isVoiceCaptureActive());
    }

    @Test public void pttReleasesRadioWhenMicrophonePermissionIsRevoked() throws Exception {
        Field sender = RadioAudioService.class.getDeclaredField("hostToEsp32");
        sender.setAccessible(true);
        sender.set(service, new Protocol.Sender(frame -> { }, false));
        service.setMode(RadioMode.RX);
        service.tuneToFreq("146.5200");
        Shadows.shadowOf((Application) service.getApplicationContext())
                .denyPermissions(Manifest.permission.RECORD_AUDIO);

        service.startPtt();

        assertEquals(RadioMode.RX, service.getMode());
        assertFalse(service.isVoiceCaptureActive());
    }

    @Test @Config(sdk = 32) public void cancelledPttReleasesLateBluetoothRecorder() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        Shadows.shadowOf(audioManager).setInputDevices(List.of(
                audioInputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC),
                audioInputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        AudioRecord phoneRecorder = createPhoneRecorder(audioManager, true);
        AudioRecord bluetoothRecorder = createBluetoothRecorder(audioManager);
        assertNotNull(phoneRecorder);
        assertNotNull(bluetoothRecorder);
        try {
            Method handoff = RadioAudioService.class.getDeclaredMethod("handOffToBluetooth",
                    AudioRecord.class, AudioRecord.class, int.class, int.class);
            handoff.setAccessible(true);
            handoff.invoke(service, phoneRecorder, bluetoothRecorder, 0, RadioAudioService.AUDIO_SAMPLE_RATE);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(AudioRecord.STATE_UNINITIALIZED, bluetoothRecorder.getState());
            assertFalse(service.isVoiceCaptureActive());
        } finally {
            phoneRecorder.stop();
            phoneRecorder.release();
        }
    }

    @Test @Config(sdk = 32) public void activePttHandsOffToBluetoothRecorder() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        Shadows.shadowOf(audioManager).setInputDevices(List.of(
                audioInputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC),
                audioInputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        AudioRecord phoneRecorder = createPhoneRecorder(audioManager, true);
        AudioRecord bluetoothRecorder = createBluetoothRecorder(audioManager);
        assertNotNull(phoneRecorder);
        assertNotNull(bluetoothRecorder);
        Field active = RadioAudioService.class.getDeclaredField("voiceCaptureActive");
        active.setAccessible(true);
        active.set(service, true);
        Field current = RadioAudioService.class.getDeclaredField("audioRecord");
        current.setAccessible(true);
        @SuppressWarnings("unchecked") AtomicReference<AudioRecord> selected =
                (AtomicReference<AudioRecord>) current.get(service);
        selected.set(phoneRecorder);
        service.setMode(RadioMode.TX);
        try {
            invokeService("handOffToBluetooth",
                    new Class<?>[]{AudioRecord.class, AudioRecord.class, int.class, int.class},
                    phoneRecorder, bluetoothRecorder, captureSession(), RadioAudioService.AUDIO_SAMPLE_RATE);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertSame(bluetoothRecorder, currentRecorder());
            assertEquals(AudioRecord.STATE_UNINITIALIZED, phoneRecorder.getState());
            assertTrue(service.isVoiceCaptureActive());
        } finally {
            stopVoiceCapture();
        }
    }

    @Test public void legacyScoRouteRestoresPreviousAudioMode() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)));
        ShadowBluetoothAdapter bluetooth = Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter());
        bluetooth.setProfileConnectionState(BluetoothProfile.HEADSET, BluetoothAdapter.STATE_CONNECTED);

        assertTrue(requestBluetoothCaptureRoute(audioManager));
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.getMode());

        releaseBluetoothCaptureRoute();
        assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
    }

    @Test public void absentBluetoothPlaybackDoesNotTakeCommunicationRoute() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        Shadows.shadowOf(audioManager).setOutputDevices(List.of());

        assertFalse(requestBluetoothCaptureRoute(audioManager));
        assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
    }

    @Test public void pttStartsOnPhoneMicBeforeLegacyScoNegotiation() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setInputDevices(List.of(audioInputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC)));
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
                audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)));
        Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setProfileConnectionState(
                BluetoothProfile.HEADSET, BluetoothAdapter.STATE_CONNECTED);
        int[] started = {0};
        service.setCallbacks(new RadioAudioService.RadioAudioServiceCallbacks() {
            @Override public void txStarted() { started[0]++; }
        });
        service.setMode(RadioMode.TX);
        try {
            assertTrue(startVoiceCapture(false));
            assertEquals(1, started[0]);
            assertTrue(service.isVoiceCaptureActive());
            assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.getMode());
            Method unavailable = RadioAudioService.class.getDeclaredMethod("onBluetoothRouteUnavailable",
                    AudioRecord.class, int.class);
            unavailable.setAccessible(true);
            unavailable.invoke(service, currentRecorder(), captureSession());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
            assertTrue(service.isVoiceCaptureActive());
        } finally {
            stopVoiceCapture();
            assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
        }
    }

    @Test public void pttKeepsPhoneMicWhenBluetoothHandsFreeIsUnavailable() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setInputDevices(List.of(audioInputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC)));
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)));
        service.setMode(RadioMode.TX);
        try {
            assertTrue(startVoiceCapture(false));
            AudioRecord phoneRecorder = currentRecorder();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertSame(phoneRecorder, currentRecorder());
            assertTrue(service.isVoiceCaptureActive());
            assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
        } finally {
            stopVoiceCapture();
        }
    }

    @Test @Config(sdk = 32) public void bluetoothRecorderUsesHeadsetInput() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        Shadows.shadowOf(audioManager).setInputDevices(List.of(
                audioInputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC),
                audioInputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        AudioRecord recorder = createBluetoothRecorder(audioManager);
        assertNotNull(recorder);
        try {
            assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, recorder.getPreferredDevice().getType());
        } finally {
            recorder.stop();
            recorder.release();
        }
    }

    @Test @Config(sdk = 32) public void modernPttRequestsBluetoothWithoutChangingMediaMode() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        ShadowAudioManager audio = Shadows.shadowOf(audioManager);
        audio.setInputDevices(List.of(audioInputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC)));
        audio.setOutputDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)));
        audio.setAvailableCommunicationDevices(List.of(audioDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        service.setMode(RadioMode.TX);
        try {
            assertTrue(startVoiceCapture(false));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, audioManager.getCommunicationDevice().getType());
            assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
            Method lost = RadioAudioService.class.getDeclaredMethod("onBluetoothCaptureLost",
                    AudioRecord.class, int.class);
            lost.setAccessible(true);
            lost.invoke(service, currentRecorder(), captureSession());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(service.isVoiceCaptureActive());
            assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
        } finally {
            stopVoiceCapture();
        }
        assertEquals(AudioManager.MODE_NORMAL, audioManager.getMode());
    }

    @Test @Config(sdk = 32) public void scoSamplesFillRadioFrameWithoutLosingBoundarySample() throws Exception {
        AudioManager audioManager = service.getSystemService(AudioManager.class);
        Shadows.shadowOf(audioManager).setInputDevices(List.of(audioInputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        AudioRecord recorder = createBluetoothRecorder(audioManager);
        assertNotNull(recorder);
        try {
            List<byte[]> frames = new ArrayList<>();
            Field sender = RadioAudioService.class.getDeclaredField("hostToEsp32");
            sender.setAccessible(true);
            sender.set(service, new Protocol.Sender(frames::add, false));
            Field active = RadioAudioService.class.getDeclaredField("voiceCaptureActive");
            active.setAccessible(true);
            active.set(service, true);
            Field current = RadioAudioService.class.getDeclaredField("audioRecord");
            current.setAccessible(true);
            @SuppressWarnings("unchecked") AtomicReference<AudioRecord> selected =
                    (AtomicReference<AudioRecord>) current.get(service);
            selected.set(recorder);
            short[] input = new short[125];
            for (short i = 0; i < input.length; i++) input[i] = i;
            short[] frame = new short[RadioAudioService.AUDIO_FRAME_SAMPLES];
            Method send = RadioAudioService.class.getDeclaredMethod("sendCapturedAudio", AudioRecord.class,
                    int.class, int.class, short[].class, int.class, short[].class, int.class);
            send.setAccessible(true);

            int remaining = (int) send.invoke(service, recorder, 0, 8000, input, input.length, frame, 0);

            assertEquals(1, frames.size());
            assertEquals(1, remaining);
            assertEquals(input[124], frame[0]);
        } finally {
            recorder.stop();
            recorder.release();
        }
    }

    private static AudioDeviceInfo audioDevice(int type) {
        AudioDeviceInfo device = AudioDeviceInfoBuilder.newBuilder().setType(type).build();
        Object port = ReflectionHelpers.getField(device, "mPort");
        Object handle = ReflectionHelpers.getField(port, "mHandle");
        ReflectionHelpers.setField(handle, "mId", type + 1);
        return device;
    }

    private static AudioDeviceInfo audioInputDevice(int type) {
        AudioDeviceInfo device = audioDevice(type);
        Object port = ReflectionHelpers.getField(device, "mPort");
        ReflectionHelpers.setField(port, "mRole", 1);
        return device;
    }

    private AudioRecord currentRecorder() throws Exception {
        Field current = RadioAudioService.class.getDeclaredField("audioRecord");
        current.setAccessible(true);
        @SuppressWarnings("unchecked") AtomicReference<AudioRecord> selected =
                (AtomicReference<AudioRecord>) current.get(service);
        return selected.get();
    }

    private int captureSession() throws Exception {
        Field field = RadioAudioService.class.getDeclaredField("voiceCaptureSession");
        field.setAccessible(true);
        return ((AtomicInteger) field.get(service)).get();
    }

    private boolean startVoiceCapture(boolean forceInternalMic) throws Exception {
        return (boolean) invokeService("startVoiceCapture", new Class<?>[]{boolean.class}, forceInternalMic);
    }

    private void stopVoiceCapture() throws Exception {
        invokeService("stopVoiceCapture", new Class<?>[0]);
    }

    private AudioRecord createPhoneRecorder(AudioManager audioManager, boolean preferInternalMic)
            throws Exception {
        return (AudioRecord) invokeService("createPhoneRecorder",
                new Class<?>[]{AudioManager.class, boolean.class}, audioManager, preferInternalMic);
    }

    private AudioRecord createBluetoothRecorder(AudioManager audioManager) throws Exception {
        return (AudioRecord) invokeService("createBluetoothRecorder",
                new Class<?>[]{AudioManager.class, int.class}, audioManager, RadioAudioService.AUDIO_SAMPLE_RATE);
    }

    private Object invokeService(String methodName, Class<?>[] parameterTypes, Object... arguments)
            throws Exception {
        Method method = RadioAudioService.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(service, arguments);
    }

    private boolean requestBluetoothCaptureRoute(AudioManager audioManager) throws Exception {
        Method request = RadioAudioService.class.getDeclaredMethod("requestBluetoothCaptureRoute", AudioManager.class);
        request.setAccessible(true);
        return (boolean) request.invoke(service, audioManager);
    }

    private void releaseBluetoothCaptureRoute() throws Exception {
        Method release = RadioAudioService.class.getDeclaredMethod("releaseBluetoothCaptureRoute");
        release.setAccessible(true);
        release.invoke(service);
    }

    private void selectBand(Protocol.RfModuleType band, float min, float max) {
        service.handleHello(Protocol.Hello.builder().version(Protocol.FirmwareVersion.builder()
            .moduleType(band).minRadioFreq(min).maxRadioFreq(max).build()).build());
    }

    private static ChannelMemory memory(int id, String frequency) {
        ChannelMemory memory = new ChannelMemory();
        memory.memoryId = id;
        memory.name = "Test " + id;
        memory.frequency = frequency;
        memory.txTone = "None";
        memory.rxTone = "None";
        return memory;
    }
}
