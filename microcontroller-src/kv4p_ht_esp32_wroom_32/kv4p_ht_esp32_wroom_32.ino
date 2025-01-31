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

const byte FIRMWARE_VER[8]   = {'0', '0', '0', '0', '0', '0', '1', '1'};  // Should be 8 characters representing a zero-padded version, like 00000001.
const byte VERSION_PREFIX[7] = {'V', 'E', 'R', 'S', 'I', 'O', 'N'};       // Must match RadioAudioService.VERSION_PREFIX in Android app.

// Commands defined here must match the Android app
const uint8_t COMMAND_PTT_DOWN         = 1;  // start transmitting audio that Android app will send
const uint8_t COMMAND_PTT_UP           = 2;  // stop transmitting audio, go into RX mode
const uint8_t COMMAND_TUNE_TO          = 3;  // change the frequency
const uint8_t COMMAND_FILTERS          = 4;  // toggle filters on/off
const uint8_t COMMAND_STOP             = 5;  // stop everything, just wait for next command
const uint8_t COMMAND_GET_FIRMWARE_VER = 6;  // report FIRMWARE_VER in the format '00000001' for 1 (etc.)

// Outgoing commands (ESP32 -> Android)
const byte COMMAND_SMETER_REPORT  = 0x53; // 'S'
const byte COMMAND_PHYS_PTT_DOWN  = 0x44; // 'D'
const byte COMMAND_PHYS_PTT_UP    = 0x55; // 'U'
const byte COMMAND_DEBUG_INFO     = 0x01;
const byte COMMAND_DEBUG_ERROR    = 0x02;
const byte COMMAND_DEBUG_WARN     = 0x03;
const byte COMMAND_DEBUG_DEBUG    = 0x04;
const byte COMMAND_DEBUG_TRACE    = 0x05;

// S-meter interval
long lastSMeterReport = -1;
#define SMETER_REPORT_INTERVAL_MS 500

// Delimeter must also match Android app
#define DELIMITER_LENGTH 8
const uint8_t COMMAND_DELIMITER[DELIMITER_LENGTH] = {0xDE, 0xAD, 0xBE, 0xEF, 0xDE, 0xAD, 0xBE, 0xEF};
int matchedDelimiterTokens                        = 0;
int matchedDelimiterTokensRx                      = 0;

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
#define TX_TEMP_AUDIO_BUFFER_SIZE 4096    // Holds data we already got off of USB serial from Android app
#define TX_CACHED_AUDIO_BUFFER_SIZE 1024  // MUST be smaller than DMA buffer size specified in i2sTxConfig, because we dump this cache into DMA buffer when full.
uint8_t txCachedAudioBuffer[TX_CACHED_AUDIO_BUFFER_SIZE] = {0};
int txCachedAudioBytes                                   = 0;
boolean isTxCacheSatisfied                               = false;  // Will be true when the DAC has enough cached tx data to avoid any stuttering (i.e. at least TX_CACHED_AUDIO_BUFFER_SIZE bytes).

// Max data to cache from USB (1024 is ESP32 max)
#define USB_BUFFER_SIZE 1024

// ms to wait before issuing PTT UP after a tx (to allow final audio to go out)
#define MS_WAIT_BEFORE_PTT_UP 40

// Connections to radio module
#define RXD2_PIN 16
#define TXD2_PIN 17
#define DAC_PIN 25  // This constant not used, just here for reference. GPIO 25 is implied by use of I2S_DAC_CHANNEL_RIGHT_EN.
#define ADC_PIN 34  // If this is changed, you may need to manually edit adc1_config_channel_atten() below too.
#define PTT_PIN 18  // Keys up the radio module
#define PD_PIN 19
//#define SQ_PIN 32
uint8_t SQ_PIN = 32;
#define PHYS_PTT_PIN1 5   // Optional. Buttons may be attached to either or both of this and next pin. They behave the same.
#define PHYS_PTT_PIN2 33  // Optional. See above.

#define DEBOUNCE_MS 50  // Minimum ms between PHYS_PTT_PIN1/2 down and then up, to avoid bouncing from spotty electrical contact.
boolean isPhysPttDown     = false;
long buttonDebounceMillis = -1;

