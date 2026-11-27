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

#define DEBOUNCE_MS 50  // Debounce each physical button independently.
bool isPhysPtt1Down = false;
bool isPhysPtt2Down = false;
Debounce ptt1Debounce(DEBOUNCE_MS);
Debounce ptt2Debounce(DEBOUNCE_MS);

void inline buttonsSetup() {
  ptt1Debounce.forceState(false);
  ptt2Debounce.forceState(false);
  if (hw.pins.pinPttPhys1 >= 0) {
    pinMode(hw.pins.pinPttPhys1, INPUT_PULLUP);
  }
  if (hw.pins.pinPttPhys2 >= 0) {
    pinMode(hw.pins.pinPttPhys2, INPUT_PULLUP);
  }
}

void inline buttonsLoop() {
  bool button1Down = hw.pins.pinPttPhys1 >= 0 &&
      ptt1Debounce.debounce(digitalRead(hw.pins.pinPttPhys1) == LOW);
  bool button2Down = hw.pins.pinPttPhys2 >= 0 &&
      ptt2Debounce.debounce(digitalRead(hw.pins.pinPttPhys2) == LOW);
  if (button1Down != isPhysPtt1Down || button2Down != isPhysPtt2Down) {
    isPhysPtt1Down = button1Down;
    isPhysPtt2Down = button2Down;
    markDeviceStateDirty();
  }
}
