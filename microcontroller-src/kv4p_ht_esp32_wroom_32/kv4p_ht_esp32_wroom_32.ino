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

#include <algorithm>
#include <DRA818.h>
#include <driver/adc.h>
#include <driver/i2s.h>
#include <esp_task_wdt.h>

#include "Radio.hpp"
#include "Constants.hpp"
#include "Globals.hpp"

// int fadeDirection = 0;             // 0: no fade, 1: fade in, -1: fade out
// int attenuation = ATTENUATION_MAX; // Full volume
// bool lastSquelched = false;

Radio radio;

void setup()
{
  // Communication with Android via USB cable
  Serial.begin(921600);
  Serial.setRxBufferSize(USB_BUFFER_SIZE);
  Serial.setTxBufferSize(USB_BUFFER_SIZE);

  // Configure watch dog timer (WDT), which will reset the system if it gets stuck somehow.
  esp_task_wdt_init(10, true); // Reboot if locked up for a bit
  esp_task_wdt_add(NULL);      // Add the current task to WDT watch

  // Debug LED
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // Set up radio module defaults
  pinMode(PD_PIN, OUTPUT);
  digitalWrite(PD_PIN, HIGH); // Power on
  pinMode(SQ_PIN, INPUT);
  pinMode(PTT_PIN, OUTPUT);
  digitalWrite(PTT_PIN, HIGH); // Rx

  // Communication with DRA818V radio module via GPIO pins
  Serial2.begin(9600, SERIAL_8N1, RXD2_PIN, TXD2_PIN);

  int result = -1;
  while (result != 1)
  {
    result = dra->handshake(); // Wait for module to start up
  }
  // Serial.println("handshake: " + String(result));
  // tuneTo(146.700, 146.700, 0, 0);
  result = dra->volume(8);
  // Serial.println("volume: " + String(result));
  result = dra->filters(false, false, false);
  // Serial.println("filters: " + String(result));
}

void loop()
{
  radio.loop(); // Call the Radio object's loop function
}
