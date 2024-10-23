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

#include <algorithm>
#include <DRA818.h>
#include <driver/adc.h>
#include <driver/i2s.h>
#include <esp_task_wdt.h>

#include "Radio.hpp"

const byte FIRMWARE_VER[8] = {'0', '0', '0', '0', '0', '0', '0', '1'}; // Should be 8 characters representing a zero-padded version, like 00000001.
const byte VERSION_PREFIX[7] = {'V', 'E', 'R', 'S', 'I', 'O', 'N'};    // Must match RadioAudioService.VERSION_PREFIX in Android app.

// Delimeter must also match Android app
#define DELIMITER_LENGTH 8
const uint8_t delimiter[DELIMITER_LENGTH] = {0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00};
int matchedDelimiterTokens = 0;

// Mode of the app, which is essentially a state machine
#define MODE_TX 0
#define MODE_RX 1
#define MODE_STOPPED 2
int mode = MODE_STOPPED;

// Audio sampling rate, must match what Android app expects (and sends).
#define AUDIO_SAMPLE_RATE 44100

// Offset to make up for fact that sampling is slightly slower than requested, and we don't want underruns.
// But if this is set too high, then we get audio skips instead of underruns. So there's a sweet spot.
#define SAMPLING_RATE_OFFSET 218

// Buffer for outgoing audio bytes to send to radio module
#define TX_TEMP_AUDIO_BUFFER_SIZE 4096   // Holds data we already got off of USB serial from Android app
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
DRA818 *dra = new DRA818(&Serial2, DRA818_VHF);

// Tx runaway detection stuff
long txStartTime = -1;
#define RUNAWAY_TX_SEC 200

// have we installed an I2S driver at least once?
bool i2sStarted = false;

// I2S audio sampling stuff
#define I2S_READ_LEN 1024
#define I2S_WRITE_LEN 1024
#define I2S_ADC_UNIT ADC_UNIT_1
#define I2S_ADC_CHANNEL ADC1_CHANNEL_6

// Squelch parameters (for graceful fade to silence)
#define FADE_SAMPLES 256 // Must be a power of two
#define ATTENUATION_MAX 256
int fadeCounter = 0;
int fadeDirection = 0;             // 0: no fade, 1: fade in, -1: fade out
int attenuation = ATTENUATION_MAX; // Full volume
bool lastSquelched = false;

Radio radio;

void setup()
{
  // Communication with Android via USB cable
  Serial.begin(921600);
  Serial.setRxBufferSize(USB_BUFFER_SIZE);
  Serial.setTxBufferSize(USB_BUFFER_SIZE);

  // Configure watch dog timer (WDT), which will reset the system if it gets stuck somehow.
  esp_task_wdt_init(10, true); // Reboot if locked up for a bit
  esp_task_wdt_add(NULL);      // Add the current task to WDT watch

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
  while (result != 1)
  {
    result = dra->handshake(); // Wait for module to start up
  }
  // Serial.println("handshake: " + String(result));
  // tuneTo(146.700, 146.700, 0, 0);
  result = dra->volume(8);
  // Serial.println("volume: " + String(result));
  result = dra->filters(false, false, false);
  // Serial.println("filters: " + String(result));

  // Begin in STOPPED mode
  setMode(MODE_STOPPED);
}

void initI2SRx()
{
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted)
  {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;

  // Initialize ADC
  adc1_config_width(ADC_WIDTH_BIT_12);
  adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_0);

  static const i2s_config_t i2sRxConfig = {
      .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_ADC_BUILT_IN),
      .sample_rate = AUDIO_SAMPLE_RATE + SAMPLING_RATE_OFFSET,
      .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
      .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
      .communication_format = i2s_comm_format_t(I2S_COMM_FORMAT_I2S | I2S_COMM_FORMAT_I2S_MSB),
      .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
      .dma_buf_count = 4,
      .dma_buf_len = I2S_READ_LEN,
      .use_apll = true,
      .tx_desc_auto_clear = false,
      .fixed_mclk = 0};

  ESP_ERROR_CHECK(i2s_driver_install(I2S_NUM_0, &i2sRxConfig, 0, NULL));
  ESP_ERROR_CHECK(i2s_set_adc_mode(I2S_ADC_UNIT, I2S_ADC_CHANNEL));
}

void initI2STx()
{
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted)
  {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;

  static const i2s_config_t i2sTxConfig = {
      .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
      .sample_rate = AUDIO_SAMPLE_RATE,
      .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
      .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
      .intr_alloc_flags = 0,
      .dma_buf_count = 8,
      .dma_buf_len = I2S_WRITE_LEN,
      .use_apll = true};

  i2s_driver_install(I2S_NUM_0, &i2sTxConfig, 0, NULL);
  i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN);
}

void handleStoppedMode();
void handleRxMode();
void handleTxMode();

void handleTuneToCommand();
void handleFiltersCommand();
void handleStopCommand();

void loop()
{
  radio.loop(); // Call the Radio object's loop function
}
