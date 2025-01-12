/*
KV4P-HT (see http://kv4p.com)
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

#include <Arduino.h>

#include <algorithm>
#include <DRA818.h>
#include <driver/adc.h>
#include <driver/i2s.h>
#include <driver/dac.h>
#include <esp_task_wdt.h>

const byte FIRMWARE_VER[8] = {'0', '0', '0', '0', '0', '0', '0', '8'}; // Should be 8 characters representing a zero-padded version, like 00000001.
const byte VERSION_PREFIX[7] = {'V', 'E', 'R', 'S', 'I', 'O', 'N'}; // Must match RadioAudioService.VERSION_PREFIX in Android app.

// Commands defined here must match the Android app
const uint8_t COMMAND_PTT_DOWN = 1; // start transmitting audio that Android app will send
const uint8_t COMMAND_PTT_UP = 2; // stop transmitting audio, go into RX mode
const uint8_t COMMAND_TUNE_TO = 3; // change the frequency
const uint8_t COMMAND_FILTERS = 4; // toggle filters on/off
const uint8_t COMMAND_STOP = 5; // stop everything, just wait for next command
const uint8_t COMMAND_GET_FIRMWARE_VER = 6; // report FIRMWARE_VER in the format '00000001' for 1 (etc.)

// Outgoing commands (ESP32 -> Android)
const byte COMMAND_SMETER_REPORT = 0x53;

// S-meter interval
long lastSMeterReport = -1;
#define SMETER_REPORT_INTERVAL_MS 500

// Delimeter must also match Android app
#define DELIMITER_LENGTH 8
const uint8_t COMMAND_DELIMITER[DELIMITER_LENGTH] = {0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00};
int matchedDelimiterTokens = 0;
int matchedDelimiterTokensRx = 0;

// Mode of the app, which is essentially a state machine
#define MODE_TX 0
#define MODE_RX 1
#define MODE_STOPPED 2
int mode = MODE_STOPPED;

// Audio sampling rate, must match what Android app expects (and sends).
#define AUDIO_SAMPLE_RATE 22050

// Offset to make up for fact that sampling is slightly slower than requested, and we don't want underruns.
// But if this is set too high, then we get audio skips instead of underruns. So there's a sweet spot.
#define SAMPLING_RATE_OFFSET 79

// Buffer for outgoing audio bytes to send to radio module
#define TX_TEMP_AUDIO_BUFFER_SIZE 4096 // Holds data we already got off of USB serial from Android app
#define TX_CACHED_AUDIO_BUFFER_SIZE 1024 // MUST be smaller than DMA buffer size specified in i2sTxConfig, because we dump this cache into DMA buffer when full.
uint8_t txCachedAudioBuffer[TX_CACHED_AUDIO_BUFFER_SIZE] = {0};
int txCachedAudioBytes = 0;
boolean isTxCacheSatisfied = false; // Will be true when the DAC has enough cached tx data to avoid any stuttering (i.e. at least TX_CACHED_AUDIO_BUFFER_SIZE bytes).

// Max data to cache from USB (1024 is ESP32 max)
#define USB_BUFFER_SIZE 1024

// ms to wait before issuing PTT UP after a tx (to allow final audio to go out)
#define MS_WAIT_BEFORE_PTT_UP 40

// Connections to radio module
#define RXD2_PIN 16
#define TXD2_PIN 17
#define DAC_PIN 25 // This constant not used, just here for reference. GPIO 25 is implied by use of I2S_DAC_CHANNEL_RIGHT_EN.
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

// Squelch parameters (for graceful fade to silence)
#define FADE_SAMPLES 256 // Must be a power of two
#define ATTENUATION_MAX 256
int fadeCounter = 0;
int fadeDirection = 0; // 0: no fade, 1: fade in, -1: fade out
int attenuation = ATTENUATION_MAX; // Full volume
bool lastSquelched = false;


////////////////////////////////////////////////////////////////////////////////
/// Forward Declarations
////////////////////////////////////////////////////////////////////////////////

void initI2SRx();
void initI2STx();
void tuneTo(float freqTx, float freqRx, int tone, int squelch, String bandwidth);
void setMode(int newMode);
void processTxAudio(uint8_t tempBuffer[], int bytesRead);

void setup() {
  // Communication with Android via USB cable
  Serial.setRxBufferSize(USB_BUFFER_SIZE);
  Serial.setTxBufferSize(USB_BUFFER_SIZE);
  Serial.begin(230400);

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
  Serial2.setTimeout(10); // Very short so we don't tie up rx audio while reading from radio module (responses are tiny so this is ok)

  int result = -1;
  while (result != 1) {
    result = dra->handshake(); // Wait for module to start up
  }
  // Serial.println("handshake: " + String(result));
  result = dra->volume(8);
  // Serial.println("volume: " + String(result));
  result = dra->filters(false, false, false);
  // Serial.println("filters: " + String(result));

  // Begin in STOPPED mode
  setMode(MODE_STOPPED);
}

void initI2SRx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;

  // Initialize ADC
  adc1_config_width(ADC_WIDTH_BIT_12);
  adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_12);

  static const i2s_config_t i2sRxConfig = {
      .mode = (i2s_mode_t) (I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_ADC_BUILT_IN),
      .sample_rate = AUDIO_SAMPLE_RATE + SAMPLING_RATE_OFFSET,
      .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
      .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
      .communication_format = i2s_comm_format_t(I2S_COMM_FORMAT_I2S | I2S_COMM_FORMAT_I2S_MSB),
      .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
      .dma_buf_count = 4,
      .dma_buf_len = I2S_READ_LEN,
      .use_apll = true,
      .tx_desc_auto_clear = false,
      .fixed_mclk = 0
  };

  ESP_ERROR_CHECK(i2s_driver_install(I2S_NUM_0, &i2sRxConfig, 0, NULL));
  ESP_ERROR_CHECK(i2s_set_adc_mode(I2S_ADC_UNIT, I2S_ADC_CHANNEL));
  dac_output_enable(DAC_CHANNEL_2);  // GPIO26 (DAC1)
  dac_output_voltage(DAC_CHANNEL_2, 138);
}

void initI2STx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;

  static const i2s_config_t i2sTxConfig = {
      .mode = (i2s_mode_t) (I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
      .sample_rate = AUDIO_SAMPLE_RATE,
      .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
      .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
      .intr_alloc_flags = 0,
      .dma_buf_count = 8,
      .dma_buf_len = I2S_WRITE_LEN,
      .use_apll = true
  };

  i2s_driver_install(I2S_NUM_0, &i2sTxConfig, 0, NULL);
  i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN);           
}

void loop() {
  try {
    if (mode == MODE_STOPPED) {
      // Read a command from Android app
      uint8_t tempBuffer[100]; // Big enough for a command and params, won't hold audio data
      int bytesRead = 0;

      while (bytesRead < (DELIMITER_LENGTH + 1)) { // Read the delimiter and the command byte only (no params yet)
        if (Serial.available()) {
          tempBuffer[bytesRead++] = Serial.read();
        }
      }
      switch (tempBuffer[DELIMITER_LENGTH]) {

        case COMMAND_STOP:
        { 
          Serial.flush();
          break;
        }

        case COMMAND_GET_FIRMWARE_VER: 
        {
          Serial.write(VERSION_PREFIX, sizeof(VERSION_PREFIX));
          Serial.write(FIRMWARE_VER, sizeof(FIRMWARE_VER));
          Serial.flush();
          esp_task_wdt_reset();
          return;
        }

        // TODO get rid of the code duplication here and in MODE_RX below to handle COMMAND_TUNE_TO and COMMAND_FILTERS.
        // Should probably just have one standardized way to read any incoming bytes from Android app here, and handle
        // commands appropriately. Or at least extract the business logic from them to avoid that duplication.
        case COMMAND_TUNE_TO:
        {
          setMode(MODE_RX);

          // If we haven't received all the parameters needed for COMMAND_TUNE_TO, wait for them before continuing.
          // This can happen if ESP32 has pulled part of the command+params from the buffer before Android has completed
          // putting them in there. If so, we take byte-by-byte until we get the full params.
          int paramBytesMissing = 20;
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

          // Example:
          // 145.4500144.8500061W
          // 8 chars for tx, 8 chars for rx, 2 chars for tone, 1 char for squelch, 1 for bandwidth W/N (20 bytes total for params)
          float freqTxFloat = paramsStr.substring(0, 8).toFloat();
          float freqRxFloat = paramsStr.substring(8, 16).toFloat();
          int toneInt = paramsStr.substring(16, 18).toInt();
          int squelchInt = paramsStr.substring(18, 19).toInt();
          String bandwidth = paramsStr.substring(19, 20);

          tuneTo(freqTxFloat, freqRxFloat, toneInt, squelchInt, bandwidth);

          // Serial.println("PARAMS: " + paramsStr.substring(0, 16) + " freqTxFloat: " + String(freqTxFloat) + " freqRxFloat: " + String(freqRxFloat) + " toneInt: " + String(toneInt));
          break;
        }

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

          while (!dra->filters(emphasis, highpass, lowpass));
          break;
        }
      }

      esp_task_wdt_reset();
      return;
    } else if (mode == MODE_RX) {
      while (Serial.available()) {
        uint8_t inByte = Serial.read();        
        if (matchedDelimiterTokensRx < DELIMITER_LENGTH) {
            // Match delimiter sequence
            if (inByte == COMMAND_DELIMITER[matchedDelimiterTokensRx]) {
              matchedDelimiterTokensRx++;
            } else {
              matchedDelimiterTokensRx = 0; // Reset on mismatch
            }
        } else {
          matchedDelimiterTokensRx = 0;

          switch (inByte) {

            case COMMAND_STOP:
            {
              setMode(MODE_STOPPED);
              Serial.flush();
              esp_task_wdt_reset();
              return;
            }

            case COMMAND_PTT_DOWN: 
            {
              setMode(MODE_TX);
              esp_task_wdt_reset();
              return;
            }

            case COMMAND_TUNE_TO:
            {
              // If we haven't received all the parameters needed for COMMAND_TUNE_TO, wait for them before continuing.
              // This can happen if ESP32 has pulled part of the command+params from the buffer before Android has completed
              // putting them in there. If so, we take byte-by-byte until we get the full params.
              int paramBytesMissing = 20;
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

              // Example:
              // 145.4500144.8500061W
              // 8 chars for tx, 8 chars for rx, 2 chars for tone, 1 char for squelch, 1 for bandwidth W/N (20 bytes total for params)
              float freqTxFloat = paramsStr.substring(0, 8).toFloat();
              float freqRxFloat = paramsStr.substring(8, 16).toFloat();
              int toneInt = paramsStr.substring(16, 18).toInt();
              int squelchInt = paramsStr.substring(18, 19).toInt();
              String bandwidth = paramsStr.substring(19, 20);
              tuneTo(freqTxFloat, freqRxFloat, toneInt, squelchInt, bandwidth);
              break;
            }

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

              while (!dra->filters(emphasis, highpass, lowpass));
              break;
            }

            default:
            {
              break;
            }
          }
        }
      }
 
      // If it's been a while since our last S-meter report, send one back to Android app.
      if ((millis() - lastSMeterReport) >= SMETER_REPORT_INTERVAL_MS) {
        // TODO fix the dra818 library's implementation of rssi(). Right now it just drops the
        // return value from the module, and just tells us success/fail.
        // int rssi = dra->rssi();

        Serial2.println("RSSI?");
        String rssiResponse = Serial2.readString(); // Should be like "RSSI=X\n\r", where X is 1-3 digits, 0-255

        if (rssiResponse.length() > 7) {
          String rssiStr = rssiResponse.substring(5);
          int rssiInt = rssiStr.toInt();

          if (rssiInt >= 0 && rssiInt <= 255) {
            byte params[1] = { (uint8_t) rssiInt };
            sendCmdToAndroid(COMMAND_SMETER_REPORT, params, /* paramsLen */ 1);
          }
        }

        // It doesn't matter if we successfully got the S-meter reading, we only want to check at most once every SMETER_REPORT_INTERVAL_MS
        lastSMeterReport = millis();
      }


      size_t bytesRead = 0;
      uint8_t buffer32[I2S_READ_LEN * 4] = {0};
      ESP_ERROR_CHECK(i2s_read(I2S_NUM_0, &buffer32, sizeof(buffer32), &bytesRead, 100));
      size_t samplesRead = bytesRead / 4;

      byte buffer8[I2S_READ_LEN] = {0};
      bool squelched = (digitalRead(SQ_PIN) == HIGH);

      // Check for squelch status change
      if (squelched != lastSquelched) {
        if (squelched) {
          // Start fade-out
          fadeCounter = FADE_SAMPLES;
          fadeDirection = -1;
        } else {
          // Start fade-in
          fadeCounter = FADE_SAMPLES;
          fadeDirection = 1;
        }
      }
      lastSquelched = squelched;

      int attenuationIncrement = ATTENUATION_MAX / FADE_SAMPLES;

      for (int i = 0; i < samplesRead; i++) {
        uint8_t sampleValue;

        // Extract 8-bit sample from 32-bit buffer
        sampleValue = buffer32[i * 4 + 3] << 4;
        sampleValue |= buffer32[i * 4 + 2] >> 4;

        // Adjust attenuation during fade
        if (fadeCounter > 0) {
          fadeCounter--;
          attenuation += fadeDirection * attenuationIncrement;
          attenuation = max(0, min(attenuation, ATTENUATION_MAX));
        } else {
          attenuation = squelched ? 0 : ATTENUATION_MAX;
          fadeDirection = 0;
        }

        // Apply attenuation to the sample
        int adjustedSample = (((int)sampleValue - 128) * attenuation) >> 8;
        adjustedSample += 128;
        buffer8[i] = (uint8_t)adjustedSample;
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
      uint8_t tempBuffer[TX_TEMP_AUDIO_BUFFER_SIZE];
      int bytesAvailable = Serial.available();
      if (bytesAvailable > 0) {
        bytesRead = Serial.readBytes(tempBuffer, bytesAvailable);

        // Pre-cache transmit audio to ensure precise timing (required for any data encoding to work, such as BFSK).
        if (!isTxCacheSatisfied) {
          if (txCachedAudioBytes + bytesRead >= TX_CACHED_AUDIO_BUFFER_SIZE) {
            isTxCacheSatisfied = true;
            processTxAudio(txCachedAudioBuffer, txCachedAudioBytes); // Process cached bytes
          } else {
            memcpy(txCachedAudioBuffer + txCachedAudioBytes, tempBuffer, bytesRead); // Store bytes to cache
            txCachedAudioBytes += bytesRead;
          }
        } 

        if (isTxCacheSatisfied) { // Note that it may have JUST been satisfied above, in which case we processed the cache, and will now process tempBuffer.
          processTxAudio(tempBuffer, bytesRead);
        }

        for (int i = 0; i < bytesRead && i < TX_TEMP_AUDIO_BUFFER_SIZE; i++) {
          // If we've seen the entire delimiter...
          if (matchedDelimiterTokens == DELIMITER_LENGTH) {
            // Process next byte as a command.
            uint8_t command = tempBuffer[i];
            matchedDelimiterTokens = 0;
            switch (command) {
              case COMMAND_STOP:
              {
                delay(MS_WAIT_BEFORE_PTT_UP); // Wait just a moment so final tx audio data in DMA buffer can be transmitted.
                setMode(MODE_STOPPED);
                esp_task_wdt_reset();
                return;
              }
                break;
              case COMMAND_PTT_UP:
              {
                delay(MS_WAIT_BEFORE_PTT_UP); // Wait just a moment so final tx audio data in DMA buffer can be transmitted.
                setMode(MODE_RX);
                esp_task_wdt_reset();
                return;
              }
                break;
            }
          } else {
            if (tempBuffer[i] == COMMAND_DELIMITER[matchedDelimiterTokens]) { // This byte may be part of the delimiter
              matchedDelimiterTokens++;
            } else { // This byte is not consistent with the command delimiter, reset counter
              matchedDelimiterTokens = 0;
            }
          }
        }
      }
    }

    // Regularly reset the WDT timer to prevent the device from rebooting (prove we're not locked up).
    esp_task_wdt_reset();
  } catch (int e) {
    // Disregard, we don't want to crash. Just pick up at next loop().)
    // Serial.println("Exception in loop(), skipping cycle.");
  }
}

