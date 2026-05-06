/*
KV4P-HT (see http://kv4p.com)
Copyright (C) 2025 Vance Vagell

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
#include <type_traits>
#include "globals.h"

#define REQUIRE_TRIVIALLY_COPYABLE(T) static_assert(std::is_trivially_copyable<T>::value, #T " must be trivially copyable!")

// KV4P KISS transport. Standard KISS DATA frames carry AX.25 packets.
// kv4p-specific commands are carried in KISS SETHARDWARE vendor frames:
// FEND 0x06 "KV4P" 0x01 <kv4pCommand> <payload...> FEND.
static constexpr uint8_t KISS_FEND = 0xC0;
static constexpr uint8_t KISS_FESC = 0xDB;
static constexpr uint8_t KISS_TFEND = 0xDC;
static constexpr uint8_t KISS_TFESC = 0xDD;
static constexpr uint8_t KISS_CMD_DATA = 0x00;
static constexpr uint8_t KISS_CMD_SETHARDWARE = 0x06;
static constexpr uint8_t KISS_PORT_0 = 0x00;
static constexpr uint8_t KV4P_PROTOCOL_VERSION = 0x01;
static constexpr size_t KV4P_VENDOR_HEADER_LEN = 6; // "KV4P" + version + kv4pCommand
static constexpr size_t KISS_MAX_FRAME_SIZE = PROTO_MTU + 1 + KV4P_VENDOR_HEADER_LEN;
static constexpr uint8_t KV4P_VENDOR_PREFIX[] = {'K', 'V', '4', 'P'};

// Incoming commands (Android -> ESP32)
enum RcvCommand {
  COMMAND_RCV_UNKNOWN    = 0x00,
  COMMAND_HOST_TX_AUDIO  = 0x07, // [COMMAND_HOST_TX_AUDIO(uint8_t[])]
  COMMAND_HOST_DESIRED_STATE = 0x0D, // [COMMAND_HOST_DESIRED_STATE(HostDesiredState)]
};

// Outgoing commands (ESP32 -> Android)
enum SndCommand {
  COMMAND_SND_UNKNOWN    = 0x00,
  COMMAND_DEBUG_INFO     = 0x01, // [COMMAND_DEBUG_INFO(char[])]
  COMMAND_DEBUG_ERROR    = 0x02, // [COMMAND_DEBUG_ERROR(char[])]
  COMMAND_DEBUG_WARN     = 0x03, // [COMMAND_DEBUG_WARN(char[])]
  COMMAND_DEBUG_DEBUG    = 0x04, // [COMMAND_DEBUG_DEBUG(char[])]
  COMMAND_DEBUG_TRACE    = 0x05, // [COMMAND_DEBUG_TRACE(char[])]
  COMMAND_HELLO          = 0x06, // [COMMAND_HELLO(Hello)]
  COMMAND_RX_AUDIO       = 0x07, // [COMMAND_RX_AUDIO(int8_t[])]
  COMMAND_WINDOW_UPDATE  = 0x09,
  COMMAND_DEVICE_STATE   = 0x0B, // [COMMAND_DEVICE_STATE(DeviceState)]
};

// COMMAND_HELLO parameters: Version + initial DeviceState
struct [[gnu::packed]] Version {
  uint16_t     ver;
  char         radioModuleStatus;
  size_t       windowSize;
  RfModuleType rfModuleType;
  float        minRadioFreq;
  float        maxRadioFreq;
  uint8_t      features; 
};
REQUIRE_TRIVIALLY_COPYABLE(Version);
#define FEATURE_HAS_HL      (1 << 0)
#define FEATURE_HAS_PHY_PTT (1 << 1)
#define FEATURE_HAS_ESP32_AFSK (1 << 2)

#define HOST_STATE_RADIO_CONFIG_VALID (1 << 0)
#define HOST_STATE_PTT_REQUESTED      (1 << 1)
#define HOST_STATE_RX_AUDIO_OPEN      (1 << 2)
#define HOST_STATE_HIGH_POWER         (1 << 3)
#define HOST_STATE_RSSI_ENABLED       (1 << 4)
#define HOST_STATE_FILTER_PRE         (1 << 5)
#define HOST_STATE_FILTER_HIGH        (1 << 6)
#define HOST_STATE_FILTER_LOW         (1 << 7)

#define DEVICE_STATE_RADIO_CONFIG_VALID HOST_STATE_RADIO_CONFIG_VALID
#define DEVICE_STATE_PTT_REQUESTED      HOST_STATE_PTT_REQUESTED
#define DEVICE_STATE_RX_AUDIO_OPEN      HOST_STATE_RX_AUDIO_OPEN
#define DEVICE_STATE_HIGH_POWER         HOST_STATE_HIGH_POWER
#define DEVICE_STATE_RSSI_ENABLED       HOST_STATE_RSSI_ENABLED
#define DEVICE_STATE_FILTER_PRE         HOST_STATE_FILTER_PRE
#define DEVICE_STATE_FILTER_HIGH        HOST_STATE_FILTER_HIGH
#define DEVICE_STATE_FILTER_LOW         HOST_STATE_FILTER_LOW
#define DEVICE_STATE_PHYS_PTT_DOWN      (1 << 8)
#define DEVICE_STATE_TX_ACTIVE          (1 << 9)
#define DEVICE_STATE_SQUELCHED          (1 << 10)

enum DeviceMode : uint8_t {
  DEVICE_MODE_TX = 0,
  DEVICE_MODE_RX = 1,
  DEVICE_MODE_STOPPED = 2,
};

enum DeviceStateError : uint8_t {
  DEVICE_STATE_ERROR_NONE = 0,
  DEVICE_STATE_ERROR_RADIO_CONFIG_FAILED = 1,
  DEVICE_STATE_ERROR_FILTERS_FAILED = 2,
};

struct [[gnu::packed]] HostDesiredState {
  uint32_t sequence;
  int32_t memoryId;
  uint16_t flags;
  uint8_t bw;
  float freq_tx;
  float freq_rx;
  uint8_t ctcss_tx;
  uint8_t squelch;
  uint8_t ctcss_rx;
};
REQUIRE_TRIVIALLY_COPYABLE(HostDesiredState);

struct [[gnu::packed]] DeviceState {
  uint32_t appliedSequence;
  int32_t memoryId;
  uint16_t flags;
  uint8_t bw;
  float freq_tx;
  float freq_rx;
  uint8_t ctcss_tx;
  uint8_t squelch;
  uint8_t ctcss_rx;
  char radioModuleStatus;
  uint8_t mode;
  uint8_t lastError;
  uint8_t latestRssi;
};
REQUIRE_TRIVIALLY_COPYABLE(DeviceState);

struct [[gnu::packed]] Hello {
  Version version;
  DeviceState deviceState;
};
REQUIRE_TRIVIALLY_COPYABLE(Hello);

// COMMAND_WINDOW_ACK parameters
struct [[gnu::packed]] WindowUpdate {
  size_t size; 
};
REQUIRE_TRIVIALLY_COPYABLE(WindowUpdate);

class KissBufferedWriter {
public:
  explicit KissBufferedWriter(Stream &out) : _out(out), _used(0) {}

  void begin(uint8_t command) {
    put(KISS_FEND);
    put(command);
  }

  void writeEscaped(uint8_t b) {
    if (b == KISS_FEND) {
      put(KISS_FESC);
      put(KISS_TFEND);
    } else if (b == KISS_FESC) {
      put(KISS_FESC);
      put(KISS_TFESC);
    } else {
      put(b);
    }
  }

  void end() {
    put(KISS_FEND);
    flush();
  }

private:
  static constexpr size_t BUF_SIZE = 64;

  Stream &_out;
  uint8_t _buf[BUF_SIZE];
  size_t _used;

  void put(uint8_t b) {
    if (_used == BUF_SIZE) {
      flush();
    }
    _buf[_used++] = b;
  }

  void flush() {
    if (_used > 0) {
      _out.write(_buf, _used);
      _used = 0;
    }
  }
};

void sendKissFrame(uint8_t kissCommand, const uint8_t *payload, size_t len) {
  if (len > (PROTO_MTU + KV4P_VENDOR_HEADER_LEN)) {
    len = PROTO_MTU + KV4P_VENDOR_HEADER_LEN;
  }
  KissBufferedWriter writer(Serial);
  writer.begin(kissCommand);
  for (size_t i = 0; i < len; i++) {
    writer.writeEscaped(payload[i]);
  }
  writer.end();
}

void inline sendKissDataFrame(const uint8_t *ax25, size_t len) {
  if (ax25 == NULL) {
    len = 0;
  }
  if (len > PROTO_MTU) {
    len = PROTO_MTU;
  }
  sendKissFrame(KISS_CMD_DATA, ax25, len);
}

void sendKv4pVendorFrame(uint8_t kv4pCommand, const uint8_t *payload, size_t len) {
  if (payload == NULL) {
    len = 0;
  }
  if (len > PROTO_MTU) {
    len = PROTO_MTU;
  }
  KissBufferedWriter writer(Serial);
  writer.begin(KISS_CMD_SETHARDWARE);
  for (size_t i = 0; i < sizeof(KV4P_VENDOR_PREFIX); i++) {
    writer.writeEscaped(KV4P_VENDOR_PREFIX[i]);
  }
  writer.writeEscaped(KV4P_PROTOCOL_VERSION);
  writer.writeEscaped(kv4pCommand);
  for (size_t i = 0; i < len; i++) {
    writer.writeEscaped(payload[i]);
  }
  writer.end();
}

void inline sendHello(uint16_t ver, char radioModuleStatus, size_t windowSize, RfModuleType rfModuleType, float minRadioFreq, float maxRadioFreq, uint8_t features, const DeviceState &deviceState) {
  Hello params = {
    .version = {
      .ver = ver,
      .radioModuleStatus = radioModuleStatus,
      .windowSize = windowSize,
      .rfModuleType = rfModuleType,
      .minRadioFreq = minRadioFreq,
      .maxRadioFreq = maxRadioFreq,
      .features = features,
    },
    .deviceState = deviceState,
  };
  sendKv4pVendorFrame(COMMAND_HELLO, (uint8_t*) &params, sizeof(params));
}

void inline sendDeviceState(const DeviceState &state) {
  sendKv4pVendorFrame(COMMAND_DEVICE_STATE, (const uint8_t*) &state, sizeof(state));
}

void inline sendAudio(const uint8_t *data, size_t len) {
  sendKv4pVendorFrame(COMMAND_RX_AUDIO, data, len);
}

void inline sendAx25Packet(const uint8_t *data, size_t len) {
  sendKissDataFrame(data, len);
}

void inline sendWindowAck(size_t size) {
  WindowUpdate params = {
    .size = size,
  };
  sendKv4pVendorFrame(COMMAND_WINDOW_UPDATE, (uint8_t*) &params, sizeof(params));
}

typedef void (*CommandCallback)(RcvCommand command, uint8_t *params, size_t param_len);
typedef void (*Ax25Callback)(uint8_t *ax25, size_t ax25_len);

class KissParser {
public:
  KissParser(Stream &serial, CommandCallback callback, Ax25Callback ax25Callback)
    : _serial(serial), _callback(callback), _ax25Callback(ax25Callback), _frameLen(0), _encodedFrameLen(0),
      _escape(false), _dropFrame(false), _inFrame(false) {}

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
  Ax25Callback _ax25Callback;
  uint8_t _frame[KISS_MAX_FRAME_SIZE];
  size_t _frameLen;
  size_t _encodedFrameLen;
  bool _escape;
  bool _dropFrame;
  bool _inFrame;

  inline bool processByte(uint8_t b) {
    if (b == KISS_FEND) {
      _encodedFrameLen++;
      if (_frameLen > 0 && !_dropFrame) {
        processFrame();
        sendWindowAck(_encodedFrameLen);
        resetParser();
        _inFrame = true;
        _encodedFrameLen = 1;
        return true;
      }
      resetParser();
      _inFrame = true;
      _encodedFrameLen = 1;
    } else if (!_inFrame) {
      return false;
    } else if (_dropFrame) {
      _encodedFrameLen++;
      return false;
    } else if (_escape) {
      _encodedFrameLen++;
      if (b == KISS_TFEND) {
        appendByte(KISS_FEND);
      } else if (b == KISS_TFESC) {
        appendByte(KISS_FESC);
      } else {
        // Unknown KISS escape: drop this frame and wait for the next FEND.
        _dropFrame = true;
      }
      _escape = false;
    } else if (b == KISS_FESC) {
      _encodedFrameLen++;
      _escape = true;
    } else {
      _encodedFrameLen++;
      appendByte(b);
    }
    return false;
  }

  void appendByte(uint8_t b) {
    if (_frameLen >= KISS_MAX_FRAME_SIZE) {
      _dropFrame = true;
      return;
    }
    _frame[_frameLen++] = b;
  }

  void processFrame() {
    uint8_t kissCommandByte = _frame[0];
    uint8_t kissPort = kissCommandByte >> 4;
    uint8_t kissCommand = kissCommandByte & 0x0F;
    uint8_t *payload = _frame + 1;
    size_t payloadLen = _frameLen - 1;

    if (kissPort != KISS_PORT_0) {
      return;
    }
    if (kissCommand == KISS_CMD_DATA) {
      if (payloadLen > 0 && payloadLen <= PROTO_MTU) {
        _ax25Callback(payload, payloadLen);
      }
    } else if (kissCommand == KISS_CMD_SETHARDWARE) {
      processVendorFrame(payload, payloadLen);
    }
  }

  void processVendorFrame(uint8_t *payload, size_t payloadLen) {
    if (payloadLen < KV4P_VENDOR_HEADER_LEN) {
      return;
    }
    if (memcmp(payload, KV4P_VENDOR_PREFIX, sizeof(KV4P_VENDOR_PREFIX)) != 0) {
      return;
    }
    if (payload[4] != KV4P_PROTOCOL_VERSION) {
      return;
    }
    RcvCommand command = (RcvCommand)payload[5];
    _callback(command, payload + KV4P_VENDOR_HEADER_LEN, payloadLen - KV4P_VENDOR_HEADER_LEN);
  }

  void resetParser() {
    _frameLen = 0;
    _encodedFrameLen = 0;
    _escape = false;
    _dropFrame = false;
    _inFrame = false;
  }
};

// Forward declaration of handleCommands function
// This function processes incoming commands, taking a command type, parameters, and their length.
void handleCommands(RcvCommand command, uint8_t *params, size_t param_len);
void handleAx25Data(uint8_t *ax25, size_t ax25_len);

// Create a KISS parser and associate it with the existing command handler.
// DATA frames dispatch as AX.25 bytes; KV4P vendor frames dispatch by kv4pCommand.
KissParser parser(Serial, &handleCommands, &handleAx25Data);

void inline protocolLoop() {
  parser.loop();
}
