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

// Buffer for sample audio bytes from the radio module
#define RX_AUDIO_BUFFER_SIZE 4000
uint8_t rxAudioBuffer[RX_AUDIO_BUFFER_SIZE]; // Circular buffer
uint8_t* rxBufferHead = &rxAudioBuffer[0];
uint8_t* rxBufferTail = &rxAudioBuffer[0];
#define AUDIO_SEND_THRESHOLD 500 // minimum bytes in buffer before they'll be sent

// Buffer for outgoing audio bytes to send to radio module
#define TX_AUDIO_BUFFER_SIZE 1000 // Holds data we already got off of USB serial from Android app
// TODO change this to a circular buffer too
uint8_t txAudioBuffer1[TX_AUDIO_BUFFER_SIZE]; // Processed tx audio bytes that will be sent to radio module
uint8_t txAudioBuffer2[TX_AUDIO_BUFFER_SIZE]; // 2nd tx audio buffer so we can alternate to avoid any gaps due to processing
bool usingTxBuffer1 = true;
int txAudioBufferIdx1 = 0;
int txAudioBufferLen1 = 0;
int txAudioBufferIdx2 = 0;
int txAudioBufferLen2 = 0;

#define USB_BUFFER_SIZE 1024

// The number of bytes from the end of any buffer when rx or tx audio is processed (to avoid overrun during async processing)
#define BYTES_TO_TRIGGER_FROM_BUFFER_END 20

// Connections to radio module
#define RXD2_PIN 16
#define TXD2_PIN 17
#define DAC_PIN 25
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

// I2S audio sampling stuff
#define I2S_READ_LEN      1024
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
  pinMode(DAC_PIN, OUTPUT);

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

  // TEMPORARY testing stuff
  initI2S();
}

void initI2S() {
  // Initialize ADC
  adc1_config_width(ADC_WIDTH_BIT_12);
  adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_0);

  static const i2s_config_t i2s_config = {
      .mode = (i2s_mode_t) (I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_ADC_BUILT_IN),
      .sample_rate = AUDIO_SAMPLE_RATE,
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

  ESP_ERROR_CHECK(i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL));
  ESP_ERROR_CHECK(i2s_set_adc_mode(I2S_ADC_UNIT, I2S_ADC_CHANNEL));

  i2s_pin_config_t pin_config = {
      .bck_io_num = 14,
      .ws_io_num = 15,
      .data_out_num = -1,
      .data_in_num = 34,
  };
  ESP_ERROR_CHECK(i2s_set_pin(I2S_NUM_0, &pin_config));

  // Configure GPIO 34 as an input and disable pull-up/pull-down resistors
  gpio_pad_select_gpio(GPIO_NUM_34);
  gpio_set_pull_mode(GPIO_NUM_34, GPIO_FLOATING); // Disable both pull-up and pull-down
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

      // TODO rewrite this to store/send the full 12-bits that we're sampling (right now we're discarding the 4 lowest bits).
      // This makes the app more complex because we now need to send multiple bytes for each audio sample (we can't assume each byte is a sample).
      byte buffer8[I2S_READ_LEN] = {0};
      bool squelched = (digitalRead(SQ_PIN) == HIGH);
      for (int i = 0; i < samplesRead; i++) {
        if (!squelched) {
          // The ADC can only sample at 12-bits, so we extract the 8 most-significant bits of that from the 32-bit value.
          buffer8[i] = buffer32[i * 4 + 3] << 4; // 4 top bits from last byte of sample
          buffer8[i] |= buffer32[i * 4 + 2] >> 4; // 4 bottom bits from second to last byte of sample
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
      break;
    case MODE_TX:
      txStartTime = micros();
      digitalWrite(LED_PIN, HIGH);
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
  /* if (bytesRead > 0) {
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
  } */
}