/**
 * Example: 
 *   [DELIMITER(8 bytes)] [CMD(1 byte)] [paramLen(1 byte)] [param data(N bytes)]
 *
 * Where:
 *   - DELIMITER is {0xFF,0x00,0xFF,0x00,0xFF,0x00,0xFF,0x00}
 *   - CMD is e.g. 0x53 for your S-meter
 *   - paramLen is up to 255
 *   - param data is 'paramLen' bytes
 */
void sendCmdToAndroid(byte cmdByte, const byte* params, size_t paramsLen)
{
    // Safety check: limit paramsLen to 255 for 1-byte length
    if (paramsLen > 255) {
        paramsLen = 255;  // or handle differently (split, or error, etc.)
    }

    const size_t totalSize = DELIMITER_LENGTH + 1 + 1 + paramsLen;
    byte outBytes[totalSize];

    // 1. Leading delimiter
    memcpy(outBytes, COMMAND_DELIMITER, DELIMITER_LENGTH);

    // 2. Command byte
    outBytes[DELIMITER_LENGTH] = cmdByte;

    // 3. Parameter length
    outBytes[DELIMITER_LENGTH + 1] = (byte)(paramsLen & 0xFF);

    // 4. Parameter bytes
    memcpy(
        outBytes + DELIMITER_LENGTH + 2, // position after delim+cmd+paramLen
        params,
        paramsLen
    );

    Serial.write(outBytes, totalSize);
    Serial.flush();
}

