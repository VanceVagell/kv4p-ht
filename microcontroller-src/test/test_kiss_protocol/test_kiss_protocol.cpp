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

#include <Arduino.h>
#include <unity.h>

#include "protocol.h"

static_assert(sizeof(HostDesiredState) == 22, "HostDesiredState wire size must match Android");
static_assert(sizeof(DeviceState) == 26, "DeviceState wire size must match Android");
static_assert(sizeof(Version) == 17, "Version wire size must match Android");
static_assert(sizeof(Hello) == 43, "Hello wire size must match Android");
static_assert(COMMAND_HOST_TX_AUDIO == 0x0C, "Host TX audio command id must match Android");
static_assert(COMMAND_RX_AUDIO == 0x0C, "RX audio command id must match Android");

struct CapturedCommand {
  bool called;
  RcvCommand command;
  uint8_t payload[KISS_MAX_FRAME_SIZE];
  size_t payloadLen;
};

static CapturedCommand captured;
static CapturedCommand capturedAx25;

void handleCommands(ProtocolSession &, RcvCommand command, uint8_t *params, size_t param_len) {
  captured.called = true;
  captured.command = command;
  captured.payloadLen = param_len;
  if (param_len > 0) {
    memcpy(captured.payload, params, param_len);
  }
}

void handleAx25Data(uint8_t *ax25, size_t ax25_len) {
  capturedAx25.called = true;
  capturedAx25.command = COMMAND_RCV_UNKNOWN;
  capturedAx25.payloadLen = ax25_len;
  if (ax25_len > 0) {
    memcpy(capturedAx25.payload, ax25, ax25_len);
  }
}

class FakeStream : public Stream {
public:
  FakeStream() : _len(0), _index(0), _writeLen(0) {}

  FakeStream(const uint8_t *data, size_t len) : FakeStream() {
    append(data, len);
  }

  void append(const uint8_t *data, size_t len) {
    TEST_ASSERT_LESS_OR_EQUAL(sizeof(_data) - _len, len);
    memcpy(_data + _len, data, len);
    _len += len;
  }

  int available() override {
    return _len - _index;
  }

  int read() override {
    if (_index >= _len) {
      return -1;
    }
    return _data[_index++];
  }

  int peek() override {
    if (_index >= _len) {
      return -1;
    }
    return _data[_index];
  }

  void flush() override {}

  size_t write(uint8_t b) override {
    TEST_ASSERT_LESS_THAN(sizeof(_writeBuffer), _writeLen);
    _writeBuffer[_writeLen++] = b;
    return 1;
  }

  size_t write(const uint8_t *buffer, size_t size) override {
    for (size_t i = 0; i < size; i++) {
      write(buffer[i]);
    }
    return size;
  }

  const uint8_t *written() const {
    return _writeBuffer;
  }

  size_t writtenLen() const {
    return _writeLen;
  }

private:
  uint8_t _data[KISS_MAX_FRAME_SIZE + 64];
  uint8_t _writeBuffer[(2 * (PROTO_MTU + KV4P_VENDOR_HEADER_LEN)) + 8];
  size_t _len;
  size_t _index;
  size_t _writeLen;
};

static void resetCaptured() {
  captured.called = false;
  captured.command = COMMAND_RCV_UNKNOWN;
  captured.payloadLen = 0;
  memset(captured.payload, 0, sizeof(captured.payload));
  capturedAx25.called = false;
  capturedAx25.command = COMMAND_RCV_UNKNOWN;
  capturedAx25.payloadLen = 0;
  memset(capturedAx25.payload, 0, sizeof(capturedAx25.payload));
}

static void parseBytes(const uint8_t *data, size_t len) {
  FakeStream stream(data, len);
  ProtocolSession session = { &stream, true, 0, 0 };
  KissParser parser(session, &handleCommands, &handleAx25Data);
  while (stream.available() > 0) {
    parser.loop();
  }
}

