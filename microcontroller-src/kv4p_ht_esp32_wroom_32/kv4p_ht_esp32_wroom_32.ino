#include <algorithm>
#include <DRA818.h>
#include <driver/adc.h>
#include <driver/i2s.h>
#include <esp_task_wdt.h>

// Commands defined here must match the Android app
const uint8_t COMMAND_PTT_DOWN = 1; // start transmitting audio that Android app will send
const uint8_t COMMAND_PTT_UP = 2; // stop transmitting audio, go into RX mode
const uint8_t COMMAND_TUNE_TO = 3; // change the frequency
const uint8_t COMMAND_FILTERS = 4; // toggle filters on/off

// Delimeter must also match Android app
#define DELIMITER_LENGTH 8
const uint8_t delimiter[DELIMITER_LENGTH] = {0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00};
int matchedDelimiterTokens = 0;

// Mode of the app, which is essentially a state machine
#define MODE_TX 0
#define MODE_RX 1
int mode = MODE_RX;

// Audio sampling rate, must match what Android app expects (and sends).
#define AUDIO_SAMPLE_RATE 44100

// Offset to make up for fact that sampling is slightly slower than requested, and we don't want underruns.
// But if this is set too high, then we get audio skips instead of underruns. So there's a sweet spot.
#define SAMPLING_RATE_OFFSET 200

// Buffer for outgoing audio bytes to send to radio module
#define TX_AUDIO_BUFFER_SIZE 1024 // Holds data we already got off of USB serial from Android app

// Max data to cache from USB (1024 is ESP32 max)
#define USB_BUFFER_SIZE 1024

// Connections to radio module
#define RXD2_PIN 16
#define TXD2_PIN 17
#define DAC_PIN 25 // This constant not used, just here for reference.
#define ADC_PIN 34 // If this is changed, you may need to manually edit adc1_config_channel_atten() below too.
#define PTT_PIN 18
#define PD_PIN 19
#define SQ_PIN 32

// Built in LED
#define LED_PIN 2

// Object used for radio module serial comms
DRA818* dra = new DRA818(&Serial2, DRA818_VHF);

// Tx runaway detection stuff
long txStartTime = -1;
#define RUNAWAY_TX_SEC 200

// have we installed an I2S driver at least once?
bool i2sStarted = false;

// I2S audio sampling stuff
#define I2S_READ_LEN      1024
#define I2S_WRITE_LEN     1024
#define I2S_ADC_UNIT      ADC_UNIT_1
#define I2S_ADC_CHANNEL   ADC1_CHANNEL_6

void setup() {
  // Communication with Android via USB cable
  Serial.begin(921600);
  Serial.setRxBufferSize(USB_BUFFER_SIZE);
  Serial.setTxBufferSize(USB_BUFFER_SIZE);

  // Configure watch dog timer (WDT), which will reset the system if it gets stuck somehow.
  esp_task_wdt_init(10, true); // Reboot if locked up for a bit
  esp_task_wdt_add(NULL); // Add the current task to WDT watch

  // Debug LED
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // Set up radio module defaults
  pinMode(PD_PIN, OUTPUT);
  digitalWrite(PD_PIN, HIGH); // Power on
  pinMode(SQ_PIN, INPUT);
  pinMode(PTT_PIN, OUTPUT);
  digitalWrite(PTT_PIN, HIGH); // Rx

  // Communication with DRA818V radio module via GPIO pins
  Serial2.begin(9600, SERIAL_8N1, RXD2_PIN, TXD2_PIN);

  int result = -1;
  while (result != 1) {
    result = dra->handshake(); // Wait for module to start up
  }
  // Serial.println("handshake: " + String(result));
  // tuneTo(146.700, 146.700, 0, 0);
  result = dra->volume(8);
  // Serial.println("volume: " + String(result));
  result = dra->filters(false, false, false);
  // Serial.println("filters: " + String(result));

  // Start in RX mode
  setMode(MODE_RX);
}

