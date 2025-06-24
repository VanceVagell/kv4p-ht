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

#define NUM_PIXELS 1

const RGBColor COLOR_STOPPED = {0, 0, 0};
const RGBColor COLOR_RX_SQL_CLOSED = {0, 0, 32};
const RGBColor COLOR_RX_SQL_OPEN = {0, 32, 0};
const RGBColor COLOR_TX = {16, 16, 0};
const RGBColor COLOR_BLACK = {0, 0, 0};

void neopixelColor(const RGBColor &c, uint8_t bright = 255) {
  uint8_t red = (uint16_t(c.red) * bright + 128) >> 8;
  uint8_t green = (uint16_t(c.green) * bright + 128) >> 8;
  uint8_t blue = (uint16_t(c.blue) * bright + 128) >> 8;
  neopixelWrite(hw.pins.pinPixels, red, green, blue);
}

// Calculate a float between min and max, that ramps from min to max in half of breath_every,
// and from max to min the second half of breath_every.
// now and breath_every are arbitrary units, but usually are milliseconds from millis().
uint8_t calcBreath(uint32_t now, uint32_t breath_every, uint8_t min, uint8_t max) {
  uint16_t amplitude = max - min; // Ensure enough range for calculations
  uint16_t bright = ((now % breath_every) * 512) / breath_every; // Scale to 0-512
  if (bright > 255) bright = 512 - bright; // Fold >255 back down to 255-0
  return ((bright * amplitude) / 255) + min;
}

void inline showLEDs() {
  // When to actually act?
  static uint32_t next_time = 0;
  const static uint32_t update_every = 50;    // in milliseconds
  uint32_t now = millis();
  // Only change LEDs if:
  // * it's been more than update_every ms since we've last set the LEDs.
  if (now >= next_time) {
    next_time = now + update_every;
    switch (mode) {
      case MODE_STOPPED:
        digitalWrite(hw.pins.pinLed, LOW);
        neopixelColor(hw.stoppedColor);
        break;
      case MODE_RX:
        digitalWrite(hw.pins.pinLed, LOW);
        if (squelched) {
          neopixelColor(COLOR_RX_SQL_CLOSED, calcBreath(now, 2000, 32, 255));
        } else {
          neopixelColor(COLOR_RX_SQL_OPEN);
        }
        break;
      case MODE_TX:
        digitalWrite(hw.pins.pinLed, HIGH);
        neopixelColor(COLOR_TX);
        break;
    }
  }
}

void inline ledSetup() {
  // Debug LED
  pinMode(hw.pins.pinLed, OUTPUT);
  digitalWrite(hw.pins.pinLed, LOW);
  showLEDs();
}

void inline ledLoop() {  
  showLEDs();
}