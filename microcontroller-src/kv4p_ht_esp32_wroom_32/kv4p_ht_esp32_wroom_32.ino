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
#include <Preferences.h>
#include <DRA818.h>
#include <esp_task_wdt.h>
#include "globals.h"
#include "debug.h"
#include "led.h"
#include "protocol.h"
#include "rxAudio.h"
#include "txAudio.h"
#include "buttons.h"
#include "utils.h"

const uint16_t FIRMWARE_VER = 14;

const uint32_t RSSI_REPORT_INTERVAL_MS = 100;
const uint16_t USB_BUFFER_SIZE = 1024*2;

DRA818 sa818_vhf(&Serial2, SA818_VHF);
DRA818 sa818_uhf(&Serial2, SA818_UHF);
DRA818 &sa818 = sa818_vhf;

// Were we able to communicate with the radio module during setup()?
const char RADIO_MODULE_NOT_FOUND = 'x';
const char RADIO_MODULE_FOUND     = 'f';
char radioModuleStatus            = RADIO_MODULE_NOT_FOUND;

Debounce squelchDebounce(100);

Preferences prefs;

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

void setMode(Mode newMode) {
  if (mode == newMode) {
    return;
  }
  mode = newMode;
  switch (mode) {
    case MODE_STOPPED:
      _LOGI("MODE_STOPPED");
      digitalWrite(hw.pins.pttPin, HIGH);
      endI2STx();
      endI2SRx();
    break;
    case MODE_RX:
      _LOGI("MODE_RX");
      digitalWrite(hw.pins.pttPin, HIGH);
      squelchDebounce.forceState(true);
      endI2STx();
      initI2SRx();
    break;
    case MODE_TX:
      _LOGI("MODE_TX");
      txStartTime = millis();
      digitalWrite(hw.pins.pttPin, LOW);
      endI2SRx();
      initI2STx();
    break;
  }
}

void setup() {
  loadHardwareConfig();
  //saveHardwareConfig();
  // Communication with Android via USB cable
  Serial.setRxBufferSize(USB_BUFFER_SIZE);
  Serial.setTxBufferSize(USB_BUFFER_SIZE);
  Serial.begin(115200);
  COLOR_HW_VER = {0, 32, 0};
  // Configure watch dog timer (WDT), which will reset the system if it gets stuck somehow.
  esp_task_wdt_init(10, true);  // Reboot if locked up for a bit
  esp_task_wdt_add(NULL);       // Add the current task to WDT watch
  buttonsSetup();
  // Set up radio module defaults
  pinMode(hw.pins.pdPin, OUTPUT);
  digitalWrite(hw.pins.pdPin, HIGH);  // Power on
  pinMode(hw.pins.sqPin, INPUT);
  pinMode(hw.pins.pttPin, OUTPUT);
  digitalWrite(hw.pins.pttPin, HIGH);  // Rx
  // Communication with DRA818V radio module via GPIO pins
  Serial2.begin(9600, SERIAL_8N1, hw.pins.rxd2Pin, hw.pins.txd2Pin);
  Serial2.setTimeout(10);  // Very short so we don't tie up rx audio while reading from radio module (responses are tiny so this is ok)
  //
  debugSetup();
  // Begin in STOPPED mode
  squelched = (digitalRead(hw.pins.sqPin) == HIGH);
  setMode(MODE_STOPPED);
  ledSetup();
  sendHello();
  _LOGI("Setup is finished");
}

void doConfig(Config const &config) {
  if(config.radioType == SA818_UHF) {
    sa818 = sa818_uhf;
  } else {
    sa818 = sa818_vhf;
  }
  int result = -1;
  uint32_t waitStart = millis();
  while (result != 1) {
    result = sa818.handshake();  // Wait for module to start up
    esp_task_wdt_reset();
    if ((millis() - waitStart) > 2000) {  // Give the radio module 2 seconds max before giving up on it
      radioModuleStatus = RADIO_MODULE_NOT_FOUND;
      break;
    }
  }
  if (result == 1) {  // Did we hear back from radio?
    radioModuleStatus = RADIO_MODULE_FOUND;
  }
  result = sa818.filters(false, false, false);
  sendVersion(FIRMWARE_VER, radioModuleStatus, USB_BUFFER_SIZE);
  esp_task_wdt_reset();
}

void handleCommands(RcvCommand command, uint8_t *params, size_t param_len) {
  switch (command) {
    case COMMAND_HOST_CONFIG:
      if (param_len == sizeof(Config)) {
        Config config;
        memcpy(&config, params, sizeof(Config));
        doConfig(config);
        esp_task_wdt_reset();
      }
      break;
    case COMMAND_HOST_FILTERS:
      if (param_len == sizeof(Filters)) {
        Filters filters;
        memcpy(&filters, params, sizeof(Filters));
        while (!sa818.filters((filters.flags & FILTER_PRE), (filters.flags & FILTER_HIGH), (filters.flags & FILTER_LOW)));
        esp_task_wdt_reset();
      }
      break;
    case COMMAND_HOST_GROUP:
      if (param_len == sizeof(Group)) {
        Group group;
        memcpy(&group, params, sizeof(Group));
        while (!sa818.group(group.bw, group.freq_tx, group.freq_rx, group.ctcss_tx, group.squelch, group.ctcss_rx));
        esp_task_wdt_reset();
        if (mode == MODE_STOPPED) {
          setMode(MODE_RX);   
        }
      } 
      break;
    case COMMAND_HOST_STOP:
      setMode(MODE_STOPPED);
      esp_task_wdt_reset();
      break;
    case COMMAND_HOST_PTT_DOWN:
      setMode(MODE_TX);
      esp_task_wdt_reset();
      break;
    case COMMAND_HOST_PTT_UP:
      setMode(MODE_RX);
      esp_task_wdt_reset();
      break;
    case COMMAND_HOST_TX_AUDIO:
      if (mode == MODE_TX) {
        processTxAudio(params, param_len);
        esp_task_wdt_reset();
      }
      break;                              
  }
}

void rssiLoop() {
  if (mode == MODE_RX) {
    EVERY_N_MILLISECONDS(RSSI_REPORT_INTERVAL_MS) {
      // TODO fix the dra818 library's implementation of rssi(). Right now it just drops the
      // return value from the module, and just tells us success/fail.
      // int rssi = dra->rssi();
      Serial2.println("RSSI?");
      String rssiResponse = Serial2.readString();
      if (rssiResponse.length() > 7) {
        String rssiStr = rssiResponse.substring(5);
        int rssiInt    = rssiStr.toInt();
        if (rssiInt >= 0 && rssiInt <= 255) {
          sendRssi((uint8_t)rssiInt);
        }
      }
    }
    END_EVERY_N_MILLISECONDS();
  }
}

void loop() {
  squelched = squelchDebounce.debounce((digitalRead(hw.pins.sqPin) == HIGH));
  debugLoop();
  ledLoop();
  buttonsLoop();
  protocolLoop();
  rxAudioLoop();
  txAudioLoop();
  rssiLoop();
}