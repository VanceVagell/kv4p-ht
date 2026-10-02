# Manual BLE radio stress test

`BleRadioStressInstrumentedTest` is a manual hardware/instrumented test for BLE voice TX. It is
skipped by default, so it does not make normal CI require a kv4p radio.

Before running it, use the normal app to switch the nearby radio to **Analog** mode and configure
a legal simplex transmit frequency. Attach an appropriate antenna or dummy load, and leave
Bluetooth enabled. Close any other kv4p app session that is connected to the radio. The test
preserves that selected radio configuration; it does not choose a frequency itself.

From Android Studio, create an **Android Instrumented Tests** run configuration for
`BleRadioStressInstrumentedTest` on a connected physical phone. In **Instrumentation arguments**,
add `kv4pHardwareBleStress=true`.

From the command line, run this from `android-src/KV4PHT`:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vagell.kv4pht.radio.BleRadioStressInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.kv4pHardwareBleStress=true
```

The defaults are 100 cycles of 2 seconds transmitting and 2 seconds unkeyed. It emits iteration, TX timing, disconnect, reconnect,
HELLO, and BLE/GATT error details to Logcat under `BleRadioStressTest`. A disconnect that recovers
within 60 seconds is logged and the run continues; a failure to regain a validated HELLO fails the
test.

To set the two timings independently, use the positive millisecond instrumentation arguments
`kv4pHardwareBleStressTxDurationMs` and `kv4pHardwareBleStressPauseDurationMs`. For example,
setting both to `2000` gives a 2-second TX / 2-second pause run:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vagell.kv4pht.radio.BleRadioStressInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.kv4pHardwareBleStress=true \
  -Pandroid.testInstrumentationRunnerArguments.kv4pHardwareBleStressTxDurationMs=2000 \
  -Pandroid.testInstrumentationRunnerArguments.kv4pHardwareBleStressPauseDurationMs=2000
```

## Watching the run

Use a second terminal to stream only the relevant app and test events:

```sh
adb logcat -v threadtime \
  BleRadioStressTest:I RadioAudioService:I BleKissRadioTransport:W '*:S'
```

`BleRadioStressTest` prints the configured timings, each TX start/end, completed cycle count,
unexpected disconnects, reconnects, HELLOs, and final totals. `BleKissRadioTransport` warnings
show write-start retries; the test separately records transport errors and disconnects.

The test logs every completed cycle to Logcat. At the end of every run (including a failed run),
it emits a `BLE_STRESS_SUMMARY` line to Logcat and standard output and attaches the same text to
the final instrumentation result, so Gradle retains the final connection statistics without
requiring Logcat parsing.
