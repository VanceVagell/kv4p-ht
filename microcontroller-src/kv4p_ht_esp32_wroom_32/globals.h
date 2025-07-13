/*
KV4P-HT (see http://kv4p.com)
Copyright (C) 2025 Vance Vagell

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

// RF module types
enum RfModuleType {
  RF_SA818_VHF = 0,
  RF_SA818_UHF = 1,
};

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
#define DEFAULT_PIN_RF_RXD    16
#define DEFAULT_PIN_RF_TXD    17
#define DEFAULT_PIN_AUDIO_OUT 25  // This constant not used, just here for reference. GPIO 25 is implied by use of I2S_DAC_CHANNEL_RIGHT_EN.
#define DEFAULT_PIN_AUDIO_IN  34  // If this is changed, you may need to manually edit adc1_config_channel_atten() below too.
#define DEFAULT_PIN_PTT       18  // Keys up the radio module
#define DEFAULT_PIN_PD        19
#define DEFAULT_PIN_SQ        32  //
#define DEFAULT_PIN_PHYS_PTT1 5   // Optional. Buttons may be attached to either or both of this and next pin. They behave the same.
#define DEFAULT_PIN_PHYS_PTT2 33  // Optional. See above.
#define DEFAULT_PIN_LED        2  // Built in LED
#define DEFAULT_PIN_PIXELS    13  // NeoPixel data pin
#define DEFAULT_PIN_HL        -1  // High/Low pin for the radio module. -1 means not used.
#define DEFAULT_VOLUME         8  // Default SA8x8 module audio volume

#define DEFAULT_ADC_BIAS_VOLTAGE     1.75
#define DEFAULT_ADC_ATTENUATION      ADC_ATTEN_DB_12
#define DEFAULT_RF_MODULE_TYPE       RF_SA818_VHF
#define DEFAULT_VOLUME               8
#define DEFAULT_STOPPED_COLOR        {0, 32, 0}

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

struct [[gnu::packed]] RGBColor {
  uint8_t red;
  uint8_t green;
  uint8_t blue;
};

struct [[gnu::packed]] PinConfig {
  int8_t pinSq;
  int8_t pinRfModuleRxd;
  int8_t pinRfModuleTxd;
  int8_t pinAudioOut;
  int8_t pinAudioIn;
  int8_t pinPtt;
  int8_t pinPd;
  int8_t pinPttPhys1;
  int8_t pinPttPhys2;
  int8_t pinLed;
  int8_t pinPixels;
  int8_t pinHl;
};

struct [[gnu::packed]] FeatureFlags {
  bool hasHL: 1; // High/Low pin
  bool hasPhysPTT: 1; // PTT pin
};

struct [[gnu::packed]] HWConfig {
  PinConfig pins;
  FeatureFlags features;
  float adcBias;
  adc_atten_t adcAttenuation;
  uint8_t volume;
  RGBColor stoppedColor;
  RfModuleType rfModuleType;
};

HWConfig hw = {
  .pins = {
    .pinSq = DEFAULT_PIN_SQ,
    .pinRfModuleRxd = DEFAULT_PIN_RF_RXD,
    .pinRfModuleTxd = DEFAULT_PIN_RF_TXD,
    .pinAudioOut = DEFAULT_PIN_AUDIO_OUT,
    .pinAudioIn = DEFAULT_PIN_AUDIO_IN,
    .pinPtt = DEFAULT_PIN_PTT,
    .pinPd = DEFAULT_PIN_PD,
    .pinPttPhys1 = DEFAULT_PIN_PHYS_PTT1,
    .pinPttPhys2 = DEFAULT_PIN_PHYS_PTT2,
    .pinLed = DEFAULT_PIN_LED,
    .pinPixels = DEFAULT_PIN_PIXELS,
    .pinHl = DEFAULT_PIN_HL,
  },
  .features = {
    .hasHL = (DEFAULT_PIN_HL != -1),
    .hasPhysPTT = (DEFAULT_PIN_PHYS_PTT1 != -1 || DEFAULT_PIN_PHYS_PTT2 != -1)
  },
  .adcBias = DEFAULT_ADC_BIAS_VOLTAGE,
  .adcAttenuation = DEFAULT_ADC_ATTENUATION,
  .volume = DEFAULT_VOLUME,
  .stoppedColor = DEFAULT_STOPPED_COLOR,
  .rfModuleType = DEFAULT_RF_MODULE_TYPE
};