void test_data_frame_unescapes_and_dispatches_ax25() {
  resetCaptured();
  const uint8_t frame[] = {
    KISS_FEND, KISS_CMD_DATA, 0x11, KISS_FESC, KISS_TFEND, 0x22, KISS_FESC, KISS_TFESC, KISS_FEND
  };

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_FALSE(captured.called);
  TEST_ASSERT_TRUE(capturedAx25.called);
  TEST_ASSERT_EQUAL(4, capturedAx25.payloadLen);
  TEST_ASSERT_EQUAL_HEX8(0x11, capturedAx25.payload[0]);
  TEST_ASSERT_EQUAL_HEX8(KISS_FEND, capturedAx25.payload[1]);
  TEST_ASSERT_EQUAL_HEX8(0x22, capturedAx25.payload[2]);
  TEST_ASSERT_EQUAL_HEX8(KISS_FESC, capturedAx25.payload[3]);
}

void test_multiple_complete_frames_in_one_buffer() {
  resetCaptured();
  const uint8_t frames[] = {
    KISS_FEND, KISS_CMD_DATA, 0x11, 0x22, KISS_FEND,
    KISS_FEND, KISS_CMD_SETHARDWARE,
    'K', 'V', '4', 'P', KV4P_PROTOCOL_VERSION, COMMAND_HOST_DESIRED_STATE, 0x33, 0x44,
    KISS_FEND
  };

  parseBytes(frames, sizeof(frames));

  TEST_ASSERT_TRUE(capturedAx25.called);
  TEST_ASSERT_EQUAL(2, capturedAx25.payloadLen);
  TEST_ASSERT_EQUAL_HEX8(0x11, capturedAx25.payload[0]);
  TEST_ASSERT_EQUAL_HEX8(0x22, capturedAx25.payload[1]);
  TEST_ASSERT_TRUE(captured.called);
  TEST_ASSERT_EQUAL(COMMAND_HOST_DESIRED_STATE, captured.command);
  TEST_ASSERT_EQUAL(2, captured.payloadLen);
  TEST_ASSERT_EQUAL_HEX8(0x33, captured.payload[0]);
  TEST_ASSERT_EQUAL_HEX8(0x44, captured.payload[1]);
}

void test_split_frame_across_loop_calls() {
  resetCaptured();
  FakeStream stream;
  ProtocolSession session = { &stream, true, 0, 0 };
  KissParser parser(session, &handleCommands, &handleAx25Data);
  const uint8_t part1[] = { KISS_FEND, KISS_CMD_DATA, 0x11 };
  const uint8_t part2[] = { 0x22, KISS_FEND };

  stream.append(part1, sizeof(part1));
  parser.loop();

  TEST_ASSERT_FALSE(capturedAx25.called);

  stream.append(part2, sizeof(part2));
  while (stream.available() > 0) {
    parser.loop();
  }

  TEST_ASSERT_TRUE(capturedAx25.called);
  TEST_ASSERT_EQUAL(2, capturedAx25.payloadLen);
  TEST_ASSERT_EQUAL_HEX8(0x11, capturedAx25.payload[0]);
  TEST_ASSERT_EQUAL_HEX8(0x22, capturedAx25.payload[1]);
}

void test_non_zero_kiss_port_is_ignored() {
  resetCaptured();
  const uint8_t frame[] = {
    KISS_FEND, (uint8_t)(0x10 | KISS_CMD_DATA), 0x11, 0x22, KISS_FEND
  };

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_FALSE(captured.called);
  TEST_ASSERT_FALSE(capturedAx25.called);
}

void test_unknown_kiss_command_is_ignored() {
  resetCaptured();
  const uint8_t frame[] = {
    KISS_FEND, 0x02, 0x11, 0x22, KISS_FEND
  };

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_FALSE(captured.called);
  TEST_ASSERT_FALSE(capturedAx25.called);
}

void test_multiple_fend_bytes_are_ignored() {
  resetCaptured();
  const uint8_t frame[] = {
    KISS_FEND, KISS_FEND, KISS_CMD_SETHARDWARE,
    'K', 'V', '4', 'P', KV4P_PROTOCOL_VERSION, COMMAND_HOST_DESIRED_STATE,
    KISS_FEND
  };

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_TRUE(captured.called);
  TEST_ASSERT_EQUAL(COMMAND_HOST_DESIRED_STATE, captured.command);
  TEST_ASSERT_EQUAL(0, captured.payloadLen);
}