void initI2SRx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;

  // Initialize ADC
  adc1_config_width(ADC_WIDTH_BIT_12);
  adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_0);

  static const i2s_config_t i2sRxConfig = {
      .mode = (i2s_mode_t) (I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_ADC_BUILT_IN),
      .sample_rate = AUDIO_SAMPLE_RATE + SAMPLING_RATE_OFFSET,
      .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
      .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
      .communication_format = i2s_comm_format_t(I2S_COMM_FORMAT_I2S | I2S_COMM_FORMAT_I2S_MSB),
      .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
      .dma_buf_count = 4,
      .dma_buf_len = I2S_READ_LEN,
      .use_apll = false,
      .tx_desc_auto_clear = false,
      .fixed_mclk = 0
  };

  ESP_ERROR_CHECK(i2s_driver_install(I2S_NUM_0, &i2sRxConfig, 0, NULL));
  ESP_ERROR_CHECK(i2s_set_adc_mode(I2S_ADC_UNIT, I2S_ADC_CHANNEL));
}

void initI2STx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;

  i2s_config_t i2sTxConfig = {
    .mode = (i2s_mode_t) (I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
    .sample_rate = AUDIO_SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_8BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
    .communication_format = i2s_comm_format_t(I2S_COMM_FORMAT_I2S | I2S_COMM_FORMAT_I2S_MSB),
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 4,
    .dma_buf_len = I2S_WRITE_LEN,
    .use_apll = false,
    .tx_desc_auto_clear = false,
    .fixed_mclk = 0
  };
  ESP_ERROR_CHECK(i2s_driver_install(I2S_NUM_0, &i2sTxConfig, 0, NULL));
  ESP_ERROR_CHECK(i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN)); // GPIO 25 is the default DAC pin
}

