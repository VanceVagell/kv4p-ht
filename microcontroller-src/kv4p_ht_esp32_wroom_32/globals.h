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
#pragma once

#include <Arduino.h>
#include <driver/adc.h>

// Audio sampling rate, must match what Android app expects (and sends).
#define AUDIO_SAMPLE_RATE 48000

// Maximum length of the frame
#define PROTO_MTU 2048

// Offset to make up for fact that sampling is slightly slower than requested, and we don't want underruns.
// But if this is set too high, then we get audio skips instead of underruns. So there's a sweet spot.
#define SAMPLING_RATE_OFFSET 0

// I2S audio sampling stuff
#define I2S_ADC_UNIT    ADC_UNIT_1
#define I2S_ADC_CHANNEL ADC1_CHANNEL_6

// Connections to radio module
#define DEFAULT_RXD2_PIN      16
#define DEFAULT_TXD2_PIN      17
#define DEFAULT_DAC_PIN       25  // This constant not used, just here for reference. GPIO 25 is implied by use of I2S_DAC_CHANNEL_RIGHT_EN.
#define DEFAULT_ADC_PIN       34  // If this is changed, you may need to manually edit adc1_config_channel_atten() below too.
#define DEFAULT_PTT_PIN       18  // Keys up the radio module
#define DEFAULT_PD_PIN        19
#define DEFAULT_SQ_PIN        32  // 
#define DEFAULT_PHYS_PTT_PIN1 5   // Optional. Buttons may be attached to either or both of this and next pin. They behave the same.
#define DEFAULT_PHYS_PTT_PIN2 33  // Optional. See above.
#define DEFAULT_LED_PIN        2  // Built in LED
#define DEFAULT_PIXELS_PIN    13  // NeoPixel data pin

#define DEFAULT_ADC_BIAS_VOLTAGE     1.75
#define DEFAULT_ADC_ATTENUATION      ADC_ATTEN_DB_12

// Mode of the app, which is essentially a state machine
enum Mode {
  MODE_TX,
  MODE_RX,
  MODE_STOPPED
};
Mode mode = MODE_STOPPED;

// Current SQ status
bool squelched = false;

// Forward declarations
void setMode(Mode newMode);

struct PinConfig {
  int8_t sqPin;
  int8_t rxd2Pin;
  int8_t txd2Pin;
  int8_t dacPin;
  int8_t adcPin;
  int8_t pttPin;
  int8_t pdPin;
  int8_t pttPhys1;
  int8_t pttPhys2;
  int8_t ledPin;
  int8_t pixelsPin;
};

struct HWConfig {
  PinConfig pins;
  float adcBias;
  adc_atten_t adcAttenuation;
};

HWConfig hw = {
  .pins = {
    .sqPin = DEFAULT_SQ_PIN,
    .rxd2Pin = DEFAULT_RXD2_PIN,
    .txd2Pin = DEFAULT_TXD2_PIN,
    .dacPin = DEFAULT_DAC_PIN,
    .adcPin = DEFAULT_ADC_PIN,
    .pttPin = DEFAULT_PTT_PIN,
    .pdPin = DEFAULT_PD_PIN,
    .pttPhys1 = DEFAULT_PHYS_PTT_PIN1,
    .pttPhys2 = DEFAULT_PHYS_PTT_PIN2,
    .ledPin = DEFAULT_LED_PIN,
    .pixelsPin = DEFAULT_PIXELS_PIN
  },
  .adcBias = DEFAULT_ADC_BIAS_VOLTAGE,
  .adcAttenuation = DEFAULT_ADC_ATTENUATION
};