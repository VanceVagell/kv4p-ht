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
#include <AudioTools.h>
#include <esp_task_wdt.h>
#include "globals.h"
#include "protocol.h"

bool txStreamConfidured = false;
AnalogAudioStream out;
AudioInfo txInfo(AUDIO_SAMPLE_RATE, 1, 16);
DecoderL8 txDec(false);
EncodedAudioStream txOut(&out, &txDec); 

// Tx runaway detection stuff
uint32_t txStartTime = -1;
const uint16_t RUNAWAY_TX_SEC = 200;

void initI2STx() {  
  auto config = out.defaultConfig(TX_MODE);
  config.copyFrom(txInfo);
  config.adc_pin = DAC_PIN;
  config.is_blocking_write = false;
  config.buffer_size = I2S_WRITE_LEN;
  config.buffer_count = 8;
  out.begin(config);
  txOut.begin(txInfo);
  txStreamConfidured = true;
}

void endI2STx() {
  if (txStreamConfidured) {
    txOut.end();
    out.end();
  }
  txStreamConfidured = false;
}

void processTxAudio(uint8_t *src, size_t len) {
  txOut.write(src, len);
}

void inline txAudioLoop() {
  if (mode == MODE_TX) {
    // Check for runaway tx
    if ((millis() - txStartTime) > RUNAWAY_TX_SEC * 1000) {
      setMode(MODE_RX);
      esp_task_wdt_reset();
    }
  }
}