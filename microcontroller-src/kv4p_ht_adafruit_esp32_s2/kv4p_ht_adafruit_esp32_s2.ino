#include <DRA818.h>
#include <driver/adc.h>
#include <algorithm>
#include <Adafruit_NeoPixel.h>
#include <esp_task_wdt.h>

// Commands defined here must match the Android app
const uint8_t COMMAND_PTT_DOWN = 1;
const uint8_t COMMAND_PTT_UP = 2;
const uint8_t COMMAND_TUNE_TO = 3;

// Delimeter must also match Android app
#define DELIMITER_LENGTH 8
const uint8_t delimiter[DELIMITER_LENGTH] = {0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00};
int matchedDelimiterTokens = 0;

// Mode of the app, which is essentially a state machine
#define MODE_TX 0
#define MODE_RX 1
int mode = MODE_RX;

// Audio sampling rate, must match what Android app expects (and sends).
#define AUDIO_SAMPLE_RATE 8000

// Buffer for sample audio bytes from the radio module
#define RX_AUDIO_BUFFER_SIZE 64 // very low buffer size because Adafruit QT Py ESP32-S2 has very small USB buffers
uint8_t rxAudioBuffer[RX_AUDIO_BUFFER_SIZE];
int rxAudioBufferIdx = 0;
uint8_t rxAudioBufferCopy[RX_AUDIO_BUFFER_SIZE];

// Buffer for outgoing audio bytes to send to radio module
#define TX_AUDIO_BUFFER_SIZE 1000 // doesn't need to be small like RX_AUDIO_BUFFER_SIZE because this holds data we already got off of USB serial
uint8_t txAudioBuffer1[TX_AUDIO_BUFFER_SIZE]; // Processed tx audio bytes that will be sent to radio module
uint8_t txAudioBuffer2[TX_AUDIO_BUFFER_SIZE]; // 2nd tx audio buffer so we can alternate to avoid any gaps due to processing
bool usingTxBuffer1 = true;
int txAudioBufferIdx1 = 0;
int txAudioBufferLen1 = 0;
int txAudioBufferIdx2 = 0;
int txAudioBufferLen2 = 0;

// The number of bytes from the end of any buffer when rx or tx audio is processed (to avoid overrun during async processing)
#define BYTES_TO_TRIGGER_FROM_BUFFER_END 20

// Connections to radio module
#define RXD2_PIN 16
#define TXD2_PIN 5
#define DAC_PIN 17
#define ADC_PIN 9 // If this is changed, you may need to manually edit adc1_config_channel_atten() below too.
#define PTT_PIN 18
#define PD_PIN 8
#define SQ_PIN 35

// Built in neopixel LED
#define NEOPIXEL_PIN 39
Adafruit_NeoPixel neopixel(1, NEOPIXEL_PIN, NEO_GRB + NEO_KHZ800);

// Object used for radio module serial comms
DRA818* dra = new DRA818(&Serial1, DRA818_VHF);

void setup() {
  // Communication with Android via USB cable
  Serial.setRxBufferSize(TX_AUDIO_BUFFER_SIZE);
  // Serial.setTxTimeoutMs(50); // FYI keep an eye on this: https://github.com/espressif/arduino-esp32/issues/7779#issuecomment-1969652597
  Serial.begin(921600);
  // Serial.setTxBufferSize(1024); Not supported by ESP32-S2 :(

  // Configure watch dog timer (WDT), which will reset the system if it gets stuck somehow.
  esp_task_wdt_init(10, true); // Reboot if locked up for a bit
  esp_task_wdt_add(NULL); // Add the current task to WDT watch

  // Neopixel LED (for rx/tx light)
  neopixel.begin();
  neopixel.show();

  // Set up radio module defaults
  pinMode(PD_PIN, OUTPUT);
  digitalWrite(PD_PIN, HIGH); // Power on
  pinMode(SQ_PIN, INPUT);
  pinMode(PTT_PIN, OUTPUT);
  digitalWrite(PTT_PIN, HIGH); // Rx
  pinMode(DAC_PIN, OUTPUT);

  // Communication with DRA818V radio module via GPIO pins
  Serial1.begin(9600, SERIAL_8N1, RXD2_PIN, TXD2_PIN);

  int result = -1;
  while (result != 1) {
    result = dra->handshake(); // Wait for module to start up
  }
  // Serial.println("handshake: " + String(result));
  tuneTo(146.520, 146.520, 0);
  result = dra->volume(8);
  // Serial.println("volume: " + String(result));
  result = dra->filters(false, false, false);
  // Serial.println("filters: " + String(result));

  // Configure the ADC resolution to 8 bits
  analogReadResolution(8);
  analogWriteResolution(8);

  // Turn off attenuation of the incoming audio from the radio module
  adc1_config_channel_atten(ADC1_CHANNEL_8, ADC_ATTEN_DB_0);

  // Start in RX mode
  setMode(MODE_RX);

  // Start async rx/tx timer
  setupTimer();
}

