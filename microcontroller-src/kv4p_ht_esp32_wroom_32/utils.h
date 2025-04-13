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