#include "led.h"

void neopixelColor(const RGBColor &c, uint8_t bright) {
  uint8_t red = (uint16_t(c.red) * bright + 128) >> 8;
  uint8_t green = (uint16_t(c.green) * bright + 128) >> 8;
  uint8_t blue = (uint16_t(c.blue) * bright + 128) >> 8;
  neopixelWrite(PIXELS_PIN, red, green, blue);
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

RGBColor COLOR_HW_VER = COLOR_BLACK;