void loop() {
  try {
    if (mode == MODE_RX) {
      if (Serial.available()) {
        // Read a command from Android app
        uint8_t tempBuffer[100]; // Big enough for a command and params, won't hold audio data
        int bytesRead = 0;

        while (bytesRead < (DELIMITER_LENGTH + 1)) { // Read the delimiter and the command byte only (no params yet)
          tempBuffer[bytesRead++] = Serial.read();
          // while (bytesRead < (DELIMITER_LENGTH + 1) && !Serial.available()) { } // If we need more bytes, wait for a byte.
        }
        switch (tempBuffer[DELIMITER_LENGTH]) {
          case COMMAND_PTT_DOWN:
          {
            setMode(MODE_TX);
          }
            break;
          case COMMAND_TUNE_TO:
          {
            // Example:
            // 145.450144.850061
            // 7 chars for tx, 7 chars for rx, 2 chars for tone, 1 char for squelch (17 bytes total for params)
            setMode(MODE_RX);

            // If we haven't received all the parameters needed for COMMAND_TUNE_TO, wait for them before continuing.
            // This can happen if ESP32 has pulled part of the command+params from the buffer before Android has completed
            // putting them in there. If so, we take byte-by-byte until we get the full params.
            int paramBytesMissing = 17;
            String paramsStr = "";
            if (paramBytesMissing > 0) {
              uint8_t paramPartsBuffer[paramBytesMissing];
              for (int j = 0; j < paramBytesMissing; j++) {
                unsigned long waitStart = micros();
                while (!Serial.available()) { 
                  // Wait for a byte.
                  if ((micros() - waitStart) > 500000) { // Give the Android app 0.5 second max before giving up on the command
                    esp_task_wdt_reset();
                    return;
                  }
                }
                paramPartsBuffer[j] = Serial.read();
              }
              paramsStr += String((char *)paramPartsBuffer);
              paramBytesMissing--;
            }
            float freqTxFloat = paramsStr.substring(0, 8).toFloat();
            float freqRxFloat = paramsStr.substring(7, 15).toFloat();
            int toneInt = paramsStr.substring(14, 16).toInt();
            int squelchInt = paramsStr.substring(16, 17).toInt();

            // Serial.println("PARAMS: " + paramsStr.substring(0, 16) + " freqTxFloat: " + String(freqTxFloat) + " freqRxFloat: " + String(freqRxFloat) + " toneInt: " + String(toneInt));

            tuneTo(freqTxFloat, freqRxFloat, toneInt, squelchInt);
          }
            break;
          case COMMAND_FILTERS:
          {
            int paramBytesMissing = 3; // e.g. 000, in order of emphasis, highpass, lowpass
            String paramsStr = "";
            if (paramBytesMissing > 0) {
              uint8_t paramPartsBuffer[paramBytesMissing];
              for (int j = 0; j < paramBytesMissing; j++) {
                unsigned long waitStart = micros();
                while (!Serial.available()) { 
                  // Wait for a byte.
                  if ((micros() - waitStart) > 500000) { // Give the Android app 0.5 second max before giving up on the command
                    esp_task_wdt_reset();
                    return;
                  }
                }
                paramPartsBuffer[j] = Serial.read();
              }
              paramsStr += String((char *)paramPartsBuffer);
              paramBytesMissing--;
            }
            bool emphasis = (paramsStr.charAt(0) == '1');
            bool highpass = (paramsStr.charAt(1) == '1');
            bool lowpass = (paramsStr.charAt(2) == '1');

            dra->filters(emphasis, highpass, lowpass);
          }
            break;
          default:
          {
            // Unexpected.
          }
            break;
        }
      }

      size_t bytesRead = 0;
      uint8_t buffer32[I2S_READ_LEN * 4] = {0};
      ESP_ERROR_CHECK(i2s_read(I2S_NUM_0, &buffer32, sizeof(buffer32), &bytesRead, 100));
      size_t samplesRead = bytesRead / 4;

      byte buffer8[I2S_READ_LEN] = {0};
      bool squelched = (digitalRead(SQ_PIN) == HIGH);
      for (int i = 0; i < samplesRead; i++) {
        if (!squelched) {
          // The ADC can only sample at 12-bits, so we extract the 8 most-significant bits of that from the 32-bit value.
          buffer8[i] = buffer32[i * 4 + 3] << 4; // Get top 4 bits from last byte of sample
          buffer8[i] |= buffer32[i * 4 + 2] >> 4; // Get bottom 4 bits from second to last byte of sample
        } else {
          buffer8[i] = 128; // No sound (half of byte, or 0v)
        }
      } 

      Serial.write(buffer8, samplesRead);
    } else if (mode == MODE_TX) {
      // Check for runaway tx
      int txSeconds = (micros() - txStartTime) / 1000000;
      if (txSeconds > RUNAWAY_TX_SEC) {
        setMode(MODE_RX);
        esp_task_wdt_reset();
        return;
      }

      // Check for incoming commands or audio from Android
      int bytesRead = 0;
      uint8_t tempBuffer[TX_AUDIO_BUFFER_SIZE];
      int bytesAvailable = Serial.available();
      if (bytesAvailable > 0) {
        bytesRead = Serial.readBytes(tempBuffer, bytesAvailable);

        for (int i = 0; i < bytesRead; i++) {
          // If we've seen the entire delimiter...
          if (matchedDelimiterTokens == DELIMITER_LENGTH) {
            // Process next byte as a command.
            uint8_t command = tempBuffer[i];
            matchedDelimiterTokens = 0;
            switch (command) {
              case COMMAND_PTT_UP: // Only command we can receive in TX mode is PTT_UP
                setMode(MODE_RX);
                esp_task_wdt_reset();
                return; // Discards remaining bytes from Android app (tx audio remnants which could cause issues now that we're in MODE_RX).
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
      processTxAudio(tempBuffer, bytesRead);
    }

    // Regularly reset the WDT timer to prevent the device from rebooting (prove we're not locked up).
    esp_task_wdt_reset();
  } catch (int e) {
    // Disregard, we don't want to crash. Just pick up at next loop().)
    // Serial.println("Exception in loop(), skipping cycle.");
  }
}

void tuneTo(float freqTx, float freqRx, int tone, int squelch) {
  int result = dra->group(DRA818_25K, freqTx, freqRx, tone, squelch, 0);
  // Serial.println("tuneTo: " + String(result));
}

void setMode(int newMode) {
  mode = newMode;
  switch (mode) {
    case MODE_RX:
      digitalWrite(LED_PIN, LOW);
      digitalWrite(PTT_PIN, HIGH);
      initI2SRx();
      break;
    case MODE_TX:
      txStartTime = micros();
      digitalWrite(LED_PIN, HIGH);
      digitalWrite(PTT_PIN, LOW);
      initI2STx();
      break;
  }
}

// TODO simulate receiving bytes here with ESP32 plugged into laptop so I can see if this is crashing somehow.
// Figure out WHY ESP32 is crashing.
void processTxAudio(uint8_t tempBuffer[], int bytesRead) {
  if (bytesRead == 0) {
    return;
  }

  size_t bytesWritten;
  ESP_ERROR_CHECK(i2s_write(I2S_NUM_0, tempBuffer, bytesRead, &bytesWritten, 100));
}
