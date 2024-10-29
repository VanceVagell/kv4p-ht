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

#include "I2SOutput.h"
#include "SerialSampleSource.h"

const byte FIRMWARE_VER[8] = {'0', '0', '0', '0', '0', '0', '0', '1'}; // Should be 8 characters representing a zero-padded version, like 00000001.
const byte VERSION_PREFIX[7] = {'V', 'E', 'R', 'S', 'I', 'O', 'N'};    // Must match RadioAudioService.VERSION_PREFIX in Android app.

// Commands defined here must match the Android app
enum class CommandValue : uint8_t
{
  COMMAND_PTT_DOWN = 1,        // start transmitting audio that Android app will send
  COMMAND_PTT_UP = 2,          // stop transmitting audio, go into RX mode
  COMMAND_TUNE_TO = 3,         // change the frequency
  COMMAND_FILTERS = 4,         // toggle filters on/off
  COMMAND_STOP = 5,            // stop everything, just wait for next command
  COMMAND_GET_FIRMWARE_VER = 6 // report FIRMWARE_VER in the format '00000001' for 1 (etc.)
};

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

// i2s pins
i2s_pin_config_t i2sPins = {
    .bck_io_num = GPIO_NUM_27,
    .ws_io_num = GPIO_NUM_14,
    .data_out_num = GPIO_NUM_26,
    .data_in_num = -1};

I2SOutput *output;
SampleSource *sampleSource;
SerialSampleSource<TX_TEMP_AUDIO_BUFFER_SIZE> *serialSampleSource;

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

  /////////////////////////////// Setup Radio Module
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

  /////////////////////////////// Setup Tx Module
  serialSampleSource = new SerialSampleSource<TX_TEMP_AUDIO_BUFFER_SIZE>(AUDIO_SAMPLE_RATE);
  sampleSource = static_cast<SampleSource *>(serialSampleSource);
  output = new I2SOutput();

  /////////////////////////////// Set initial state
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

enum class MsgType : uint8_t
{
  DATA = 0xF0,
  CMD = 0x0F,
  DEFAULT_CMD = 0
};

