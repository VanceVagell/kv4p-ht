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
#include "globals.h"
#include "debug.h"

// Delimeter must also match Android app
const uint8_t COMMAND_DELIMITER[] = {0xDE, 0xAD, 0xBE, 0xEF};
#define DELIMITER_LENGTH sizeof(COMMAND_DELIMITER)

// Incoming commands (Android -> ESP32)
enum RcvCommand {
  COMMAND_RCV_UNKNOWN    = 0x00,
  COMMAND_HOST_PTT_DOWN  = 0x01, // [COMMAND_HOST_PTT_DOWN()]
  COMMAND_HOST_PTT_UP    = 0x02, // [COMMAND_HOST_PTT_UP()]
  COMMAND_HOST_GROUP     = 0x03, // [COMMAND_HOST_GROUP(Group)]
  COMMAND_HOST_FILTERS   = 0x04, // [COMMAND_HOST_FILTERS(Filters)]
  COMMAND_HOST_STOP      = 0x05, // [COMMAND_HOST_STOP()] 
  COMMAND_HOST_CONFIG    = 0x06, // [COMMAND_HOST_CONFIG(Config)] -> [COMMAND_VERSION(Version)]
  COMMAND_HOST_TX_AUDIO  = 0x07  // [COMMAND_HOST_TX_AUDIO(uint8_t[])]
};

// Outgoing commands (ESP32 -> Android)
enum SndCommand {
  COMMAND_SND_UNKNOWN    = 0x00,
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
  COMMAND_WINDOW_UPDATE  = 0x09,
};

// COMMAND_VERSION parameters
struct version {
  uint16_t    ver;
  char        radioModuleStatus;
  hw_ver_t    hw;
  size_t      windowSize;
} __attribute__((__packed__));
typedef struct version Version;

// COMMAND_SMETER_REPORT parameters
struct rssi {
  uint8_t     rssi;
} __attribute__((__packed__));
typedef struct rssi Rssi;

// COMMAND_HOST_GROUP parameters
struct group {
  uint8_t bw;
  float freq_tx;
  float freq_rx;
  uint8_t ctcss_tx;
  uint8_t squelch;
  uint8_t ctcss_rx;
} __attribute__((__packed__));
typedef struct group Group;

// COMMAND_HOST_FILTERS parameters
struct filters {
  uint8_t flags;  // Uses bitmask for pre, high, and low
} __attribute__((__packed__));
typedef struct filters Filters;

#define FILTER_PRE  (1 << 0)
#define FILTER_HIGH (1 << 1)
#define FILTER_LOW  (1 << 2)

// COMMAND_HOST_CONFIG parameters
struct config {
  uint8_t radioType; 
} __attribute__((__packed__));
typedef struct config Config;

// COMMAND_WINDOW_ACK parameters
struct window_update {
  size_t size; 
} __attribute__((__packed__));
typedef struct window_update WindowUpdate;


/**
 * Send a command with params
 * Format: [DELIMITER(8 bytes)] [CMD(1 byte)] [paramLen(1 byte)] [param data(N bytes)]
 */
void __sendCmdToHost(SndCommand cmd, const byte *params, size_t paramsLen) {
  // Safety check: limit paramsLen to 255 for 1-byte length
  if (paramsLen > PROTO_MTU) {
    paramsLen = PROTO_MTU;  // or handle differently (split, or error, etc.)
  }
  // 1. Leading delimiter
  Serial.write(COMMAND_DELIMITER, DELIMITER_LENGTH);
  // 2. Command byte
  Serial.write((uint8_t*) &cmd, 1);
  // 3. Parameter length
  uint16_t len = paramsLen;
  Serial.write((uint8_t*) &len, sizeof(len));
  // 4. Parameter bytes
  if (paramsLen > 0) {
    Serial.write(params, paramsLen);
  }
}

void inline __sendCmdToHost(SndCommand cmd) {
  __sendCmdToHost(cmd, NULL, 0);
}

void inline sendHello() {
  __sendCmdToHost(COMMAND_HELLO);
}