void test_vendor_frame_validates_prefix_and_version() {
  resetCaptured();
  const uint8_t invalidPrefix[] = {
    KISS_FEND, KISS_CMD_SETHARDWARE,
    'B', 'A', 'D', '!', KV4P_PROTOCOL_VERSION, COMMAND_HOST_DESIRED_STATE,
    KISS_FEND
  };

  parseBytes(invalidPrefix, sizeof(invalidPrefix));

  TEST_ASSERT_FALSE(captured.called);

  resetCaptured();
  const uint8_t invalidVersion[] = {
    KISS_FEND, KISS_CMD_SETHARDWARE,
    'K', 'V', '4', 'P', 0x02, COMMAND_HOST_DESIRED_STATE,
    KISS_FEND
  };

  parseBytes(invalidVersion, sizeof(invalidVersion));

  TEST_ASSERT_FALSE(captured.called);
}

void test_over_mtu_data_frame_is_dropped() {
  resetCaptured();
  uint8_t frame[PROTO_MTU + 4];
  frame[0] = KISS_FEND;
  frame[1] = KISS_CMD_DATA;
  memset(frame + 2, 0x55, PROTO_MTU + 1);
  frame[sizeof(frame) - 1] = KISS_FEND;

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_FALSE(captured.called);
}

void test_unknown_escape_drops_frame_and_recovers() {
  resetCaptured();
  const uint8_t frames[] = {
    KISS_FEND, KISS_CMD_DATA, 0x11, KISS_FESC, 0x99, 0x22, KISS_FEND,
    KISS_FEND, KISS_CMD_DATA, 0x33, 0x44, KISS_FEND
  };

  parseBytes(frames, sizeof(frames));

  TEST_ASSERT_FALSE(captured.called);
  TEST_ASSERT_TRUE(capturedAx25.called);
  TEST_ASSERT_EQUAL(2, capturedAx25.payloadLen);
  TEST_ASSERT_EQUAL_HEX8(0x33, capturedAx25.payload[0]);
  TEST_ASSERT_EQUAL_HEX8(0x44, capturedAx25.payload[1]);
}

void test_oversized_frame_is_dropped_and_recovers() {
  resetCaptured();
  uint8_t frame[KISS_MAX_FRAME_SIZE + 10];
  frame[0] = KISS_FEND;
  frame[1] = KISS_CMD_DATA;
  memset(frame + 2, 0x55, KISS_MAX_FRAME_SIZE + 5);
  frame[sizeof(frame) - 4] = KISS_FEND;
  frame[sizeof(frame) - 3] = KISS_CMD_DATA;
  frame[sizeof(frame) - 2] = 0x66;
  frame[sizeof(frame) - 1] = KISS_FEND;

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_FALSE(captured.called);
  TEST_ASSERT_TRUE(capturedAx25.called);
  TEST_ASSERT_EQUAL(1, capturedAx25.payloadLen);
  TEST_ASSERT_EQUAL_HEX8(0x66, capturedAx25.payload[0]);
}

void test_send_kiss_data_frame_escapes_fend_and_fesc() {
  FakeStream stream;
  const uint8_t payload[] = { 0x11, KISS_FEND, 0x22, KISS_FESC };
  const uint8_t expected[] = {
    KISS_FEND, KISS_CMD_DATA,
    0x11, KISS_FESC, KISS_TFEND, 0x22, KISS_FESC, KISS_TFESC,
    KISS_FEND
  };

  sendKissDataFrame(stream, payload, sizeof(payload));

  TEST_ASSERT_EQUAL(sizeof(expected), stream.writtenLen());
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, stream.written(), sizeof(expected));
}

void test_send_kiss_data_frame_broadcasts_to_connected_secondary_stream() {
  FakeStream secondary;
  ProtocolSession oldBtSession = protocolBtSession;
  protocolBtSession = { &secondary, true, 0, 0 };

  const uint8_t payload[] = { 0x11, 0x22 };
  const uint8_t expected[] = {
    KISS_FEND, KISS_CMD_DATA, 0x11, 0x22, KISS_FEND
  };

  sendKissDataFrame(payload, sizeof(payload));

  TEST_ASSERT_EQUAL(sizeof(expected), secondary.writtenLen());
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, secondary.written(), sizeof(expected));

  protocolBtSession = oldBtSession;
}

void test_send_kv4p_vendor_frame_escapes_payload() {
  FakeStream stream;
  const uint8_t payload[] = { 0x11, KISS_FEND, KISS_FESC };
  const uint8_t expected[] = {
    KISS_FEND, KISS_CMD_SETHARDWARE,
    'K', 'V', '4', 'P', KV4P_PROTOCOL_VERSION, COMMAND_HOST_TX_AUDIO,
    0x11, KISS_FESC, KISS_TFEND, KISS_FESC, KISS_TFESC,
    KISS_FEND
  };

  sendKv4pVendorFrame(stream, COMMAND_HOST_TX_AUDIO, payload, sizeof(payload));

  TEST_ASSERT_EQUAL(sizeof(expected), stream.writtenLen());
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, stream.written(), sizeof(expected));
}