// Built in LED
#define LED_PIN 2

// Object used for radio module serial comms
DRA818 *dra;

// Tx runaway detection stuff
long txStartTime = -1;
#define RUNAWAY_TX_SEC 200

// Were we able to communicate with the radio module during setup()?
const char RADIO_MODULE_NOT_FOUND = 'x';
const char RADIO_MODULE_FOUND     = 'f';
char radioModuleStatus            = RADIO_MODULE_NOT_FOUND;

// Have we installed an I2S driver at least once?
bool i2sStarted = false;

// I2S audio sampling stuff
#define I2S_READ_LEN 1024
#define I2S_WRITE_LEN 1024
#define I2S_ADC_UNIT ADC_UNIT_1
#define I2S_ADC_CHANNEL ADC1_CHANNEL_6

// Squelch parameters (for graceful fade to silence)
#define FADE_SAMPLES 256  // Must be a power of two
#define ATTENUATION_MAX 256
int fadeCounter    = 0;
int fadeDirection  = 0;                // 0: no fade, 1: fade in, -1: fade out
int attenuation    = ATTENUATION_MAX;  // Full volume
bool lastSquelched = false;

// 11dB vs 12dB is a ...version thing?
#ifndef ADC_ATTEN_DB_12
#define ADC_ATTEN_DB_12 ADC_ATTEN_DB_11
#endif

// Hardware version detection
#define HW_VER_PIN_0 39    // 0xF0
#define HW_VER_PIN_1 36    // 0x0F
typedef uint8_t hw_ver_t;  // This allows us to do a lot more in the future if we want.
// LOW = 0, HIGH = F, 1 <= analog values <= E
#define HW_VER_V1 (0x00)
#define HW_VER_V2_0C (0xFF)
#define HW_VER_V2_0D (0xF0)
// #define HW_VER_?? (0x0F)  // Unused
hw_ver_t hardware_version = HW_VER_V1;  // lowest common denominator

