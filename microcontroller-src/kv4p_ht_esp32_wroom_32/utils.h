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

#define EVERY_N_MILLISECONDS(INTERVAL) \
    do { \
        static unsigned long _lastExecTime = 0; \
        if (millis() - _lastExecTime >= (INTERVAL)) { \
            _lastExecTime = millis(); 
            
#define END_EVERY_N_MILLISECONDS() \
    }} \
    while(0)  // Ensure the macro behaves like a block


class Debounce {
private:
  unsigned long lastDebounceTime;
  unsigned int debounceDelay;
  bool lastState;
public:
  Debounce(unsigned int delay): debounceDelay(delay), lastDebounceTime(0), lastState(true) {}

  bool debounce(bool state) {
    if (state != lastState) {
        if ((millis() - lastDebounceTime) > debounceDelay) {
            lastState = state;
        }
    } else {
        lastDebounceTime = millis();  // Reset debounce timer only when stable
    }
    return lastState;
  }

  void forceState(bool state) {
    lastState = state;
    lastDebounceTime = millis();
  }
};