void inline sendRssi(uint8_t rssi) {
  Rssi params = {
    .rssi = rssi
  };
  __sendCmdToHost(COMMAND_SMETER_REPORT, (uint8_t*) &params, sizeof(params));
}

void inline sendVersion(uint16_t ver, char radioModuleStatus, hw_ver_t hw, size_t windowSize) {
  Version params = {
    .ver = ver,
    .radioModuleStatus = radioModuleStatus,
    .hw = hw,
    .windowSize = windowSize
  };
  __sendCmdToHost(COMMAND_VERSION, (uint8_t*) &params, sizeof(params));
}

void inline sendPhysPttState(bool isPhysPttDown) {
  __sendCmdToHost(isPhysPttDown ? COMMAND_PHYS_PTT_DOWN : COMMAND_PHYS_PTT_UP);
}

void inline sendAudio(const byte *data, size_t len) {
  __sendCmdToHost(COMMAND_RX_AUDIO, data, len);
}

void inline sendWindowAck(size_t size) {
  WindowUpdate params = {
    .size = size,
  };
  __sendCmdToHost(COMMAND_WINDOW_UPDATE, (uint8_t*) &params, sizeof(params));
}

typedef void (*CommandCallback)(RcvCommand command, uint8_t *params, size_t param_len);

class FrameParser {
public:
  FrameParser(Stream &serial, CommandCallback callback) 
    : _serial(serial), _callback(callback), _matchedDelimiterTokens(0),
      _command(COMMAND_RCV_UNKNOWN), _commandParamLen(0), _paramIndex(0) {}

  void loop() {
    while (_serial.available() > 0) {
      uint8_t b = _serial.read();
      if (processByte(b)) {
        return;
      }
    }
  }
private:
  Stream &_serial;
  CommandCallback _callback;
  uint8_t _matchedDelimiterTokens;
  RcvCommand _command;
  size_t _commandParamLen; 
  uint8_t _commandParams[PROTO_MTU];
  size_t _paramIndex;

  inline bool processByte(uint8_t b) {
    if (_matchedDelimiterTokens < DELIMITER_LENGTH) {
      _matchedDelimiterTokens = (b == COMMAND_DELIMITER[_matchedDelimiterTokens]) ? _matchedDelimiterTokens + 1 : 0;
    } else if (_matchedDelimiterTokens == DELIMITER_LENGTH) {
      _command = (RcvCommand)b;
      _matchedDelimiterTokens++;
    } else if (_matchedDelimiterTokens == DELIMITER_LENGTH + 1) {
      _commandParamLen = b;
      _matchedDelimiterTokens++;
    } else if (_matchedDelimiterTokens == DELIMITER_LENGTH + 2) {  
      _commandParamLen |= (b << 8);
      _paramIndex = 0;
      _matchedDelimiterTokens++;
      if (_commandParamLen == 0) {
        _callback(_command, _commandParams, 0);
        sendWindowAck(DELIMITER_LENGTH + 1 + 2);
        resetParser();
        return true;
      }
      if (_commandParamLen > PROTO_MTU) {
        resetParser();
      }
    } else {
      if (_paramIndex < _commandParamLen) {
        _commandParams[_paramIndex++] = b;
      }
      if (_paramIndex == _commandParamLen) {
        _callback(_command, _commandParams, _commandParamLen);
        sendWindowAck(DELIMITER_LENGTH + 1 + 2 + _commandParamLen);
        resetParser();
        return true;
      }
    }
    return false;
  }

  void resetParser() {
    _matchedDelimiterTokens = 0;
    _paramIndex = 0;
    _commandParamLen = 0;
  }
};

// Forward declaration of handleCommands function
// This function processes incoming commands, taking a command type, parameters, and their length.
void handleCommands(RcvCommand command, uint8_t *params, size_t param_len);

// Create an instance of FrameParser and associate it with the handleCommands function
// The FrameParser object uses the Serial interface and the handleCommands function for processing commands.
FrameParser parser(Serial, &handleCommands);

void inline protocolLoop() {
  parser.loop();
}