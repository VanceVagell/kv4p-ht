#pragma once

#include <Arduino.h>
#include "globals.h"
#include "debug.h"

// Delimeter must also match Android app
#define DELIMITER_LENGTH 8
const uint8_t COMMAND_DELIMITER[DELIMITER_LENGTH] = {0xDE, 0xAD, 0xBE, 0xEF, 0xDE, 0xAD, 0xBE, 0xEF};

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
const byte COMMAND_HELLO          = 0x06;
const byte COMMAND_RX_AUDIO       = 0x07;
const byte COMMAND_VERSION        = 0x08;

struct version {
    uint16_t    ver;
    char        radioModuleStatus;
} __attribute__((__packed__));
  
typedef struct version Version;