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

#include <Arduino.h>
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
#include "board.h"

const uint16_t FIRMWARE_VER = 15;

const uint32_t RSSI_REPORT_INTERVAL_MS = 100;
const uint16_t USB_BUFFER_SIZE = 1024*2;

DRA818 sa818_vhf(&Serial2, SA818_VHF);
DRA818 sa818_uhf(&Serial2, SA818_UHF);
DRA818 &sa818 = sa818_vhf;

// Were we able to communicate with the radio module during setup()?
const char RADIO_MODULE_NOT_FOUND = 'x';
const char RADIO_MODULE_FOUND     = 'f';
char radioModuleStatus            = RADIO_MODULE_NOT_FOUND;

boolean rssiOn = true; // true if RSSI is enabled

Debounce squelchDebounce(100);

void setMode(Mode newMode) {
  if (mode == newMode) {
    return;
  }
  mode = newMode;
  switch (mode) {
    case MODE_STOPPED:
      _LOGI("MODE_STOPPED");
      digitalWrite(hw.pins.pinPtt, HIGH);
      endI2STx();
      endI2SRx();
    break;
    case MODE_RX:
      _LOGI("MODE_RX");
      digitalWrite(hw.pins.pinPtt, HIGH);
      squelchDebounce.forceState(true);
      endI2STx();
      initI2SRx();
    break;
    case MODE_TX:
      _LOGI("MODE_TX");
      txStartTime = millis();
      digitalWrite(hw.pins.pinPtt, LOW);
      endI2SRx();
      initI2STx();
    break;
  }
}

void setup() {
  boardSetup();
  // Communication with Android via USB cable
  Serial.setRxBufferSize(USB_BUFFER_SIZE);
  Serial.setTxBufferSize(USB_BUFFER_SIZE);
  Serial.begin(115200);
  Serial.println();
  Serial.println("===== kv4p serial output =====");
  Serial.println("This port will emit binary data using the kv4p protocol.");
  Serial.println("You will see watchdog resets — this is expected behavior.");
  Serial.println("Use `logcat` or a kv4p decoder to view readable logs.");
  Serial.println("More info: https://github.com/VanceVagell/kv4p-ht/blob/main/microcontroller-src/kv4p_ht_esp32_wroom_32/readme.md");
  Serial.println("==============================");
  // Configure watch dog timer (WDT), which will reset the system if it gets stuck somehow.
  esp_task_wdt_init(10, true);  // Reboot if locked up for a bit
  esp_task_wdt_add(NULL);       // Add the current task to WDT watch
  buttonsSetup();
  // Set up radio module defaults
  pinMode(hw.pins.pinPd, OUTPUT);
  digitalWrite(hw.pins.pinPd, HIGH);  // Power on
  pinMode(hw.pins.pinSq, INPUT);
  pinMode(hw.pins.pinPtt, OUTPUT);
  digitalWrite(hw.pins.pinPtt, HIGH);  // Rx
  if (hw.features.hasHL) {
    pinMode(hw.pins.pinHl, OUTPUT);
    digitalWrite(hw.pins.pinHl, LOW);  // High power
  }
  // Communication with DRA818V radio module via GPIO pins
  Serial2.begin(9600, SERIAL_8N1, hw.pins.pinRfModuleRxd, hw.pins.pinRfModuleTxd);
  Serial2.setTimeout(10);  // Very short so we don't tie up rx audio while reading from radio module (responses are tiny so this is ok)
  //
  debugSetup();
  // Begin in STOPPED mode
  squelched = (digitalRead(hw.pins.pinSq) == HIGH);
  setMode(MODE_STOPPED);
  ledSetup();
  sendHello();
  _LOGI("Setup is finished");
}

void doConfig(Config const &config) {
  if (hw.rfModuleType == RF_SA818_UHF) {
    sa818 = sa818_uhf;
  } else {
    sa818 = sa818_vhf;
  }
  if (hw.features.hasHL) {
    digitalWrite(hw.pins.pinHl, config.isHigh ? LOW : HIGH);
  }
  radioModuleStatus = RADIO_MODULE_NOT_FOUND;
  // The sa818.handshake() has 3 retries internally with 2 seconds between each attempt.
  // We have 3 retries on top of that, so total wait time is up to 20 seconds.
  // This should allow the radio module to power up and respond.
  for (int i = 0; i < 3; i++) {
    esp_task_wdt_reset();
    if (sa818.handshake()) { //Check if radio responded to handshake attempt
      radioModuleStatus = RADIO_MODULE_FOUND;
      sa818.volume(hw.volume);
      sa818.filters(false, false, false);
      break;
    }
  }
  uint8_t features = (hw.features.hasHL ? FEATURE_HAS_HL : 0) | (hw.features.hasPhysPTT ? FEATURE_HAS_PHY_PTT : 0);
  sendVersion(FIRMWARE_VER, radioModuleStatus, USB_BUFFER_SIZE, hw.rfModuleType, features);
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
    case COMMAND_HOST_HL:
      if (param_len == sizeof(HlState)) {
        HlState hl;
        memcpy(&hl, params, sizeof(HlState));
        if (hw.features.hasHL) {
          digitalWrite(hw.pins.pinHl, hl.isHigh ? LOW : HIGH);
        }
        esp_task_wdt_reset();
      }
      break;     
    case COMMAND_HOST_RSSI:
      if (param_len == sizeof(RSSIState)) {
        RSSIState rssiState;
        memcpy(&rssiState, params, sizeof(RSSIState));
        rssiState.on ? rssiOn = true : rssiOn = false;    
      }   
      break;                    
  }
}

void rssiLoop() {
  if (rssiOn && mode == MODE_RX) {
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
  squelched = squelchDebounce.debounce((digitalRead(hw.pins.pinSq) == HIGH));
  debugLoop();
  ledLoop();
  buttonsLoop();
  protocolLoop();
  rxAudioLoop();
  txAudioLoop();
  rssiLoop();
}