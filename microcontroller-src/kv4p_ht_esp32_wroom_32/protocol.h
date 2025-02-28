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
const uint8_t COMMAND_GET_FIRMWARE_VER = 6;  // reply with [COMMAND_VERSION(Version)]

// Outgoing commands (ESP32 -> Android)
const uint8_t COMMAND_SMETER_REPORT  = 0x53; // [COMMAND_SMETER_REPORT(Rssi)]
const uint8_t COMMAND_PHYS_PTT_DOWN  = 0x44; // [COMMAND_PHYS_PTT_DOWN()]
const uint8_t COMMAND_PHYS_PTT_UP    = 0x55; // [COMMAND_PHYS_PTT_UP()]
const uint8_t COMMAND_DEBUG_INFO     = 0x01; // [COMMAND_DEBUG_INFO(char[])]
const uint8_t COMMAND_DEBUG_ERROR    = 0x02; // [COMMAND_DEBUG_ERROR(char[])]
const uint8_t COMMAND_DEBUG_WARN     = 0x03; // [COMMAND_DEBUG_WARN(char[])]
const uint8_t COMMAND_DEBUG_DEBUG    = 0x04; // [COMMAND_DEBUG_DEBUG(char[])]
const uint8_t COMMAND_DEBUG_TRACE    = 0x05; // [COMMAND_DEBUG_TRACE(char[])]
const uint8_t COMMAND_HELLO          = 0x06; // [COMMAND_HELLO()]
const uint8_t COMMAND_RX_AUDIO       = 0x07; // [COMMAND_RX_AUDIO(int8_t[])]
const uint8_t COMMAND_VERSION        = 0x08; // [COMMAND_VERSION(Version)]

// COMMAND_VERSION parameters
struct version {
    uint16_t    ver;
    char        radioModuleStatus;
    hw_ver_t    hw;
} __attribute__((__packed__));
typedef struct version Version;

// COMMAND_SMETER_REPORT parameters
struct rssi {
    uint8_t     val;
} __attribute__((__packed__));
typedef struct rssi Rssi;