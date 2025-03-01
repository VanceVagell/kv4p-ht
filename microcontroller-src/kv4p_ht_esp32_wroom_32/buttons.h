#pragma once

#include <Arduino.h>
#include "globals.h"
#include "protocol.h"

#define DEBOUNCE_MS 50  // Minimum ms between PHYS_PTT_PIN1/2 down and then up, to avoid bouncing from spotty electrical contact.
boolean isPhysPttDown     = false;

void inline buttonsSetup() {
    // Optional physical PTT buttons
    pinMode(PHYS_PTT_PIN1, INPUT_PULLUP);
    pinMode(PHYS_PTT_PIN2, INPUT_PULLUP);
}

void inline buttonsLoop() {
  static long buttonDebounceMillis = -1;
  // Report any physical PTT button presses or releases back to Android app (note that
  // we don't start tx here, Android app decides what to do, since the user could be in
  // some mode where tx doesn't make sense, like in Settings).
  long msSincePhysButtonChange = millis() - buttonDebounceMillis;
  if (buttonDebounceMillis == -1 || msSincePhysButtonChange > DEBOUNCE_MS) {
    // If EITHER physical PTT button has just become "down", let Android app know
    if (!isPhysPttDown && (digitalRead(PHYS_PTT_PIN1) == LOW || digitalRead(PHYS_PTT_PIN2) == LOW)) {
      isPhysPttDown = true;
      sendPhysPttState(isPhysPttDown);
      buttonDebounceMillis = millis();
    } else if (isPhysPttDown &&  (digitalRead(PHYS_PTT_PIN1) == HIGH && digitalRead(PHYS_PTT_PIN2) == HIGH)) {
      // If BOTH PTT buttons are now "up", let Android app know
      isPhysPttDown = false;
      sendPhysPttState(isPhysPttDown);
      buttonDebounceMillis = millis();
    }
  }
}