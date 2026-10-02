package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.MutableLiveData;
import com.vagell.kv4pht.data.ChannelMemory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RadioAudioServiceTest {
    @Rule public final InstantTaskExecutorRule liveDataRule = new InstantTaskExecutorRule();
    private RadioAudioService service;

    @Before public void setUp() {
        // Attach an Android context without starting onCreate's database/network workers.
        service = Robolectric.buildService(RadioAudioService.class).get();
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

    @Test public void productionAndSyntheticPttUseTheirExpectedAudioCapturePaths() throws Exception {
        Protocol.Sender sender = new Protocol.Sender(bytes -> { }, false);
        Field senderField = RadioAudioService.class.getDeclaredField("hostToEsp32");
        senderField.setAccessible(true);
        senderField.set(service, sender);
        service.getRadioModule().attachSender(sender);
        service.setMode(RadioMode.RX);
        service.tuneToFreq("146.5200");

        service.startPttForSyntheticAudio();
        assertEquals(RadioMode.TX, service.getMode());
        assertFalse(service.isVoiceCaptureActive());
        service.endPtt();

        service.startPtt();
        assertEquals(RadioMode.TX, service.getMode());
        service.endPtt();
    }

    @Test public void transportLifecycleNotifiesObservers() throws Exception {
        RadioAudioService.RadioAudioServiceCallbacks noOpCallbacks =
            new RadioAudioService.RadioAudioServiceCallbacks() {};
        noOpCallbacks.radioTransportConnected("test");
        noOpCallbacks.radioTransportDisconnected("test");
        noOpCallbacks.radioTransportError("test", "test");

        List<String> events = new ArrayList<>();
        service.setCallbacks(new RadioAudioService.RadioAudioServiceCallbacks() {
            @Override public void radioTransportConnected(String name) {
                events.add("connected:" + name);
            }

            @Override public void radioTransportDisconnected(String name) {
                events.add("disconnected:" + name);
            }

            @Override public void radioTransportError(String name, String detail) {
                events.add("error:" + name + ":" + detail);
            }
        });

        TestRadioTransport connectedTransport = new TestRadioTransport();
        setActiveTransport(connectedTransport);
        transportListener(connectedTransport).onReady();

        TestRadioTransport errorTransport = new TestRadioTransport();
        setActiveTransport(errorTransport);
        transportListener(errorTransport).onError(new IllegalStateException("test error"));

        TestRadioTransport disconnectedTransport = new TestRadioTransport();
        setActiveTransport(disconnectedTransport);
        transportListener(disconnectedTransport).onDisconnected();

        assertEquals(List.of("connected:test", "error:test:java.lang.IllegalStateException: test error",
            "disconnected:test"), events);
    }

    private void setActiveTransport(RadioTransport transport) throws Exception {
        Field transportField = RadioAudioService.class.getDeclaredField("activeTransport");
        transportField.setAccessible(true);
        transportField.set(service, transport);
    }

    private RadioTransport.Listener transportListener(RadioTransport transport) throws Exception {
        Method listenerFactory = RadioAudioService.class.getDeclaredMethod(
            "createTransportListener", RadioTransport.class);
        listenerFactory.setAccessible(true);
        return (RadioTransport.Listener) listenerFactory.invoke(service, transport);
    }

    private static final class TestRadioTransport implements RadioTransport {
        @Override public void start(Listener listener) {
            // The test invokes a listener produced by the service directly.
        }

        @Override public void close() {
            // This in-memory transport owns no resources.
        }

        @Override public boolean isReady() { return true; }

        @Override public void writeAsync(byte[] bytes) {
            // Protocol output is outside this lifecycle-callback test's scope.
        }

        @Override public boolean supportsFirmwareFlashing() { return false; }
        @Override public boolean prepareForFirmwareFlashing() { return false; }
        @Override public String getName() { return "test"; }
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
