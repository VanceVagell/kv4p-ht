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

Repurposes the kv4p-ht board (ESP32-WROOM-32 + SA818) as a standalone
RX-only receiver:
  - live audio as HTTP endless-WAV, PCM16 mono 16 kHz: http://<ip>:8000/stream.wav
    (playable directly in VLC; a decoder server consumes the same URL)
  - web UI on port 80: channel table, tuning, volume/squelch, WiFi, OTA update
  - SA818 filters always fully bypassed — flat audio for VDV-FFSK/NEMO data
  - no Bluetooth, no KISS protocol, no TX

The radio is permanently in RX (PTT never asserted).
*/

#include <Arduino.h>
#include <esp_task_wdt.h>
#include "hardware.h"
#include "config.h"
#include "radio.h"
#include "audio.h"
#include "streamer.h"
#include "wifiMgr.h"
#include "webui.h"

void setup() {
  Serial.begin(115200);
  Serial.printf("\n===== kv4p RX streamer v%s =====\n", FIRMWARE_VERSION);

  boardSetup();
  loadConfig();

  // Watchdog: reboot if the main loop stalls for 10 s. The streamer task is
  // deliberately not registered (its socket writes may block).
  esp_task_wdt_init(10, true);
  esp_task_wdt_add(NULL);

  // Radio module pins: powered on, permanently in RX.
  pinMode(hw.pins.pinPd, OUTPUT);
  digitalWrite(hw.pins.pinPd, HIGH);
  pinMode(hw.pins.pinSq, INPUT);
  pinMode(hw.pins.pinPtt, OUTPUT);
  digitalWrite(hw.pins.pinPtt, HIGH);  // HIGH = RX
  if (hw.pins.pinHl != -1) {
    pinMode(hw.pins.pinHl, OUTPUT);
    digitalWrite(hw.pins.pinHl, HIGH);  // low power (TX unused anyway)
  }
  pinMode(hw.pins.pinLed, OUTPUT);

  Serial2.begin(9600, SERIAL_8N1, hw.pins.pinRfModuleRxd, hw.pins.pinRfModuleTxd);
  Serial2.setTimeout(10);

  initRadio();
  audioInit();
  wifiSetup();
  webSetup();
  streamerStart();

  Serial.printf("[setup] done. web ui on http://<ip>/  stream on :%u/stream.wav\n", cfg.streamPort);
}

void squelchLoop() {
  static unsigned long lastChange = 0;
  static bool raw = false;
  bool now = (digitalRead(hw.pins.pinSq) == LOW);  // LOW = squelch open (carrier present)
  if (now != raw) {
    raw = now;
    lastChange = millis();
  } else if (millis() - lastChange > 30 && squelchOpen != raw) {
    squelchOpen = raw;
    digitalWrite(hw.pins.pinLed, squelchOpen ? HIGH : LOW);
  }
}

void statusLoop() {
  static unsigned long last = 0;
  if (millis() - last < 5000) {
    return;
  }
  last = millis();
  Serial.printf("[status] heap=%u wifi=%d rssi=%d clients=%u overruns=%u sq=%d\n",
                ESP.getFreeHeap(), WiFi.status() == WL_CONNECTED, WiFi.RSSI(),
                streamClientCount, streamOverruns, squelchOpen);
}

void loop() {
  squelchLoop();
  audioLoop();
  server.handleClient();
  wifiLoop();
  statusLoop();
}
