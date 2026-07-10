/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.

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
#include <driver/adc.h>

// Pin defaults and hardware-revision handling, adapted from the upstream
// kv4p_ht_esp32_wroom_32 firmware (globals.h + board.h) so every PCB revision
// (v1.x straps, v2.0c/d straps, NVS "hwconfig" blob) keeps working unchanged.

enum RfModuleType : uint8_t {
  RF_SA818_VHF = 0,
  RF_SA818_UHF = 1,
};

#define I2S_ADC_UNIT    ADC_UNIT_1
#define I2S_ADC_CHANNEL ADC1_CHANNEL_6  // GPIO34 — ADC1, safe alongside WiFi

#define DEFAULT_PIN_RF_RXD    16
#define DEFAULT_PIN_RF_TXD    17
#define DEFAULT_PIN_AUDIO_OUT 25
#define DEFAULT_PIN_AUDIO_IN  34
#define DEFAULT_PIN_PTT       18
#define DEFAULT_PIN_PD        19
#define DEFAULT_PIN_SQ        32
#define DEFAULT_PIN_PHYS_PTT1 5
#define DEFAULT_PIN_PHYS_PTT2 33
#define DEFAULT_PIN_LED        2
#define DEFAULT_PIN_PIXELS    13
#define DEFAULT_PIN_HL        -1

#define DEFAULT_ADC_BIAS_VOLTAGE 1.75
#define DEFAULT_ADC_ATTENUATION  ADC_ATTEN_DB_12
#define DEFAULT_RF_MODULE_TYPE   RF_SA818_VHF
#define DEFAULT_HW_VOLUME        8

#define STRINGIFY(x) #x
#define TOSTRING(x) STRINGIFY(x)

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

struct HWConfig {
  PinConfig pins;
  float adcBias;
  adc_atten_t adcAttenuation;
  uint8_t volume;
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
  .adcBias = DEFAULT_ADC_BIAS_VOLTAGE,
  .adcAttenuation = DEFAULT_ADC_ATTENUATION,
  .volume = DEFAULT_HW_VOLUME,
  .rfModuleType = DEFAULT_RF_MODULE_TYPE,
};

// Legacy hardware version detection via strap pins
#define HW_VER_PIN_0 39
#define HW_VER_PIN_1 36
#define HW_VER_V1    (0x00)
#define HW_VER_V2_0C (0xFF)
#define HW_VER_V2_0D (0xF0)

Preferences hwPrefs;

bool isHardwareConfigExists() {
  hwPrefs.begin("hwconfig", true);
  bool exists = hwPrefs.isKey("HWCONFIG");
  hwPrefs.end();
  return exists;
}

void loadHardwareConfig() {
  hwPrefs.begin("hwconfig", true);
  hw.pins.pinRfModuleRxd = hwPrefs.getChar("PIN_RF_RXD", DEFAULT_PIN_RF_RXD);
  hw.pins.pinRfModuleTxd = hwPrefs.getChar("PIN_RF_TXD", DEFAULT_PIN_RF_TXD);
  hw.pins.pinAudioOut    = hwPrefs.getChar("PIN_AUDIO_OUT", DEFAULT_PIN_AUDIO_OUT);
  hw.pins.pinAudioIn     = hwPrefs.getChar("PIN_AUDIO_IN", DEFAULT_PIN_AUDIO_IN);
  hw.pins.pinPtt         = hwPrefs.getChar("PIN_PTT", DEFAULT_PIN_PTT);
  hw.pins.pinPd          = hwPrefs.getChar("PIN_PD", DEFAULT_PIN_PD);
  hw.pins.pinSq          = hwPrefs.getChar("PIN_SQ", DEFAULT_PIN_SQ);
  hw.pins.pinPttPhys1    = hwPrefs.getChar("PIN_PHYS_PTT1", DEFAULT_PIN_PHYS_PTT1);
  hw.pins.pinPttPhys2    = hwPrefs.getChar("PIN_PHYS_PTT2", DEFAULT_PIN_PHYS_PTT2);
  hw.pins.pinPixels      = hwPrefs.getChar("PIN_PIXELS", DEFAULT_PIN_PIXELS);
  hw.pins.pinLed         = hwPrefs.getChar("PIN_LED", DEFAULT_PIN_LED);
  hw.pins.pinHl          = hwPrefs.getChar("PIN_HL", DEFAULT_PIN_HL);
  hw.adcAttenuation      = (adc_atten_t)hwPrefs.getChar("ADC_ATTEN", DEFAULT_ADC_ATTENUATION);
  hw.adcBias             = hwPrefs.getString("ADC_BIAS", TOSTRING(DEFAULT_ADC_BIAS_VOLTAGE)).toFloat();
  hw.volume              = hwPrefs.getUChar("VOLUME", DEFAULT_HW_VOLUME);
  hw.rfModuleType        = (RfModuleType)hwPrefs.getUChar("RF_MODULE_TYPE", DEFAULT_RF_MODULE_TYPE);
  hwPrefs.end();
}

uint8_t getHardwareVersion() {
  pinMode(HW_VER_PIN_0, INPUT);
  pinMode(HW_VER_PIN_1, INPUT);
  uint8_t ver = 0x00;
  ver |= (digitalRead(HW_VER_PIN_0) == HIGH ? 0x0F : 0x00);
  ver |= (digitalRead(HW_VER_PIN_1) == HIGH ? 0xF0 : 0x00);
  return ver;
}

// Unlike upstream we never write hwconfig back to NVS here — this firmware only
// reads the board description, it doesn't own it.
void boardSetup() {
  if (isHardwareConfigExists()) {
    loadHardwareConfig();
  } else {
    switch (getHardwareVersion()) {
      case HW_VER_V2_0C:
        hw.pins.pinSq = 4;
        hw.adcAttenuation = ADC_ATTEN_DB_0;
        hw.volume = 6;
        hw.pins.pinHl = 23;
        break;
      case HW_VER_V2_0D:
        hw.pins.pinSq = 4;
        hw.pins.pinHl = 23;
        break;
    }
  }
}

float moduleMinFreqMHz() { return hw.rfModuleType == RF_SA818_UHF ? 400.0f : 134.0f; }
float moduleMaxFreqMHz() { return hw.rfModuleType == RF_SA818_UHF ? 480.0f : 174.0f; }
