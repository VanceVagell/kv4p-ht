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
#include <Preferences.h>
#include "globals.h"
#include "debug.h"
#include "led.h"

// Hardware version detection
#define HW_VER_PIN_0  39  // 0xF0
#define HW_VER_PIN_1  36  // 0x0F

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

Preferences prefs;

bool isHardwareConfigExists() {
  prefs.begin("hwconfig", true); // read-only
  bool exists = prefs.isKey("HWCONFIG");
  prefs.end();
  return exists;
} 

void loadHardwareConfig() {
  prefs.begin("hwconfig", true); // read-only
  hw.pins.rxd2Pin   = prefs.getChar("RXD2_PIN",     DEFAULT_RXD2_PIN);
  hw.pins.txd2Pin   = prefs.getChar("TXD2_PIN",     DEFAULT_TXD2_PIN);
  hw.pins.dacPin    = prefs.getChar("DAC_PIN",      DEFAULT_DAC_PIN);
  hw.pins.adcPin    = prefs.getChar("ADC_PIN",      DEFAULT_ADC_PIN);
  hw.pins.pttPin    = prefs.getChar("PTT_PIN",      DEFAULT_PTT_PIN);
  hw.pins.pdPin     = prefs.getChar("PD_PIN",       DEFAULT_PD_PIN);
  hw.pins.sqPin     = prefs.getChar("SQ_PIN",       DEFAULT_SQ_PIN);
  hw.pins.pttPhys1  = prefs.getChar("PHYS_PTT1",    DEFAULT_PHYS_PTT_PIN1);
  hw.pins.pttPhys2  = prefs.getChar("PHYS_PTT2",    DEFAULT_PHYS_PTT_PIN2);
  hw.pins.pixelsPin = prefs.getChar("PIXELS_PIN",   DEFAULT_PIXELS_PIN);
  hw.pins.ledPin    = prefs.getChar("LED_PIN",      DEFAULT_LED_PIN);
  hw.adcAttenuation = (adc_atten_t) prefs.getChar("ADC_ATTEN", DEFAULT_ADC_ATTENUATION);
  hw.adcBias        = prefs.getFloat("ADC_BIAS",    DEFAULT_ADC_BIAS_VOLTAGE);
  prefs.end();
}

void saveHardwareConfig() {
  prefs.begin("hwconfig", false); // read-write mode
  prefs.putBool("HWCONFIG",     true);
  prefs.putChar("RXD2_PIN",     hw.pins.rxd2Pin);
  prefs.putChar("TXD2_PIN",     hw.pins.txd2Pin);
  prefs.putChar("DAC_PIN",      hw.pins.dacPin);
  prefs.putChar("ADC_PIN",      hw.pins.adcPin);
  prefs.putChar("PTT_PIN",      hw.pins.pttPin);
  prefs.putChar("PD_PIN",       hw.pins.pdPin);
  prefs.putChar("SQ_PIN",       hw.pins.sqPin);
  prefs.putChar("PHYS_PTT1",    hw.pins.pttPhys1);
  prefs.putChar("PHYS_PTT2",    hw.pins.pttPhys2);
  prefs.putChar("PIXELS_PIN",   hw.pins.pixelsPin);
  prefs.putChar("LED_PIN",      hw.pins.ledPin);
  prefs.putChar("ADC_ATTEN",    hw.adcAttenuation);
  prefs.putFloat("ADC_BIAS",    hw.adcBias);
  prefs.end();
}

hw_ver_t get_hardware_version() {
  pinMode(HW_VER_PIN_0, INPUT);
  pinMode(HW_VER_PIN_1, INPUT);
  hw_ver_t ver = 0x00;
  ver |= (digitalRead(HW_VER_PIN_0) == HIGH ? 0x0F : 0x00);
  ver |= (digitalRead(HW_VER_PIN_1) == HIGH ? 0xF0 : 0x00);
  return ver;
}

void inline boardSetup() {
  if (isHardwareConfigExists()) {
    loadHardwareConfig();
  } else {
    // Fallback to "old" way
    switch (get_hardware_version()) {
      case HW_VER_V2_0C:
      hw.stoppedColor = {32, 0, 0};
        hw.pins.sqPin = 4;
        hw.adcAttenuation = ADC_ATTEN_DB_0;
        hw.volume = 6;
        break;
      case HW_VER_V2_0D:
        hw.stoppedColor = {0, 0, 32};
        hw.pins.sqPin = 4;
        break;
    }
    saveHardwareConfig();
  }
}