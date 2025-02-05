#pragma once

#include <Arduino.h>
#include "globals.h"

// Built in LED
#define LED_PIN 2

// NeoPixel support
#define PIXELS_PIN 13
#define NUM_PIXELS 1

struct RGBColor {
  uint8_t red;
  uint8_t green;
  uint8_t blue;
};
const RGBColor COLOR_STOPPED = {0, 0, 0};
const RGBColor COLOR_RX_SQL_CLOSED = {0, 0, 32};
const RGBColor COLOR_RX_SQL_OPEN = {0, 32, 0};
const RGBColor COLOR_TX = {16, 16, 0};
const RGBColor COLOR_BLACK = {0, 0, 0};
RGBColor COLOR_HW_VER = COLOR_BLACK;

void neopixelColor(RGBColor C, uint8_t bright = 255) {
  uint8_t red = (uint16_t(C.red) * bright) / 255;
  uint8_t green = (uint16_t(C.green) * bright) / 255;
  uint8_t blue = (uint16_t(C.blue) * bright) / 255;
  neopixelWrite(PIXELS_PIN, red, green, blue);
}

// Calculate a float between min and max, that ramps from min to max in half of breath_every,
// and from max to min the second half of breath_every.
// now and breath_every are arbitrary units, but usually are milliseconds from millis().
uint8_t calc_breath(uint32_t now, uint32_t breath_every, uint8_t min, uint8_t max) {
  uint16_t amplitude = max - min; // Ensure enough range for calculations
  uint16_t bright = ((now % breath_every) * 512) / breath_every; // Scale to 0-512
  if (bright > 255) bright = 512 - bright; // Fold >255 back down to 255-0
  return ((bright * amplitude) / 255) + min;
}

void show_LEDs() {
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
        digitalWrite(LED_PIN, LOW);
        neopixelColor(COLOR_HW_VER);
        break;
      case MODE_RX:
        digitalWrite(LED_PIN, LOW);
        if (squelched) {
          neopixelColor(COLOR_RX_SQL_CLOSED, calc_breath(now, 2000, 127, 255));
        } else {
          neopixelColor(COLOR_RX_SQL_OPEN);
        }
        break;
      case MODE_TX:
        digitalWrite(LED_PIN, HIGH);
        neopixelColor(COLOR_TX);
        break;
    }
  }
}

void ledSetup() {
  // Debug LED
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  show_LEDs();

}

void ledLoop() {  
  show_LEDs();
}