void loop()
{
  try
  {
    //////////// Handle an message if present
    MsgType incomingMessage = MsgType::DEFAULT_CMD;
    uint8_t headerBuffer[1];
    if (Serial.available())
    {
      Serial.readBytes(headerBuffer, 1);
      incomingMessage = static_cast<MsgType>(headerBuffer[0]);
    }
    switch (incomingMessage)
    {
    case MsgType::DATA:
    {
      if (MODE_TX == mode)
      {
        uint8_t sizeBuffer[2];
        while (Serial.available() < 2)
        {
          // Wait for the amount of data we want
        };
        Serial.readBytes(sizeBuffer, 2);
        size_t numberOfIncomingAudioBytes = (sizeBuffer[0] << 8) | (sizeBuffer[1] & 0xFF);

        while (Serial.available() < numberOfIncomingAudioBytes)
        {
          // Wait for the amount of data we want
        };
        serialSampleSource->readFromSerial(numberOfIncomingAudioBytes);
      }
      else
      {
        throw 1;
      }
    }
    break;
    case MsgType::CMD:
    {
      uint8_t commandBuffer[1];
      Serial.readBytes(commandBuffer, 1);
      switch (static_cast<CommandValue>(commandBuffer[0]))
      {
      case CommandValue::COMMAND_PTT_DOWN:
      {
        output->start(I2S_NUM_0, i2sPins, sampleSource);
        setMode(MODE_TX);
        esp_task_wdt_reset();
      }
      break;
      case CommandValue::COMMAND_PTT_UP:
      {
        output->stop(I2S_NUM_0);
        setMode(MODE_RX);
        esp_task_wdt_reset();
      }
      break;
      case CommandValue::COMMAND_TUNE_TO:
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
        if (paramBytesMissing > 0)
        {
          uint8_t paramPartsBuffer[paramBytesMissing];
          for (int j = 0; j < paramBytesMissing; j++)
          {
            unsigned long waitStart = micros();
            while (!Serial.available())
            {
              // Wait for a byte.
              if ((micros() - waitStart) > 500000)
              { // Give the Android app 0.5 second max before giving up on the command
                esp_task_wdt_reset();
                return;
              }
            }
            paramPartsBuffer[j] = Serial.read();
          }
          paramsStr += String((char *)paramPartsBuffer);
          paramBytesMissing--;
        }
        float freqTxFloat = paramsStr.substring(0, 7).toFloat();
        float freqRxFloat = paramsStr.substring(7, 14).toFloat();
        int toneInt = paramsStr.substring(14, 16).toInt();
        int squelchInt = paramsStr.substring(16, 17).toInt();

        // Serial.println("PARAMS: " + paramsStr.substring(0, 16) + " freqTxFloat: " + String(freqTxFloat) + " freqRxFloat: " + String(freqRxFloat) + " toneInt: " + String(toneInt));

        tuneTo(freqTxFloat, freqRxFloat, toneInt, squelchInt);
      }
      break;
      case CommandValue::COMMAND_FILTERS:
      {
        int paramBytesMissing = 3; // e.g. 000, in order of emphasis, highpass, lowpass
        String paramsStr = "";
        if (paramBytesMissing > 0)
        {
          uint8_t paramPartsBuffer[paramBytesMissing];
          for (int j = 0; j < paramBytesMissing; j++)
          {
            unsigned long waitStart = micros();
            while (!Serial.available())
            {
              // Wait for a byte.
              if ((micros() - waitStart) > 500000)
              { // Give the Android app 0.5 second max before giving up on the command
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
      case CommandValue::COMMAND_STOP:
      {
        Serial.flush();
      }
      break;
      case CommandValue::COMMAND_GET_FIRMWARE_VER:
      {
        Serial.write(VERSION_PREFIX, sizeof(VERSION_PREFIX));
        Serial.write(FIRMWARE_VER, sizeof(FIRMWARE_VER));
      }
      break;
      default:
        break;
      }
    }
    break;
    case MsgType::DEFAULT_CMD:
    {
      // If no command was received, do the thing
      switch (mode)
      {
      case MODE_STOPPED:
        break;
      case MODE_RX:
      {
        size_t bytesRead = 0;
        uint8_t buffer32[I2S_READ_LEN * 4] = {0};
        ESP_ERROR_CHECK(i2s_read(I2S_NUM_0, &buffer32, sizeof(buffer32), &bytesRead, 100));
        size_t samplesRead = bytesRead / 4;

        byte buffer8[I2S_READ_LEN] = {0};
        bool squelched = (digitalRead(SQ_PIN) == HIGH);

        // Check for squelch status change
        if (squelched != lastSquelched)
        {
          if (squelched)
          {
            // Start fade-out
            fadeCounter = FADE_SAMPLES;
            fadeDirection = -1;
          }
          else
          {
            // Start fade-in
            fadeCounter = FADE_SAMPLES;
            fadeDirection = 1;
          }
        }
        lastSquelched = squelched;

        int attenuationIncrement = ATTENUATION_MAX / FADE_SAMPLES;

        for (int i = 0; i < samplesRead; i++)
        {
          uint8_t sampleValue;

          // Extract 8-bit sample from 32-bit buffer
          sampleValue = buffer32[i * 4 + 3] << 4;
          sampleValue |= buffer32[i * 4 + 2] >> 4;

          // Adjust attenuation during fade
          if (fadeCounter > 0)
          {
            fadeCounter--;
            attenuation += fadeDirection * attenuationIncrement;
            attenuation = max(0, min(attenuation, ATTENUATION_MAX));
          }
          else
          {
            attenuation = squelched ? 0 : ATTENUATION_MAX;
            fadeDirection = 0;
          }

          // Apply attenuation to the sample
          int adjustedSample = (((int)sampleValue - 128) * attenuation) >> 8;
          adjustedSample += 128;
          buffer8[i] = (uint8_t)adjustedSample;
        }

        Serial.write(buffer8, samplesRead);
      }
      break;
      case MODE_TX:
        break;
      default:
        // If we get here.. bad things happened
        break;
      }
    }
    break;
    default:
      // An invalid command was received
      Serial.flush();
    }

    // Regularly reset the WDT timer to prevent the device from rebooting (prove we're not locked up).
    esp_task_wdt_reset();
  }
  catch (int e)
  {
    // Disregard, we don't want to crash. Just pick up at next loop().)
    // Serial.println("Exception in loop(), skipping cycle.");
  }
}

void tuneTo(float freqTx, float freqRx, int tone, int squelch)
{
  int result = dra->group(DRA818_25K, freqTx, freqRx, tone, squelch, 0);
  // Serial.println("tuneTo: " + String(result));
}

void setMode(int newMode)
{
  mode = newMode;
  switch (mode)
  {
  case MODE_STOPPED:
    digitalWrite(LED_PIN, LOW);
    digitalWrite(PTT_PIN, HIGH);
    break;
  case MODE_RX:
    digitalWrite(LED_PIN, LOW);
    digitalWrite(PTT_PIN, HIGH);
    initI2SRx();
    break;
  case MODE_TX:
    txStartTime = micros();
    digitalWrite(LED_PIN, HIGH);
    digitalWrite(PTT_PIN, LOW);
    break;
  }
}
