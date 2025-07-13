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
#include <Preferences.h>
#include "globals.h"
#include "debug.h"

// Legacy Hardware version detection
#define HW_VER_PIN_0  39  // 0xF0
#define HW_VER_PIN_1  36  // 0x0F

//  Hardware Version Summary (Legacy):
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
#define STRINGIFY(x) #x
#define TOSTRING(x) STRINGIFY(x)

typedef uint8_t hw_ver_t;  // This allows us to do a lot more in the future if we want.

Preferences prefs;

bool isHardwareConfigExists() {
  prefs.begin("hwconfig", true); // read-only
  bool exists = prefs.isKey("HWCONFIG");
  prefs.end();
  return exists;
} 

void loadHardwareConfig() {
  prefs.begin("hwconfig", true); // read-only
  hw.pins.pinRfModuleRxd   = prefs.getChar("PIN_RF_RXD",   DEFAULT_PIN_RF_RXD);
  hw.pins.pinRfModuleTxd   = prefs.getChar("PIN_RF_TXD",   DEFAULT_PIN_RF_TXD);
  hw.pins.pinAudioOut      = prefs.getChar("PIN_AUDIO_OUT",DEFAULT_PIN_AUDIO_OUT);
  hw.pins.pinAudioIn           = prefs.getChar("PIN_AUDIO_IN", DEFAULT_PIN_AUDIO_IN);
  hw.pins.pinPtt           = prefs.getChar("PIN_PTT",      DEFAULT_PIN_PTT);
  hw.pins.pinPd            = prefs.getChar("PIN_PD",       DEFAULT_PIN_PD);
  hw.pins.pinSq            = prefs.getChar("PIN_SQ",       DEFAULT_PIN_SQ);
  hw.pins.pinPttPhys1      = prefs.getChar("PIN_PHYS_PTT1",DEFAULT_PIN_PHYS_PTT1);
  hw.pins.pinPttPhys2      = prefs.getChar("PIN_PHYS_PTT2",DEFAULT_PIN_PHYS_PTT2);
  hw.pins.pinPixels        = prefs.getChar("PIN_PIXELS",   DEFAULT_PIN_PIXELS);
  hw.pins.pinLed           = prefs.getChar("PIN_LED",      DEFAULT_PIN_LED);
  hw.pins.pinHl            = prefs.getChar("PIN_HL",       DEFAULT_PIN_HL);
  hw.adcAttenuation        = (adc_atten_t) prefs.getChar("ADC_ATTEN",DEFAULT_ADC_ATTENUATION);
  hw.adcBias               = prefs.getString("ADC_BIAS", TOSTRING(DEFAULT_ADC_BIAS_VOLTAGE)).toFloat();
  prefs.getBytes("STOPPED_COLOR", &hw.stoppedColor, sizeof(RGBColor));
  hw.volume                = prefs.getUChar("VOLUME",       DEFAULT_VOLUME);
  hw.rfModuleType          = (RfModuleType) prefs.getUChar("RF_MODULE_TYPE", DEFAULT_RF_MODULE_TYPE);
  prefs.end();
}

void saveHardwareConfig() {
  prefs.begin("hwconfig", false); // read-write mode
  prefs.putBool("HWCONFIG",        true);
  prefs.putChar("PIN_RF_RXD",      hw.pins.pinRfModuleRxd);
  prefs.putChar("PIN_RF_TXD",      hw.pins.pinRfModuleTxd);
  prefs.putChar("PIN_AUDIO_OUT",   hw.pins.pinAudioOut);
  prefs.putChar("PIN_AUDIO_IN",    hw.pins.pinAudioIn);
  prefs.putChar("PIN_PTT",         hw.pins.pinPtt);
  prefs.putChar("PIN_PD",          hw.pins.pinPd);
  prefs.putChar("PIN_SQ",          hw.pins.pinSq);
  prefs.putChar("PIN_PHYS_PTT1",   hw.pins.pinPttPhys1);
  prefs.putChar("PIN_PHYS_PTT2",   hw.pins.pinPttPhys2);
  prefs.putChar("PIN_PIXELS",      hw.pins.pinPixels);
  prefs.putChar("PIN_LED",         hw.pins.pinLed);
  prefs.putChar("PIN_HL",          hw.pins.pinHl);
  prefs.putChar("ADC_ATTEN",       hw.adcAttenuation);
  prefs.putString("ADC_BIAS",String(hw.adcBias, 6));
  prefs.putBytes("STOPPED_COLOR",  &hw.stoppedColor, sizeof(RGBColor));
  prefs.putUChar("VOLUME",         hw.volume);
  prefs.putUChar("RF_MODULE_TYPE", hw.rfModuleType);
  prefs.end();
}

hw_ver_t getHardwareVersion() {
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
    // Fallback to legacy detection
    switch (getHardwareVersion()) {
      case HW_VER_V2_0C:
        hw.stoppedColor = {32, 0, 0};
        hw.pins.pinSq = 4;
        hw.adcAttenuation = ADC_ATTEN_DB_0;
        hw.volume = 6;
        hw.pins.pinHl = 23;
        saveHardwareConfig();
        break;
      case HW_VER_V2_0D:
        hw.stoppedColor = {0, 0, 32};
        hw.pins.pinSq = 4;
        hw.pins.pinHl = 23;
        saveHardwareConfig();
        break;
    }
  }
  // Set up the hardware features
  hw.features.hasHL = (hw.pins.pinHl != -1);
  hw.features.hasPhysPTT = (hw.pins.pinPttPhys1 != -1 || hw.pins.pinPttPhys2 != -1);
}