void tuneTo(float freqTx, float freqRx, int tone, int squelch, String bandwidth) {
  initI2SRx();

  // Tell radio module to tune
  int result = 0;
  while (!result) {
    if (bandwidth.equals("W")) {
      result = dra->group(DRA818_25K, freqTx, freqRx, tone, squelch, 0);
    } else if (bandwidth.equals("N")) {
      result = dra->group(DRA818_12K5, freqTx, freqRx, tone, squelch, 0);
    }
  }
  // Serial.println("tuneTo: " + String(result));
}

void setMode(int newMode) {
  mode = newMode;
  switch (mode) {
    case MODE_STOPPED:
      digitalWrite(LED_PIN, LOW);
      digitalWrite(PTT_PIN, HIGH);
      break;
    case MODE_RX:
      digitalWrite(LED_PIN, LOW);
      digitalWrite(PTT_PIN, HIGH);
      initI2SRx();
      matchedDelimiterTokensRx = 0;
      break;
    case MODE_TX:
      txStartTime = micros();
      digitalWrite(LED_PIN, HIGH);
      digitalWrite(PTT_PIN, LOW);
      initI2STx();
      txCachedAudioBytes = 0;
      isTxCacheSatisfied = false;
      break;
  }
}

void processTxAudio(uint8_t tempBuffer[], int bytesRead) {
  if (bytesRead == 0) {
    return;
  }

  // Convert the 8-bit audio data to 16-bit
  uint8_t buffer16[bytesRead * 2] = {0};
  for (int i = 0; i < bytesRead; i++) {
    buffer16[i * 2 + 1] = tempBuffer[i]; // Move 8-bit audio into top 8 bits of 16-bit byte that I2S expects.
  }

  size_t totalBytesWritten = 0;
  size_t bytesWritten;
  size_t bytesToWrite = sizeof(buffer16);
  do {
    ESP_ERROR_CHECK(i2s_write(I2S_NUM_0, buffer16 + totalBytesWritten, bytesToWrite, &bytesWritten, 100)); 
    totalBytesWritten += bytesWritten;
    bytesToWrite -= bytesWritten;
  } while (bytesToWrite > 0);
}
