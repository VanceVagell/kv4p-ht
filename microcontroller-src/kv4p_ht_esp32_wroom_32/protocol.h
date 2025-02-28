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
enum Esp32ToHost {
  COMMAND_SMETER_REPORT  = 0x53, // [COMMAND_SMETER_REPORT(Rssi)]
  COMMAND_PHYS_PTT_DOWN  = 0x44, // [COMMAND_PHYS_PTT_DOWN()]
  COMMAND_PHYS_PTT_UP    = 0x55, // [COMMAND_PHYS_PTT_UP()]
  COMMAND_DEBUG_INFO     = 0x01, // [COMMAND_DEBUG_INFO(char[])]
  COMMAND_DEBUG_ERROR    = 0x02, // [COMMAND_DEBUG_ERROR(char[])]
  COMMAND_DEBUG_WARN     = 0x03, // [COMMAND_DEBUG_WARN(char[])]
  COMMAND_DEBUG_DEBUG    = 0x04, // [COMMAND_DEBUG_DEBUG(char[])]
  COMMAND_DEBUG_TRACE    = 0x05, // [COMMAND_DEBUG_TRACE(char[])]
  COMMAND_HELLO          = 0x06, // [COMMAND_HELLO()]
  COMMAND_RX_AUDIO       = 0x07, // [COMMAND_RX_AUDIO(int8_t[])]
  COMMAND_VERSION        = 0x08, // [COMMAND_VERSION(Version)]
};

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

/**
 * Send a command with params
 * Format: [DELIMITER(8 bytes)] [CMD(1 byte)] [paramLen(1 byte)] [param data(N bytes)]
 */
void __sendCmdToHost(Esp32ToHost cmd, const byte *params, size_t paramsLen) {
  // Safety check: limit paramsLen to 255 for 1-byte length
  if (paramsLen > 255) {
    paramsLen = 255;  // or handle differently (split, or error, etc.)
  }
  // 1. Leading delimiter
  Serial.write(COMMAND_DELIMITER, DELIMITER_LENGTH);
  // 2. Command byte
  Serial.write((uint8_t*) &cmd, 1);
  // 3. Parameter length
  uint8_t len = paramsLen;
  Serial.write(&len, 1);
  // 4. Parameter bytes
  if (paramsLen > 0) {
    Serial.write(params, paramsLen);
  }
}

void inline __sendCmdToHost(Esp32ToHost cmd) {
  __sendCmdToHost(cmd, NULL, 0);
}

void inline sendHello() {
  __sendCmdToHost(COMMAND_HELLO);
}

void inline sendRssi(uint8_t rssi) {
  Rssi params = {
    .val = rssi
  };
  __sendCmdToHost(COMMAND_SMETER_REPORT, (uint8_t*) &params, sizeof(params));
}

void inline sendVersion(uint16_t ver, char radioModuleStatus, hw_ver_t hw) {
  Version params = {
    .ver = ver,
    .radioModuleStatus = radioModuleStatus,
    .hw = hw
  };
  __sendCmdToHost(COMMAND_VERSION, (uint8_t*) &params, sizeof(params));
}

void inline sendPhysPttState(bool isPhysPttDown) {
  __sendCmdToHost(isPhysPttDown ? COMMAND_PHYS_PTT_DOWN : COMMAND_PHYS_PTT_UP);
}

void inline sendAudio(const byte *data, size_t len) {
  __sendCmdToHost(COMMAND_RX_AUDIO, data, len);
}