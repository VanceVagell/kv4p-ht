package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.core.content.ContextCompat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Manual hardware test for issue #472. It is skipped unless invoked with
 * {@code -e kv4pHardwareBleStress true}; it must never be a normal CI requirement.
 *
 * <p>The test binds the real {@link RadioAudioService}, lets its connection controller discover
 * the radio through {@link BleKissRadioTransport}, and drives PTT plus
 * {@link RadioAudioService#sendAudioToESP32(short[], boolean)}. It therefore uses the production
 * IMA ADPCM encoder, KISS/vendor framing, BLE write queue, flow control, and radio state path.
 * It deliberately does not use {@code BluetoothGatt} or {@code AudioRecord} itself.</p>
 */
@RunWith(AndroidJUnit4.class)
public class BleRadioStressInstrumentedTest {
    private static final String TAG = "BleRadioStressTest";
    private static final String ENABLE_ARGUMENT = "kv4pHardwareBleStress";
    private static final String TX_DURATION_ARGUMENT = "kv4pHardwareBleStressTxDurationMs";
    private static final String PAUSE_DURATION_ARGUMENT = "kv4pHardwareBleStressPauseDurationMs";

    // Fast hardware-stress defaults. Override either duration through instrumentation arguments.
    private static final int ITERATION_COUNT = 100;
    private static final long DEFAULT_TX_DURATION_MS = 2_000L;
    private static final long DEFAULT_PAUSE_DURATION_MS = 2_000L;
    private static final long INITIAL_READY_TIMEOUT_MS = 60_000L;
    private static final long RECOVERY_READY_TIMEOUT_MS = 60_000L;
    private static final long FRAME_PERIOD_NS =
        RadioAudioService.AUDIO_FRAME_SAMPLES * 1_000_000_000L / RadioAudioService.AUDIO_SAMPLE_RATE;
    private static final long MAX_TEST_DURATION_MS =
        INITIAL_READY_TIMEOUT_MS + ITERATION_COUNT * (8_000L + 24_000L
            + RECOVERY_READY_TIMEOUT_MS) + 60_000L;

    private final CountDownLatch serviceBound = new CountDownLatch(1);
    private final AtomicInteger helloCount = new AtomicInteger();
    private final AtomicInteger disconnectCount = new AtomicInteger();
    private final AtomicInteger reconnectCount = new AtomicInteger();
    private final AtomicInteger transportErrorCount = new AtomicInteger();
    private final AtomicBoolean hadHello = new AtomicBoolean();
    private final AtomicBoolean disconnectedSinceHello = new AtomicBoolean();

    private Context context;
    private Intent serviceIntent;
    private RadioAudioService service;
    private boolean bound;
    private long txDurationMs;
    private long pauseDurationMs;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RadioAudioService.RadioBinder) binder).getService();
            serviceBound.countDown();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            log("Android service process disconnected");
        }
    };

    @Before public void setUp() throws Exception {
        Assume.assumeTrue("Manual BLE hardware test; pass -e " + ENABLE_ARGUMENT + " true",
            "true".equals(InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT)));
        txDurationMs = readDurationMs(TX_DURATION_ARGUMENT, DEFAULT_TX_DURATION_MS);
        pauseDurationMs = readDurationMs(PAUSE_DURATION_ARGUMENT, DEFAULT_PAUSE_DURATION_MS);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        adoptRequiredPermissions();
        assertBluetoothEnabled();

        serviceIntent = new Intent(context, RadioAudioService.class)
            .putExtra("callsign", "BLESTRESS")
            .putExtra("activeMemoryId", -1);
        // Match MainActivity's production lifecycle: Android grants the foreground-service
        // process priority before it is bound and its connection controller begins scanning.
        ContextCompat.startForegroundService(context, serviceIntent);
        bound = context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
        assertTrue("Could not bind RadioAudioService", bound);
        assertTrue("Timed out binding RadioAudioService", serviceBound.await(10, TimeUnit.SECONDS));
        assertNotNull(service);
        service.setCallbacks(new StressCallbacks());
        service.start();
        log("configured txDurationMs=" + txDurationMs + " pauseDurationMs=" + pauseDurationMs);
    }

    @After public void tearDown() {
        if (service != null) {
            service.endPtt();
        }
        if (bound) {
            context.unbindService(connection);
        }
        if (context != null && serviceIntent != null) {
            context.stopService(serviceIntent);
        }
        InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    @Test(timeout = MAX_TEST_DURATION_MS)
    public void repeatedlyTransmitsSyntheticAudioOverProductionBlePath() throws Exception {
        int completedCycles = 0;
        try {
            waitForReady(INITIAL_READY_TIMEOUT_MS, "initial HELLO");
            assertTrue("Radio configuration must allow TX; select a legal simplex channel before running",
                service.isTxAllowed());
            log("initial HELLO complete mode=" + service.getMode() + " txAllowed=" + service.isTxAllowed());

            int nextSample = 0;
            for (int iteration = 1; iteration <= ITERATION_COUNT; iteration++) {
                waitForReady(RECOVERY_READY_TIMEOUT_MS, "before iteration " + iteration);
                assertEquals("Radio must be in RX before TX cycle " + iteration,
                    RadioMode.RX, service.getMode());
                assertTrue("TX became disallowed before iteration " + iteration, service.isTxAllowed());

                long txStart = SystemClock.elapsedRealtime();
                log("iteration=" + iteration + " TX start=" + txStart
                    + " hello=" + helloCount.get() + " disconnects=" + disconnectCount.get());
                service.startPttForSyntheticAudio();
                assertEquals("PTT did not enter TX on iteration " + iteration, RadioMode.TX, service.getMode());
                nextSample = sendPacedSyntheticFrames(iteration, nextSample);
                service.endPtt();
                long txEnd = SystemClock.elapsedRealtime();
                log("iteration=" + iteration + " TX end=" + txEnd + " durationMs=" + (txEnd - txStart));

                waitForReady(RECOVERY_READY_TIMEOUT_MS, "after iteration " + iteration);
                assertEquals("Radio did not return to RX after iteration " + iteration,
                    RadioMode.RX, service.getMode());
                completedCycles++;
                log("iteration=" + iteration + " complete; completed=" + completedCycles
                    + " disconnects=" + disconnectCount.get() + " reconnects=" + reconnectCount.get()
                    + " hellos=" + helloCount.get() + " transportErrors=" + transportErrorCount.get());
                sleepInterruptibly(pauseDurationMs);
            }
            assertEquals("A recoverable reconnect may be recorded but every configured cycle must finish",
                ITERATION_COUNT, completedCycles);
        } finally {
            reportFinalSummary(completedCycles);
        }
    }

    private int sendPacedSyntheticFrames(int iteration, int sampleNumber) throws Exception {
        final long finishAtNs = SystemClock.elapsedRealtimeNanos() + txDurationMs * 1_000_000L;
        long nextFrameAtNs = SystemClock.elapsedRealtimeNanos();
        short[] frame = new short[RadioAudioService.AUDIO_FRAME_SAMPLES];
        while (SystemClock.elapsedRealtimeNanos() < finishAtNs) {
            if (!service.isRadioConnected() || service.getMode() != RadioMode.TX) {
                log("iteration=" + iteration + " transport lost while TX; stopping frame feed");
                break;
            }
            for (int index = 0; index < frame.length; index++, sampleNumber++) {
                // Deterministic, low-level 440 Hz PCM; normal production frame size and sample rate.
                frame[index] = (short) (2_000 * Math.sin(2.0 * Math.PI * 440.0
                    * sampleNumber / RadioAudioService.AUDIO_SAMPLE_RATE));
            }
            service.sendAudioToESP32(frame, false);
            nextFrameAtNs += FRAME_PERIOD_NS;
            sleepUntil(nextFrameAtNs);
        }
        return sampleNumber;
    }

    private void waitForReady(long timeoutMs, String reason) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (service.isRadioConnected() && service.getMode() == RadioMode.RX) {
                return;
            }
            sleepInterruptibly(100L);
        }
        throw new AssertionError("Radio did not become BLE-ready with a validated HELLO within "
            + timeoutMs + "ms (" + reason + "); mode=" + service.getMode()
            + ", hello=" + helloCount.get() + ", disconnects=" + disconnectCount.get()
            + ", errors=" + transportErrorCount.get());
    }

    private void assertBluetoothEnabled() {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        assertNotNull("Bluetooth is unavailable on this device", adapter);
        assertTrue("Enable Bluetooth before running this hardware test", adapter.isEnabled());
    }

    private long readDurationMs(String argumentName, long defaultValue) {
        String rawValue = InstrumentationRegistry.getArguments().getString(argumentName);
        if (rawValue == null) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(rawValue);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // The assertion below provides a useful runner-visible configuration error.
        }
        throw new AssertionError(argumentName + " must be a positive millisecond duration; was " + rawValue);
    }

    private void adoptRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS);
        } else {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO);
        }
    }

    private void sleepUntil(long targetNs) throws InterruptedException {
        while (true) {
            long remainingNs = targetNs - SystemClock.elapsedRealtimeNanos();
            if (remainingNs <= 0) {
                return;
            }
            LockSupport.parkNanos(remainingNs);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    private void sleepInterruptibly(long durationMs) throws InterruptedException {
        sleepUntil(SystemClock.elapsedRealtimeNanos() + TimeUnit.MILLISECONDS.toNanos(durationMs));
    }

    private void log(String message) {
        Log.i(TAG, message);
    }

    private void reportFinalSummary(int completedCycles) {
        String summary = "BLE_STRESS_SUMMARY completed=" + completedCycles + "/" + ITERATION_COUNT
            + " txDurationMs=" + txDurationMs + " pauseDurationMs=" + pauseDurationMs
            + " failures=" + connectionFailureCount() + " disconnects=" + disconnectCount.get()
            + " reconnects=" + reconnectCount.get() + " hellos=" + helloCount.get()
            + " transportErrors=" + transportErrorCount.get();
        log(summary);
        System.out.println(summary);
        Bundle results = new Bundle();
        results.putString("kv4pBleStressSummary", summary);
        // AndroidJUnitRunner merges these into its final instrumentation result, so Gradle can
        // retain the summary without sending an intermediate test-completion status.
        InstrumentationRegistry.getInstrumentation().addResults(results);
    }

    private int connectionFailureCount() {
        return disconnectCount.get() + transportErrorCount.get();
    }

    private final class StressCallbacks implements RadioAudioService.RadioAudioServiceCallbacks {
        @Override public void radioTransportConnected(String transportName) {
            int reconnects = hadHello.get() ? reconnectCount.incrementAndGet() : reconnectCount.get();
            log("BLE transport connected transport=" + transportName + " reconnects=" + reconnects);
        }

        @Override public void radioTransportDisconnected(String transportName) {
            if (!hadHello.get()) {
                // BleKissRadioTransport uses this callback when a discovery scan window expires.
                // Before the first HELLO there has been no radio connection to lose.
                log("BLE discovery scan expired while waiting for radio transport=" + transportName);
                return;
            }
            disconnectedSinceHello.set(true);
            log("unexpected BLE disconnect transport=" + transportName + " count="
                + disconnectCount.incrementAndGet() + "; possible radio reboot, awaiting reconnect");
        }

        @Override public void radioTransportError(String transportName, String detail) {
            disconnectedSinceHello.set(true);
            log("unexpected BLE/GATT transport error transport=" + transportName + " detail=" + detail
                + " count=" + transportErrorCount.incrementAndGet()
                + "; possible transport loss, awaiting reconnect");
        }

        @Override public void radioConnected() {
            int hellos = helloCount.incrementAndGet();
            boolean hadPreviousHello = hadHello.getAndSet(true);
            if (hadPreviousHello && disconnectedSinceHello.getAndSet(false)) {
                log("HELLO received after reconnect; possible radio reboot hellos=" + hellos);
            } else if (!hadPreviousHello) {
                log("initial HELLO received hellos=" + hellos);
            } else {
                log("additional HELLO received without a transport disconnect hellos=" + hellos);
            }
        }
    }
}
