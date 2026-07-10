/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include <DRA818.h>
#include <esp_task_wdt.h>
#include "hardware.h"
#include "config.h"

DRA818 sa818Vhf(&Serial2, SA818_VHF);
DRA818 sa818Uhf(&Serial2, SA818_UHF);
DRA818 *sa818 = &sa818Vhf;

bool radioModuleFound = false;
uint32_t appliedFreqHz = 0;
uint8_t appliedBandwidth = 0xFF;
uint8_t appliedSquelch = 0xFF;
uint8_t appliedVolume = 0xFF;

void drainRadioSerial() {
  while (Serial2.available()) {
    Serial2.read();
  }
}

// Tune the SA818. Filters are bypassed unconditionally (pre/de-emphasis,
// high-pass, low-pass all off): the audio path must stay flat because data
// channels carry FFSK modem signals. Voice/data on a channel is metadata only.
bool applyRadioTuning() {
  if (!radioModuleFound) {
    return false;
  }
  uint32_t freqHz;
  uint8_t bandwidth;
  activeTuning(freqHz, bandwidth);
  float freqMHz = freqHz / 1e6f;
  uint8_t bw = (bandwidth == 1) ? DRA818_25K : DRA818_12K5;

  if (freqHz != appliedFreqHz || bw != appliedBandwidth || cfg.squelch != appliedSquelch) {
    drainRadioSerial();
    bool ok = false;
    for (int i = 0; i < 3 && !ok; i++) {
      esp_task_wdt_reset();
      ok = sa818->group(bw, freqMHz, freqMHz, 0, cfg.squelch, 0);
    }
    if (!ok) {
      Serial.printf("[radio] group(%0.4f MHz) failed\n", freqMHz);
      return false;
    }
    appliedFreqHz = freqHz;
    appliedBandwidth = bw;
    appliedSquelch = cfg.squelch;
    Serial.printf("[radio] tuned %0.4f MHz bw=%s sq=%d\n", freqMHz, bw == DRA818_25K ? "25k" : "12.5k", cfg.squelch);
  }

  if (cfg.volume != appliedVolume) {
    drainRadioSerial();
    if (sa818->volume(cfg.volume)) {
      appliedVolume = cfg.volume;
      Serial.printf("[radio] volume=%d\n", cfg.volume);
    }
  }
  return true;
}

void initRadio() {
  sa818 = (hw.rfModuleType == RF_SA818_UHF) ? &sa818Uhf : &sa818Vhf;
  radioModuleFound = false;
  // handshake() retries 3x internally with 2s waits; 3 outer tries give the
  // module up to ~20s to power up (matches upstream behavior).
  for (int i = 0; i < 3; i++) {
    esp_task_wdt_reset();
    if (sa818->handshake()) {
      radioModuleFound = true;
      break;
    }
  }
  if (!radioModuleFound) {
    Serial.println("[radio] SA818 module not responding!");
    return;
  }
  drainRadioSerial();
  bool filtersOk = false;
  for (int i = 0; i < 5 && !filtersOk; i++) {
    esp_task_wdt_reset();
    filtersOk = sa818->filters(false, false, false);
  }
  if (!filtersOk) {
    // Never proceed with a shaped audio path — data channels need it flat.
    Serial.println("[radio] filter bypass failed, radio disabled");
    radioModuleFound = false;
    return;
  }
  applyRadioTuning();
}