#define _LOGE(fmt, ...)           \
  {                                    \
    debug_log_printf(COMMAND_DEBUG_ERROR, ARDUHAL_LOG_FORMAT(E, fmt), ##__VA_ARGS__);       \
  }
#define _LOGW(fmt, ...)           \
  {                                    \
    debug_log_printf(COMMAND_DEBUG_WARN, ARDUHAL_LOG_FORMAT(W, fmt), ##__VA_ARGS__);       \
  }
#define _LOGI(fmt, ...)           \
  {                                    \
    debug_log_printf(COMMAND_DEBUG_INFO, ARDUHAL_LOG_FORMAT(I, fmt), ##__VA_ARGS__);       \
  }
#define _LOGD(fmt, ...)           \
  {                                    \
    debug_log_printf(COMMAND_DEBUG_DEBUG, ARDUHAL_LOG_FORMAT(D, fmt), ##__VA_ARGS__);       \
  }
#define _LOGT(fmt, ...)           \
  {                                    \
    debug_log_printf(COMMAND_DEBUG_TRACE, ARDUHAL_LOG_FORMAT(T, fmt), ##__VA_ARGS__);       \
  }

////////////////////////////////////////////////////////////////////////////////
/// Forward Declarations
////////////////////////////////////////////////////////////////////////////////

void initI2SRx();
void initI2STx();
void tuneTo(float freqTx, float freqRx, int tone, int squelch, String bandwidth);
void setMode(int newMode);
void processTxAudio(uint8_t tempBuffer[], int bytesRead);
void iir_lowpass_reset();
hw_ver_t get_hardware_version();
void reportPhysPttState();

void sendCmdToAndroid(byte cmdByte, const byte *params, size_t paramsLen);

int debug_log_printf(uint8_t cmd, const char* format, ...) {
  static char loc_buf[256];
  char* temp = loc_buf;
  int len;
  va_list arg;
  va_list copy;
  va_start(arg, format);
  va_copy(copy, arg);
  len = vsnprintf(NULL, 0, format, arg);
  va_end(copy);
  if (len >= sizeof(loc_buf)) {
    temp = (char*)malloc(len + 1);
    if (temp == NULL) {
      return 0;
    }
  }
  vsnprintf(temp, len + 1, format, arg);
  sendCmdToAndroid(cmd, (byte*) temp, len);
  va_end(arg);
  if (len >= sizeof(loc_buf)) {
    free(temp);
  }
  return len;
}

void setup() {
  // Used for setup, need to know early.
  hardware_version = get_hardware_version();

  // Communication with Android via USB cable
  Serial.setRxBufferSize(USB_BUFFER_SIZE);
  Serial.setTxBufferSize(USB_BUFFER_SIZE);
  Serial.begin(230400);

  // Hardware dependent pin assignments.
  switch (hardware_version) {
    case HW_VER_V1:
      SQ_PIN = 32;
      break;
    case HW_VER_V2_0C:
      SQ_PIN = 4;
      break;
    case HW_VER_V2_0D:
      SQ_PIN = 4;
      break;
    default:
      // Unknown version detected. Indicate this some way?
      break;
  }

  // Configure watch dog timer (WDT), which will reset the system if it gets stuck somehow.
  esp_task_wdt_init(10, true);  // Reboot if locked up for a bit
  esp_task_wdt_add(NULL);       // Add the current task to WDT watch

  // Debug LED
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // Optional physical PTT buttons
  pinMode(PHYS_PTT_PIN1, INPUT_PULLUP);
  pinMode(PHYS_PTT_PIN2, INPUT_PULLUP);

  // Set up radio module defaults
  pinMode(PD_PIN, OUTPUT);
  digitalWrite(PD_PIN, HIGH);  // Power on
  pinMode(SQ_PIN, INPUT);
  pinMode(PTT_PIN, OUTPUT);
  digitalWrite(PTT_PIN, HIGH);  // Rx

  // Communication with DRA818V radio module via GPIO pins
  Serial2.begin(9600, SERIAL_8N1, RXD2_PIN, TXD2_PIN);
  Serial2.setTimeout(10);  // Very short so we don't tie up rx audio while reading from radio module (responses are tiny so this is ok)

  // Begin in STOPPED mode
  setMode(MODE_STOPPED);
  _LOGI("Setup is finished");
}

void initI2SRx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;

  // Initialize ADC
  adc1_config_width(ADC_WIDTH_BIT_12);
  if (hardware_version == HW_VER_V2_0C) {
    // v2.0c has a lower input ADC range
    adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_0);
  } else {
    adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_12);
  }

  static const i2s_config_t i2sRxConfig = {
    .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_ADC_BUILT_IN),
    .sample_rate          = AUDIO_SAMPLE_RATE + SAMPLING_RATE_OFFSET,
    .bits_per_sample      = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = i2s_comm_format_t(I2S_COMM_FORMAT_I2S | I2S_COMM_FORMAT_I2S_MSB),
    .intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count        = 4,
    .dma_buf_len          = I2S_READ_LEN,
    .use_apll             = true,
    .tx_desc_auto_clear   = false,
    .fixed_mclk           = 0};

  ESP_ERROR_CHECK(i2s_driver_install(I2S_NUM_0, &i2sRxConfig, 0, NULL));
  ESP_ERROR_CHECK(i2s_set_adc_mode(I2S_ADC_UNIT, I2S_ADC_CHANNEL));
  dac_output_enable(DAC_CHANNEL_2);  // GPIO26 (DAC1)
  dac_output_voltage(DAC_CHANNEL_2, 138);
  iir_lowpass_reset();
}

void initI2STx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;

  static const i2s_config_t i2sTxConfig = {
    .mode             = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
    .sample_rate      = AUDIO_SAMPLE_RATE,
    .bits_per_sample  = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format   = I2S_CHANNEL_FMT_ONLY_RIGHT,
    .intr_alloc_flags = 0,
    .dma_buf_count    = 8,
    .dma_buf_len      = I2S_WRITE_LEN,
    .use_apll         = true};

  i2s_driver_install(I2S_NUM_0, &i2sTxConfig, 0, NULL);
  i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN);
}

#define DECAY_TIME 0.25  // seconds
#define ALPHA (1.0f - expf(-1.0f / (AUDIO_SAMPLE_RATE * (DECAY_TIME / logf(2.0f)))))