// Transmit audio is very sensitive to the microcontroller not being very precise with
// when it updates the DAC, so we regulate it based on how much time actually passed.
#define ADC_DAC_CALL_MICROSECONDS 41 // 41 Found empirically by determining a value that sounded natural.
#define PROCESSOR_MHZ 240 // TODO: Can we use ESP.getCpuFreqMHz() ?
unsigned long targetTxBufferEndMicros = micros();
unsigned long startedTxMicros = micros();

unsigned long ulmap(unsigned long x, unsigned long in_min, unsigned long in_max, unsigned long out_min, unsigned long out_max) {
  return (x - in_min) * (out_max - out_min) / (in_max - in_min) + out_min;
}

void IRAM_ATTR readWriteAnalog() {
  try {
    // This needs to do as LITTLE processing as possible, or we won't capture
    // enough audio for it to play back properly.
    if (mode) { // Since MODE_RX is 1, using this hack to avoid a few cycles.
      if (rxAudioBufferIdx < RX_AUDIO_BUFFER_SIZE) { 
        // TODO rewrite this to use the same interpolating approach as tx below. the difference is that this needs to ensure that
        // any skipped indices are filled with the current value (so can't just jump to interpolated index but count up to it).
        rxAudioBuffer[rxAudioBufferIdx++] = (uint8_t) analogRead(ADC_PIN);
      }
    } else { // MODE_TX
      unsigned long now = micros();
      if (now < targetTxBufferEndMicros) {
        if (usingTxBuffer1) {
          txAudioBufferIdx1 = ulmap(now, startedTxMicros, targetTxBufferEndMicros, 0, txAudioBufferLen1 - 1); // Advance a number of audio bytes relative to time since last DAC update
          dacWrite(DAC_PIN, txAudioBuffer1[txAudioBufferIdx1]);          
        } else {
          txAudioBufferIdx2 = ulmap(now, startedTxMicros, targetTxBufferEndMicros, 0, txAudioBufferLen2 - 1); // Advance a number of audio bytes relative to time since last DAC update
          dacWrite(DAC_PIN, txAudioBuffer2[txAudioBufferIdx2]);   
        }
      }
    }
  } catch (int e) {
    // Disregard, we don't want to crash. Just pick up at next loop of readWriteAnalog().
    // Serial.println("Exception in readWriteAnalog(), skipping cycle.");
  }
}

void setupTimer() {
  // Configure a hardware timer
  hw_timer_t * timer = NULL;
  timer = timerBegin(0, PROCESSOR_MHZ, true); // Use the first timer, prescale by 240 (for 1MHz counting on 240MHz ESP32), count up

  // Set the timer to call the readWriteAnalog function
  timerAttachInterrupt(timer, &readWriteAnalog, true);
  timerAlarmWrite(timer, ADC_DAC_CALL_MICROSECONDS, true);
  timerAlarmEnable(timer);
}

void loop() {
  try {
    // Check for incoming commands or audio from Android
    int bytesRead = 0;
    uint8_t tempBuffer[TX_AUDIO_BUFFER_SIZE];
    if (Serial.available() > 0) {
      bytesRead = Serial.readBytes(tempBuffer, TX_AUDIO_BUFFER_SIZE);

      for (int i = 0; i < bytesRead; i++) {
        // If we've seen the entire delimiter...
        if (matchedDelimiterTokens == DELIMITER_LENGTH) {
          // Process next byte as a command.
          uint8_t command = tempBuffer[i];
          // TODO remove the delimiter and command from buffer so it's not played back as audio (just a few bytes, can't really hear it)
          matchedDelimiterTokens = 0;
          switch (command) {
            case COMMAND_PTT_DOWN:
              setMode(MODE_TX);
              break;
            case COMMAND_PTT_UP:
              setMode(MODE_RX);
              break;
            case COMMAND_TUNE_TO:
              // Example:
              // 145.450144.85006
              // 7 chars for tx, 7 chars for rx, 2 chars for tone

              char freqTxChars[7] = {tempBuffer[i + 1], tempBuffer[i + 2], tempBuffer[i + 3], tempBuffer[i + 4], tempBuffer[i + 5], tempBuffer[i + 6], tempBuffer[i + 7]};
              String freqTxStr = String(freqTxChars);
              float freqTxFloat = freqTxStr.toFloat();

              i += 7; // Skip over the tx frequency string now that we have it.

              char freqRxChars[7] = {tempBuffer[i + 1], tempBuffer[i + 2], tempBuffer[i + 3], tempBuffer[i + 4], tempBuffer[i + 5], tempBuffer[i + 6], tempBuffer[i + 7]};
              String freqRxStr = String(freqRxChars);
              float freqRxFloat = freqRxStr.toFloat();

              i += 7; // Skip over the rx frequency string now that we have it

              char toneChars[2] = {tempBuffer[i + 1], tempBuffer[i + 2]};
              String toneStr = String(toneChars);
              int toneInt = toneStr.toInt();

              i += 2; // Skip over the tone string now that we have it

              tuneTo(freqTxFloat, freqRxFloat, toneInt);

              break;
          }
        } else {
          if (tempBuffer[i] == delimiter[matchedDelimiterTokens]) { // This byte may be part of the delimiter
            matchedDelimiterTokens++;
          } else { // This byte is not consistent with the command delimiter, reset counter
            matchedDelimiterTokens = 0;
          }
        }
      }
    }

    if (mode == MODE_RX) {
      // Intercept the rx audio buffer before an overrun happens.
      if (rxAudioBufferIdx >= RX_AUDIO_BUFFER_SIZE - BYTES_TO_TRIGGER_FROM_BUFFER_END) { 
        // Copy the buffer so the timer reading from ADC can continue without being blocked.
        // This is very quick compared to writing to Serial.
        int copySize = std::min(rxAudioBufferIdx, RX_AUDIO_BUFFER_SIZE);
        memcpy(rxAudioBufferCopy, rxAudioBuffer, copySize);
        rxAudioBufferIdx = 0; // This will cause sampling to resume in parallel.

        // If the radio module is indicating that squelch is active, force the audio to quiet (remove background hiss).
        if (digitalRead(SQ_PIN) == HIGH) {
          for (int i = 0; i < copySize; i++) {
            rxAudioBufferCopy[i] = 128; // No sound (half of byte, or 0v)
          }
        }

        if (Serial.availableForWrite()) {
          Serial.write(rxAudioBufferCopy, copySize);
          Serial.flush();
        }
      }
    } else if (mode == MODE_TX) {
      processTxAudio(tempBuffer, bytesRead);
    }

    // Regularly reset the WDT timer to prevent the device from rebooting (prove we're not locked up).
    esp_task_wdt_reset();
  } catch (int e) {
    // Disregard, we don't want to crash. Just pick up at next loop().)
    Serial.println("Exception in loop(), skipping cycle.");
  }
}

