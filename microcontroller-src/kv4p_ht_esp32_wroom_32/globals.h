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
#define RXD2_PIN      16
#define TXD2_PIN      17
#define DAC_PIN       25  // This constant not used, just here for reference. GPIO 25 is implied by use of I2S_DAC_CHANNEL_RIGHT_EN.
#define ADC_PIN       34  // If this is changed, you may need to manually edit adc1_config_channel_atten() below too.
#define PTT_PIN       18  // Keys up the radio module
#define PD_PIN        19
#define SQ_PIN_HW1    32  // 
#define SQ_PIN_HW2     4  // Squelch pin. In v2.0c, this is GPIO 4. In v1.x and v2.0d, this is GPIO 32.
#define PHYS_PTT_PIN1 5   // Optional. Buttons may be attached to either or both of this and next pin. They behave the same.
#define PHYS_PTT_PIN2 33  // Optional. See above.

#define ADC_BIAS_VOLTAGE     1.75
#define ADC_ATTENUATION      ADC_ATTEN_DB_12
#define ADC_ATTENUATION_v20C ADC_ATTEN_DB_0  // v2.0c has a lower input ADC range

// Hardware version detection
#define HW_VER_PIN_0  39  // 0xF0
#define HW_VER_PIN_1  36  // 0x0F
// LOW = 0, HIGH = F, 1 <= analog values <= E

//  Hardware Version Summary:
//  +-------------+------------+-----------------+-------------------------------------------------------------------+
//  | Version     | Squelch Pin| ADC Attenuation | Notes                                                             |
//  +-------------+------------+-----------------+-------------------------------------------------------------------+
//  | HW_VER_V1   | GPIO32     | 12dB            | Original version, full feature set                                |
//  | HW_VER_V2_0C| GPIO4      | 0dB             | Switched to GPIO4 for squelch and lower ADC input range (0–1.1V). |
//  | HW_VER_V2_0D| GPIO4      | 12dB            | Same squelch pin as V2.0C; ADC range restored to normal (~3.3V).  |
//  +-------------+------------+-----------------+-------------------------------------------------------------------+
#define HW_VER_V1     (0x00)
#define HW_VER_V2_0C  (0xFF)
#define HW_VER_V2_0D  (0xF0)
// #define HW_VER_?? (0x0F)  // Unused

typedef uint8_t hw_ver_t;  // This allows us to do a lot more in the future if we want.
hw_ver_t hardware_version = HW_VER_V1;  // lowest common denominator

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

// Squelch pin
uint8_t sqPin = SQ_PIN_HW1;