static float prev_y = 0.0f;

void iir_lowpass_reset() {
  prev_y = 0.0f;
}

// IIR Low-pass filter (float state)
int16_t iir_lowpass(int16_t x) {
  float x_f = (float)x;
  // IIR calculation: y[n] = α * x[n] + (1 - α) * y[n-1]
  prev_y = ALPHA * x_f + (1.0f - ALPHA) * prev_y;
  // Convert result back to int16
  return (int16_t)prev_y;
}

// High-pass: x[n] - LPF(x[n])
int16_t remove_dc(int16_t x) {
  return x - iir_lowpass(x);
}

void loop() {
  try {
    // Report any physical PTT button presses or releases back to Android app (note that
    // we don't start tx here, Android app decides what to do, since the user could be in
    // some mode where tx doesn't make sense, like in Settings).
    long msSincePhysButtonChange = millis() - buttonDebounceMillis;
    if (buttonDebounceMillis == -1 || msSincePhysButtonChange > DEBOUNCE_MS) {
      // If EITHER physical PTT button has just become "down", let Android app know
      if (!isPhysPttDown && (digitalRead(PHYS_PTT_PIN1) == LOW || digitalRead(PHYS_PTT_PIN2) == LOW)) {
        isPhysPttDown = true;
        reportPhysPttState();
        buttonDebounceMillis = millis();
      } else if (isPhysPttDown &&  // If BOTH PTT buttons are now "up", let Android app know
                 (digitalRead(PHYS_PTT_PIN1) == HIGH && digitalRead(PHYS_PTT_PIN2) == HIGH)) {
        isPhysPttDown = false;
        reportPhysPttState();
        buttonDebounceMillis = millis();
      }
    }

    if (mode == MODE_STOPPED) {
      // Read a command from Android app
      uint8_t tempBuffer[100];  // Big enough for a command and params, won't hold audio data
      int bytesRead = 0;

      while (bytesRead < (DELIMITER_LENGTH)) {  // Read the delimiter and the command byte only (no params yet)
        uint8_t tmp = Serial.read();
        if (tmp != COMMAND_DELIMITER[bytesRead]) {
          // Not a delimiter. Reset.
          bytesRead = 0;
          continue;
        }
        tempBuffer[bytesRead++] = tmp;
      }
      tempBuffer[DELIMITER_LENGTH] = Serial.read();
      switch (tempBuffer[DELIMITER_LENGTH]) {

        case COMMAND_STOP:
          {
            Serial.flush();
            break;
          }

        case COMMAND_GET_FIRMWARE_VER:
          {
            // The command must tell us what kind of radio module we're working with, grab that.
            int paramBytesMissing = 1;  // e.g. "v" or "u" for VHF or UHF respectively
            String paramsStr      = "";
            if (paramBytesMissing > 0) {
              uint8_t paramPartsBuffer[paramBytesMissing];
              for (int j = 0; j < paramBytesMissing; j++) {
                unsigned long waitStart = micros();
                while (!Serial.available()) {
                  // Wait for a byte.
                  if ((micros() - waitStart) > 500000) {  // Give the Android app 0.5 second max before giving up on the command
                    esp_task_wdt_reset();
                    return;
                  }
                }
                paramPartsBuffer[j] = Serial.read();
              }
              paramsStr += String((char *)paramPartsBuffer);
              paramBytesMissing--;
            }
            if (paramsStr.charAt(0) == 'v') {
              dra = new DRA818(&Serial2, DRA818_VHF);
            } else if (paramsStr.charAt(0) == 'u') {
              dra = new DRA818(&Serial2, DRA818_UHF);
            } else {
              // Unexpected.
            }

            int result              = -1;
            unsigned long waitStart = micros();
            while (result != 1) {
              result = dra->handshake();  // Wait for module to start up
              // Serial.println("handshake: " + String(result));

              if ((micros() - waitStart) > 2000000) {  // Give the radio module 2 seconds max before giving up on it
                radioModuleStatus = RADIO_MODULE_NOT_FOUND;
                break;
              }
            }

            if (result == 1) {  // Did we hear back from radio?
              radioModuleStatus = RADIO_MODULE_FOUND;
            }

            if (hardware_version == HW_VER_V2_0C) {
              // v2.0c has a lower input ADC range.
              result = dra->volume(4);
            } else {
              result = dra->volume(8);
            }
            // Serial.println("volume: " + String(result));
            result = dra->filters(false, false, false);
            // Serial.println("filters: " + String(result));

            Serial.write(VERSION_PREFIX, sizeof(VERSION_PREFIX));  // "VERSION"
            Serial.write(FIRMWARE_VER, sizeof(FIRMWARE_VER));      // "00000007" (or whatever)
            uint8_t radioModuleStatusArray[1] = {radioModuleStatus};
            Serial.write(radioModuleStatusArray, 1);  // "f" (or "x" if there's a problem with radio module)

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
            int paramBytesMissing = 22;
            String paramsStr      = "";
            if (paramBytesMissing > 0) {
              uint8_t paramPartsBuffer[paramBytesMissing];
              for (int j = 0; j < paramBytesMissing; j++) {
                unsigned long waitStart = micros();
                while (!Serial.available()) {
                  // Wait for a byte.
                  if ((micros() - waitStart) > 500000) {  // Give the Android app 0.5 second max before giving up on the command
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
            // 8 chars for tx, 8 chars for rx, 2 chars for tx tone, 2 chars for rx tone, 1 char for squelch, 1 for bandwidth W/N (20 bytes total for params)
            float freqTxFloat = paramsStr.substring(0, 8).toFloat();
            float freqRxFloat = paramsStr.substring(8, 16).toFloat();
            int txToneInt     = paramsStr.substring(16, 18).toInt();
            int rxToneInt     = paramsStr.substring(18, 20).toInt();
            int squelchInt    = paramsStr.substring(20, 21).toInt();
            String bandwidth  = paramsStr.substring(21, 22);

            tuneTo(freqTxFloat, freqRxFloat, txToneInt, rxToneInt, squelchInt, bandwidth);

            // Serial.println("PARAMS: " + paramsStr.substring(0, 16) + " freqTxFloat: " + String(freqTxFloat) + " freqRxFloat: " + String(freqRxFloat) + " toneInt: " + String(toneInt));
            break;
          }

        case COMMAND_FILTERS:
          {
            int paramBytesMissing = 3;  // e.g. 000, in order of emphasis, highpass, lowpass
            String paramsStr      = "";
            if (paramBytesMissing > 0) {
              uint8_t paramPartsBuffer[paramBytesMissing];
              for (int j = 0; j < paramBytesMissing; j++) {
                unsigned long waitStart = micros();
                while (!Serial.available()) {
                  // Wait for a byte.
                  if ((micros() - waitStart) > 500000) {  // Give the Android app 0.5 second max before giving up on the command
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
            bool lowpass  = (paramsStr.charAt(2) == '1');

            while (!dra->filters(emphasis, highpass, lowpass));
          }
          break;
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
            matchedDelimiterTokensRx = 0;  // Reset on mismatch
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
                int paramBytesMissing = 22;
                String paramsStr      = "";
                if (paramBytesMissing > 0) {
                  uint8_t paramPartsBuffer[paramBytesMissing];
                  for (int j = 0; j < paramBytesMissing; j++) {
                    unsigned long waitStart = micros();
                    while (!Serial.available()) {
                      // Wait for a byte.
                      if ((micros() - waitStart) > 500000) {  // Give the Android app 0.5 second max before giving up on the command
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
                // 8 chars for tx, 8 chars for rx, 2 chars for tx tone, 2 chars for rx tone, 1 char for squelch, 1 for bandwidth W/N (20 bytes total for params)
                float freqTxFloat = paramsStr.substring(0, 8).toFloat();
                float freqRxFloat = paramsStr.substring(8, 16).toFloat();
                int txToneInt     = paramsStr.substring(16, 18).toInt();
                int rxToneInt     = paramsStr.substring(18, 20).toInt();
                int squelchInt    = paramsStr.substring(20, 21).toInt();
                String bandwidth  = paramsStr.substring(21, 22);

                tuneTo(freqTxFloat, freqRxFloat, txToneInt, rxToneInt, squelchInt, bandwidth);
                break;
              }

            case COMMAND_FILTERS:
              {
                int paramBytesMissing = 3;  // e.g. 000, in order of emphasis, highpass, lowpass
                String paramsStr      = "";
                if (paramBytesMissing > 0) {
                  uint8_t paramPartsBuffer[paramBytesMissing];
                  for (int j = 0; j < paramBytesMissing; j++) {
                    unsigned long waitStart = micros();
                    while (!Serial.available()) {
                      // Wait for a byte.
                      if ((micros() - waitStart) > 500000) {  // Give the Android app 0.5 second max before giving up on the command
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
                bool lowpass  = (paramsStr.charAt(2) == '1');

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
        String rssiResponse = Serial2.readString();  // Should be like "RSSI=X\n\r", where X is 1-3 digits, 0-255

        if (rssiResponse.length() > 7) {
          String rssiStr = rssiResponse.substring(5);
          int rssiInt    = rssiStr.toInt();

          if (rssiInt >= 0 && rssiInt <= 255) {
            byte params[1] = {(uint8_t)rssiInt};
            sendCmdToAndroid(COMMAND_SMETER_REPORT, params, /* paramsLen */ 1);
          }
          _LOGD("%s, rssiInt=%d", rssiResponse.c_str(), rssiInt);
        }

        // It doesn't matter if we successfully got the S-meter reading, we only want to check at most once every SMETER_REPORT_INTERVAL_MS
        lastSMeterReport = millis();
      
      }


      size_t bytesRead = 0;
      static uint16_t buffer16[I2S_READ_LEN];
      static uint8_t buffer8[I2S_READ_LEN];
      ESP_ERROR_CHECK(i2s_read(I2S_NUM_0, &buffer16, sizeof(buffer16), &bytesRead, 100));
      size_t samplesRead = bytesRead / 2;

      bool squelched = (digitalRead(SQ_PIN) == HIGH);

      // Check for squelch status change
      if (squelched != lastSquelched) {
        if (squelched) {
          // Start fade-out
          fadeCounter   = FADE_SAMPLES;
          fadeDirection = -1;
        } else {
          // Start fade-in
          fadeCounter   = FADE_SAMPLES;
          fadeDirection = 1;
        }
      }
      lastSquelched = squelched;

      int attenuationIncrement = ATTENUATION_MAX / FADE_SAMPLES;

      for (int i = 0; i < samplesRead; i++) {

        // Adjust attenuation during fade
        if (fadeCounter > 0) {
          fadeCounter--;
          attenuation += fadeDirection * attenuationIncrement;
          attenuation = max(0, min(attenuation, ATTENUATION_MAX));
        } else {
          attenuation   = squelched ? 0 : ATTENUATION_MAX;
          fadeDirection = 0;
        }

        // Apply attenuation to the sample
        int16_t sample = (int32_t)remove_dc(((2048 - (buffer16[i] & 0xfff)) << 4)) * attenuation >> 8;
        buffer8[i]     = (sample >> 8);  // Signed
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
      static uint8_t tempBuffer[TX_TEMP_AUDIO_BUFFER_SIZE];
      int bytesAvailable = Serial.available();
      if (bytesAvailable > 0) {
        bytesRead = Serial.readBytes(tempBuffer, bytesAvailable);

        // Pre-cache transmit audio to ensure precise timing (required for any data encoding to work, such as BFSK).
        if (!isTxCacheSatisfied) {
          if (txCachedAudioBytes + bytesRead >= TX_CACHED_AUDIO_BUFFER_SIZE) {
            isTxCacheSatisfied = true;
            processTxAudio(txCachedAudioBuffer, txCachedAudioBytes);  // Process cached bytes
          } else {
            memcpy(txCachedAudioBuffer + txCachedAudioBytes, tempBuffer, bytesRead);  // Store bytes to cache
            txCachedAudioBytes += bytesRead;
          }
        }

        if (isTxCacheSatisfied) {  // Note that it may have JUST been satisfied above, in which case we processed the cache, and will now process tempBuffer.
          processTxAudio(tempBuffer, bytesRead);
        }

        for (int i = 0; i < bytesRead && i < TX_TEMP_AUDIO_BUFFER_SIZE; i++) {
          // If we've seen the entire delimiter...
          if (matchedDelimiterTokens == DELIMITER_LENGTH) {
            // Process next byte as a command.
            uint8_t command        = tempBuffer[i];
            matchedDelimiterTokens = 0;
            switch (command) {
              case COMMAND_STOP:
                {
                  delay(MS_WAIT_BEFORE_PTT_UP);  // Wait just a moment so final tx audio data in DMA buffer can be transmitted.
                  setMode(MODE_STOPPED);
                  esp_task_wdt_reset();
                  return;
                }
                break;
              case COMMAND_PTT_UP:
                {
                  delay(MS_WAIT_BEFORE_PTT_UP);  // Wait just a moment so final tx audio data in DMA buffer can be transmitted.
                  setMode(MODE_RX);
                  esp_task_wdt_reset();
                  return;
                }
                break;
            }
          } else {
            if (tempBuffer[i] == COMMAND_DELIMITER[matchedDelimiterTokens]) {  // This byte may be part of the delimiter
              matchedDelimiterTokens++;
            } else {  // This byte is not consistent with the command delimiter, reset counter
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
 * Send a command with params
 * Format: [DELIMITER(8 bytes)] [CMD(1 byte)] [paramLen(1 byte)] [param data(N bytes)]
 */
void sendCmdToAndroid(byte cmdByte, const byte *params, size_t paramsLen) {
  // Safety check: limit paramsLen to 255 for 1-byte length
  if (paramsLen > 255) {
    paramsLen = 255;  // or handle differently (split, or error, etc.)
  }

  // 1. Leading delimiter
  Serial.write(COMMAND_DELIMITER, DELIMITER_LENGTH);

  // 2. Command byte
  Serial.write(&cmdByte, 1);

  // 3. Parameter length
  uint8_t len = paramsLen;
  Serial.write(&len, 1);

  if (paramsLen > 0) {
    // 4. Parameter bytes
    Serial.write(params, paramsLen);
  }
}

void tuneTo(float freqTx, float freqRx, int txTone, int rxTone, int squelch, String bandwidth) {
  // Tell radio module to tune
  int result = 0;
  while (!result) {
    if (bandwidth.equals("W")) {
      result = dra->group(DRA818_25K, freqTx, freqRx, txTone, squelch, rxTone);
    } else if (bandwidth.equals("N")) {
      result = dra->group(DRA818_12K5, freqTx, freqRx, txTone, squelch, rxTone);
    }
  }
  // Serial.println("tuneTo: " + String(result));
}

void setMode(int newMode) {
  mode = newMode;
  switch (mode) {
    case MODE_STOPPED:
      _LOGI("MODE_STOPPED");
      digitalWrite(LED_PIN, LOW);
      digitalWrite(PTT_PIN, HIGH);
      break;
    case MODE_RX:
      _LOGI("MODE_RX");
      digitalWrite(LED_PIN, LOW);
      digitalWrite(PTT_PIN, HIGH);
      initI2SRx();
      matchedDelimiterTokensRx = 0;
      break;
    case MODE_TX:
      _LOGI("MODE_TX");
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
    buffer16[i * 2 + 1] = tempBuffer[i];  // Move 8-bit audio into top 8 bits of 16-bit byte that I2S expects.
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

void reportPhysPttState() {
  sendCmdToAndroid(isPhysPttDown ? COMMAND_PHYS_PTT_DOWN : COMMAND_PHYS_PTT_UP, NULL, 0);
}

hw_ver_t get_hardware_version() {
  pinMode(HW_VER_PIN_0, INPUT);
  pinMode(HW_VER_PIN_1, INPUT);

  hw_ver_t ver = 0x00;
  ver |= (digitalRead(HW_VER_PIN_0) == HIGH ? 0x0F : 0x00);
  ver |= (digitalRead(HW_VER_PIN_1) == HIGH ? 0xF0 : 0x00);

  // In the future, we're replace these with analogRead()s and
  // use values between 0x0 and 0xF. For now, just binary.

  return ver;
}