void tuneTo(float freqTx, float freqRx, int tone) {
  int result = dra->group(DRA818_25K, freqTx, freqRx, tone, 0, 0);  // TODO use squelch setting here when we have it
  // Serial.println("tuneTo: " + String(result));
}

void setMode(int newMode) {
  mode = newMode;
  switch (mode) {
    case MODE_RX:
      neopixel.setPixelColor(0, 0, 10, 0);
      neopixel.show();
      digitalWrite(PTT_PIN, HIGH);
      rxAudioBufferIdx = 0;
      break;
    case MODE_TX:
      neopixel.setPixelColor(0, 10, 0, 0);
      neopixel.show();
      digitalWrite(PTT_PIN, LOW);
      usingTxBuffer1 = true;
      txAudioBufferIdx1 = 0;
      txAudioBufferLen1 = 0;
      txAudioBufferIdx2 = 0;
      txAudioBufferLen2 = 0;
      break;
  }
}

void processTxAudio(uint8_t tempBuffer[], int bytesRead) {
  // Add this next chunk of audio to the audio buffer
  if (bytesRead > 0) {
    if (usingTxBuffer1) { // Read into tx buffer 2 while buffer 1 is playing
      memcpy(txAudioBuffer2 + txAudioBufferLen2, tempBuffer, std::min(bytesRead, TX_AUDIO_BUFFER_SIZE - txAudioBufferLen2));
      txAudioBufferLen2 += std::min(bytesRead, TX_AUDIO_BUFFER_SIZE - txAudioBufferLen2);
      if (txAudioBufferLen2 >= TX_AUDIO_BUFFER_SIZE - BYTES_TO_TRIGGER_FROM_BUFFER_END) {
        usingTxBuffer1 = false; // Start playing from buffer 2, it's full.
        startedTxMicros = micros();
        targetTxBufferEndMicros = startedTxMicros + (1000000 * txAudioBufferLen2 / TX_AUDIO_BUFFER_SIZE * TX_AUDIO_BUFFER_SIZE / AUDIO_SAMPLE_RATE); // Transmit should be done by this system time, used to regulate DAC playback rate
        txAudioBufferLen1 = 0;
      }
    } else { // Read into tx buffer 1 while buffer 2 is playing
      memcpy(txAudioBuffer1 + txAudioBufferLen1, tempBuffer, std::min(bytesRead, TX_AUDIO_BUFFER_SIZE - txAudioBufferLen1));
      txAudioBufferLen1 += std::min(bytesRead, TX_AUDIO_BUFFER_SIZE - txAudioBufferLen1);
      if (txAudioBufferLen1 >= TX_AUDIO_BUFFER_SIZE - BYTES_TO_TRIGGER_FROM_BUFFER_END) {
        usingTxBuffer1 = true; // Start playing from buffer 1, it's full.
        startedTxMicros = micros();
        targetTxBufferEndMicros = startedTxMicros + (1000000 * txAudioBufferLen1 / TX_AUDIO_BUFFER_SIZE * TX_AUDIO_BUFFER_SIZE / AUDIO_SAMPLE_RATE); // Transmit should be done by this system time, used to regulate DAC playback rate
        txAudioBufferLen2 = 0;
      }
    }
  }
}
