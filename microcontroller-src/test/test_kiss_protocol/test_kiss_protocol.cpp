#include <Arduino.h>
#include <unity.h>

#include "protocol.h"

struct CapturedCommand {
  bool called;
  RcvCommand command;
  uint8_t payload[KISS_MAX_FRAME_SIZE];
  size_t payloadLen;
};

static CapturedCommand captured;

void handleCommands(RcvCommand command, uint8_t *params, size_t param_len) {
  captured.called = true;
  captured.command = command;
  captured.payloadLen = param_len;
  if (param_len > 0) {
    memcpy(captured.payload, params, param_len);
  }
}

class FakeStream : public Stream {
public:
  FakeStream(const uint8_t *data, size_t len) : _data(data), _len(len), _index(0) {}

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

  size_t write(uint8_t) override {
    return 1;
  }

private:
  const uint8_t *_data;
  size_t _len;
  size_t _index;
};

static void resetCaptured() {
  captured.called = false;
  captured.command = COMMAND_RCV_UNKNOWN;
  captured.payloadLen = 0;
  memset(captured.payload, 0, sizeof(captured.payload));
}

static void parseBytes(const uint8_t *data, size_t len) {
  FakeStream stream(data, len);
  KissParser parser(stream, &handleCommands);
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

  TEST_ASSERT_TRUE(captured.called);
  TEST_ASSERT_EQUAL(COMMAND_HOST_TX_AX25, captured.command);
  TEST_ASSERT_EQUAL(4, captured.payloadLen);
  TEST_ASSERT_EQUAL_HEX8(0x11, captured.payload[0]);
  TEST_ASSERT_EQUAL_HEX8(KISS_FEND, captured.payload[1]);
  TEST_ASSERT_EQUAL_HEX8(0x22, captured.payload[2]);
  TEST_ASSERT_EQUAL_HEX8(KISS_FESC, captured.payload[3]);
}

void test_multiple_fend_bytes_are_ignored() {
  resetCaptured();
  const uint8_t frame[] = {
    KISS_FEND, KISS_FEND, KISS_CMD_SETHARDWARE,
    'K', 'V', '4', 'P', KV4P_PROTOCOL_VERSION, COMMAND_HOST_PTT_DOWN,
    KISS_FEND
  };

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_TRUE(captured.called);
  TEST_ASSERT_EQUAL(COMMAND_HOST_PTT_DOWN, captured.command);
  TEST_ASSERT_EQUAL(0, captured.payloadLen);
}

void test_vendor_frame_validates_prefix_and_version() {
  resetCaptured();
  const uint8_t invalidPrefix[] = {
    KISS_FEND, KISS_CMD_SETHARDWARE,
    'B', 'A', 'D', '!', KV4P_PROTOCOL_VERSION, COMMAND_HOST_PTT_DOWN,
    KISS_FEND
  };

  parseBytes(invalidPrefix, sizeof(invalidPrefix));

  TEST_ASSERT_FALSE(captured.called);

  resetCaptured();
  const uint8_t invalidVersion[] = {
    KISS_FEND, KISS_CMD_SETHARDWARE,
    'K', 'V', '4', 'P', 0x02, COMMAND_HOST_PTT_DOWN,
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

void test_unknown_escape_drops_frame() {
  resetCaptured();
  const uint8_t frame[] = {
    KISS_FEND, KISS_CMD_DATA, 0x11, KISS_FESC, 0x99, 0x22, KISS_FEND
  };

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_FALSE(captured.called);
}

void setup() {
  UNITY_BEGIN();
  RUN_TEST(test_data_frame_unescapes_and_dispatches_ax25);
  RUN_TEST(test_multiple_fend_bytes_are_ignored);
  RUN_TEST(test_vendor_frame_validates_prefix_and_version);
  RUN_TEST(test_over_mtu_data_frame_is_dropped);
  RUN_TEST(test_unknown_escape_drops_frame);
  UNITY_END();
}

void loop() {}
