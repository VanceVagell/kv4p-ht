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
#pragma once

#include <Arduino.h>

// Hardware version detection
#define HW_VER_PIN_0 39    // 0xF0
#define HW_VER_PIN_1 36    // 0x0F
// LOW = 0, HIGH = F, 1 <= analog values <= E
#define HW_VER_V1 (0x00)
#define HW_VER_V2_0C (0xFF)
#define HW_VER_V2_0D (0xF0)
// #define HW_VER_?? (0x0F)  // Unused

typedef uint8_t hw_ver_t;  // This allows us to do a lot more in the future if we want.
hw_ver_t hardware_version = HW_VER_V1;  // lowest common denominator

// Commands defined here must match the Android app
const uint8_t COMMAND_PTT_DOWN         = 1;  // start transmitting audio that Android app will send
const uint8_t COMMAND_PTT_UP           = 2;  // stop transmitting audio, go into RX mode
const uint8_t COMMAND_TUNE_TO          = 3;  // change the frequency
const uint8_t COMMAND_FILTERS          = 4;  // toggle filters on/off
const uint8_t COMMAND_STOP             = 5;  // stop everything, just wait for next command
const uint8_t COMMAND_GET_FIRMWARE_VER = 6;  // report FIRMWARE_VER in the format '00000001' for 1 (etc.)

// Outgoing commands (ESP32 -> Android)
const byte COMMAND_SMETER_REPORT  = 0x53; // 'S'
const byte COMMAND_PHYS_PTT_DOWN  = 0x44; // 'D'
const byte COMMAND_PHYS_PTT_UP    = 0x55; // 'U'
const byte COMMAND_DEBUG_INFO     = 0x01;
const byte COMMAND_DEBUG_ERROR    = 0x02;
const byte COMMAND_DEBUG_WARN     = 0x03;
const byte COMMAND_DEBUG_DEBUG    = 0x04;
const byte COMMAND_DEBUG_TRACE    = 0x05;

// Mode of the app, which is essentially a state machine
enum Mode {
  MODE_TX,
  MODE_RX,
  MODE_STOPPED
};
Mode mode = MODE_STOPPED;

// Current SQ status
bool squelched = false;

////////////////////////////////////////////////////////////////////////////////
/// Forward Declarations
////////////////////////////////////////////////////////////////////////////////
void initI2SRx();
void initI2STx();
void tuneTo(float freqTx, float freqRx, int txTone, int rxTone, int squelch, String bandwidth);
void setMode(int newMode);
void processTxAudio(uint8_t tempBuffer[], int bytesRead);
void iir_lowpass_reset();
hw_ver_t get_hardware_version();
void reportPhysPttState();
void sendCmdToAndroid(byte cmdByte, const byte *params, size_t paramsLen);