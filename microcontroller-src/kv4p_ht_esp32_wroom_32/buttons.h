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
#include "globals.h"
#include "protocol.h"
#include "utils.h"

#define DEBOUNCE_MS 50  // Minimum ms between PHYS_PTT_PIN1/2 down and then up, to avoid bouncing from spotty electrical contact.
boolean isPhysPttDown     = false;
Debounce pttDebounce(DEBOUNCE_MS);

void inline buttonsSetup() {
  if (hw.features.hasPhysPTT) {
    pinMode(hw.pins.pinPttPhys1, INPUT_PULLUP);
    pinMode(hw.pins.pinPttPhys2, INPUT_PULLUP);
  }
}

void inline buttonsLoop() {
  if (hw.features.hasPhysPTT) {
    bool debouncedPttState = pttDebounce.debounce(digitalRead(hw.pins.pinPttPhys1) == LOW || digitalRead(hw.pins.pinPttPhys2) == LOW);
    if (debouncedPttState != isPhysPttDown) {
      isPhysPttDown = debouncedPttState;
      sendPhysPttState(isPhysPttDown);
    }
  }
}