void test_send_audio_routes_only_to_rx_audio_open_sessions() {
  FakeStream usb;
  FakeStream secondary;
  ProtocolSession oldUsbSession = protocolUsbSession;
  ProtocolSession oldBtSession = protocolBtSession;
  const uint8_t payload[] = { 0x55 };
  const uint8_t expected[] = {
    KISS_FEND, KISS_CMD_SETHARDWARE,
    'K', 'V', '4', 'P', KV4P_PROTOCOL_VERSION, COMMAND_RX_AUDIO,
    0x55, KISS_FEND
  };

  protocolUsbSession.stream = &usb;
  protocolUsbSession.connected = true;
  protocolUsbSession.flags = HOST_STATE_RX_AUDIO_OPEN;
  protocolBtSession = { &secondary, true, 0, 0 };

  sendAudio(payload, sizeof(payload));

  TEST_ASSERT_EQUAL(sizeof(expected), usb.writtenLen());
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, usb.written(), sizeof(expected));
  TEST_ASSERT_EQUAL(0, secondary.writtenLen());

  protocolUsbSession.flags = 0;
  protocolBtSession.flags = HOST_STATE_RX_AUDIO_OPEN;
  sendAudio(payload, sizeof(payload));

  TEST_ASSERT_EQUAL(sizeof(expected), secondary.writtenLen());
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, secondary.written(), sizeof(expected));

  protocolUsbSession = oldUsbSession;
  protocolBtSession = oldBtSession;
}

void test_parser_ack_is_written_to_input_stream() {
  resetCaptured();
  const uint8_t frame[] = {
    KISS_FEND, KISS_CMD_DATA, 0x11, KISS_FEND
  };
  FakeStream stream(frame, sizeof(frame));
  ProtocolSession session = { &stream, true, 0, 0 };
  KissParser parser(session, &handleCommands, &handleAx25Data);
  const uint8_t expectedAck[] = {
    KISS_FEND, KISS_CMD_SETHARDWARE,
    'K', 'V', '4', 'P', KV4P_PROTOCOL_VERSION, COMMAND_WINDOW_UPDATE,
    0x04, 0x00, 0x00, 0x00,
    KISS_FEND
  };

  while (stream.available() > 0) {
    parser.loop();
  }

  TEST_ASSERT_TRUE(capturedAx25.called);
  TEST_ASSERT_EQUAL(sizeof(expectedAck), stream.writtenLen());
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expectedAck, stream.written(), sizeof(expectedAck));
}

static int runKissProtocolTests() {
  UNITY_BEGIN();
  RUN_TEST(test_data_frame_unescapes_and_dispatches_ax25);
  RUN_TEST(test_multiple_complete_frames_in_one_buffer);
  RUN_TEST(test_split_frame_across_loop_calls);
  RUN_TEST(test_non_zero_kiss_port_is_ignored);
  RUN_TEST(test_unknown_kiss_command_is_ignored);
  RUN_TEST(test_multiple_fend_bytes_are_ignored);
  RUN_TEST(test_vendor_frame_validates_prefix_and_version);
  RUN_TEST(test_over_mtu_data_frame_is_dropped);
  RUN_TEST(test_unknown_escape_drops_frame_and_recovers);
  RUN_TEST(test_oversized_frame_is_dropped_and_recovers);
  RUN_TEST(test_send_kiss_data_frame_escapes_fend_and_fesc);
  RUN_TEST(test_send_kiss_data_frame_broadcasts_to_connected_secondary_stream);
  RUN_TEST(test_send_kv4p_vendor_frame_escapes_payload);
  RUN_TEST(test_send_audio_routes_only_to_rx_audio_open_sessions);
  RUN_TEST(test_parser_ack_is_written_to_input_stream);
  return UNITY_END();
}

#ifdef PIO_NATIVE_TEST
int main(int, char **) {
  return runKissProtocolTests();
}
#else
void setup() {
  runKissProtocolTests();
}

void loop() {}
#endif
