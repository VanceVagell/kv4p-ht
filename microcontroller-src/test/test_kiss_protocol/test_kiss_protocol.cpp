/*
KV4P-HT (see http://kv4p.com)
Copyright (C) 2026 Vance Vagell

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

#include "ax25TxScheduler.h"
#include "protocol.h"

static bool freeDvEnabledForTest = false;
bool freeDv2400bEnabled() { return freeDvEnabledForTest; }

static_assert(sizeof(HostDesiredState) == 22, "HostDesiredState wire size must match Android");
static_assert(sizeof(DeviceState) == 26, "DeviceState wire size must match Android");
static_assert(sizeof(Version) == 17, "Version wire size must match Android");
static_assert(sizeof(Hello) == 43, "Hello wire size must match Android");
static_assert(COMMAND_HOST_TX_AUDIO == 0x0C, "Host TX audio command id must match Android");
static_assert(COMMAND_RX_AUDIO == 0x0C, "RX audio command id must match Android");
static_assert(COMMAND_HOST_TX_DIGITAL == 0x0E, "Host digital command id must match Android");
static_assert(COMMAND_HOST_TX_AX25 == 0x0F, "Host AX.25 override command id must match Android");
static_assert(COMMAND_RX_DIGITAL == 0x0E, "RX digital command id must match Android");

struct CapturedCommand {
  bool called;
  RcvCommand command;
  uint8_t payload[KISS_MAX_FRAME_SIZE];
  size_t payloadLen;
};

static CapturedCommand captured;
static CapturedCommand capturedAx25;
static bool capturedKissParameter;
static uint8_t capturedKissCommand;
static uint8_t capturedKissValue;

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

void handleKissParameter(uint8_t command, uint8_t value) {
  capturedKissParameter = true;
  capturedKissCommand = command;
  capturedKissValue = value;
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
  capturedKissParameter = false;
  capturedKissCommand = 0;
  capturedKissValue = 0;
}

static void parseBytes(const uint8_t *data, size_t len) {
  FakeStream stream(data, len);
  ProtocolSession session = { &stream, true, 0, 0 };
  KissParser parser(session, &handleCommands, &handleAx25Data, &handleKissParameter);
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

void test_txdelay_frame_dispatches_kiss_parameter() {
  resetCaptured();
  const uint8_t frame[] = {
    KISS_FEND, KISS_CMD_TXDELAY, 75, KISS_FEND
  };

  parseBytes(frame, sizeof(frame));

  TEST_ASSERT_TRUE(capturedKissParameter);
  TEST_ASSERT_EQUAL_HEX8(KISS_CMD_TXDELAY, capturedKissCommand);
  TEST_ASSERT_EQUAL_UINT8(75, capturedKissValue);
  TEST_ASSERT_FALSE(captured.called);
  TEST_ASSERT_FALSE(capturedAx25.called);
}

void test_persist_and_slottime_frames_dispatch_kiss_parameters() {
  resetCaptured();
  const uint8_t persist[] = {KISS_FEND, KISS_CMD_PERSIST, 42, KISS_FEND};
  parseBytes(persist, sizeof(persist));
  TEST_ASSERT_TRUE(capturedKissParameter);
  TEST_ASSERT_EQUAL_HEX8(KISS_CMD_PERSIST, capturedKissCommand);
  TEST_ASSERT_EQUAL_UINT8(42, capturedKissValue);
  resetCaptured();
  const uint8_t slotTime[] = {KISS_FEND, KISS_CMD_SLOTTIME, 9, KISS_FEND};
  parseBytes(slotTime, sizeof(slotTime));
  TEST_ASSERT_TRUE(capturedKissParameter);
  TEST_ASSERT_EQUAL_HEX8(KISS_CMD_SLOTTIME, capturedKissCommand);
  TEST_ASSERT_EQUAL_UINT8(9, capturedKissValue);
}

void test_ax25_scheduler_holds_two_copied_frames_in_fifo_order() {
  Ax25TxScheduler scheduler;
  uint8_t first[] = {0x11, 0x22};
  const uint8_t second[] = {0x33};

  TEST_ASSERT_TRUE(scheduler.enqueue(first, sizeof(first)));
  first[0] = 0x44;
  TEST_ASSERT_TRUE(scheduler.enqueue(second, sizeof(second)));
  TEST_ASSERT_EQUAL(2, scheduler.count());
  TEST_ASSERT_EQUAL(sizeof(first), scheduler.head()->len);
  TEST_ASSERT_EQUAL_HEX8(0x11, scheduler.head()->data[0]);

  scheduler.complete();
  TEST_ASSERT_EQUAL(1, scheduler.count());
  TEST_ASSERT_EQUAL_HEX8(0x33, scheduler.head()->data[0]);
  TEST_ASSERT_TRUE(scheduler.enqueue(second, sizeof(second)));
  TEST_ASSERT_FALSE(scheduler.enqueue(second, sizeof(second)));
}

void test_ax25_scheduler_limits_jobs_to_maximum_aprs_frame() {
  Ax25TxScheduler scheduler;
  static uint8_t maximumFrame[AX25_MAX_KISS_DATA_LEN] = {};
  static uint8_t oversizedFrame[AX25_MAX_KISS_DATA_LEN + 1] = {};

  TEST_ASSERT_TRUE(scheduler.enqueue(maximumFrame, sizeof(maximumFrame)));
  scheduler.complete();
  TEST_ASSERT_FALSE(scheduler.enqueue(oversizedFrame, sizeof(oversizedFrame)));
}

void test_ax25_scheduler_tests_persistence_immediately_when_channel_clears() {
  Ax25TxScheduler scheduler;
  const uint8_t frame[] = {0x11};
  TEST_ASSERT_TRUE(scheduler.enqueue(frame, sizeof(frame)));

  scheduler.setSlotTime(10);
  scheduler.setPersist(63);
  TEST_ASSERT_FALSE(scheduler.ready(0, false, 0));
  TEST_ASSERT_FALSE(scheduler.ready(100, true, 64));
  TEST_ASSERT_FALSE(scheduler.ready(199, true, 0));
  TEST_ASSERT_TRUE(scheduler.ready(200, true, 63));
}

void test_ax25_scheduler_restarts_defer_when_channel_becomes_busy() {
  Ax25TxScheduler scheduler;
  const uint8_t frame[] = {0x11};
  TEST_ASSERT_TRUE(scheduler.enqueue(frame, sizeof(frame)));

  scheduler.setSlotTime(10);
  TEST_ASSERT_FALSE(scheduler.ready(0, true, 100));
  TEST_ASSERT_FALSE(scheduler.ready(50, false, 100));
  TEST_ASSERT_TRUE(scheduler.ready(60, true, 0));
}

void test_ax25_scheduler_does_not_restart_head_backoff_when_second_frame_arrives() {
  Ax25TxScheduler scheduler;
  const uint8_t first[] = {0x11};
  const uint8_t second[] = {0x22};
  TEST_ASSERT_TRUE(scheduler.enqueue(first, sizeof(first)));
  scheduler.setSlotTime(10);
  scheduler.setPersist(63);

  TEST_ASSERT_FALSE(scheduler.ready(0, true, 64));
  TEST_ASSERT_TRUE(scheduler.enqueue(second, sizeof(second)));
  TEST_ASSERT_FALSE(scheduler.ready(99, true, 0));
  TEST_ASSERT_TRUE(scheduler.ready(100, true, 63));
}

void test_ax25_scheduler_uses_kiss_txdelay_units() {
  Ax25TxScheduler scheduler;

  TEST_ASSERT_EQUAL_UINT16(650, scheduler.txDelayMs());
  scheduler.setTxDelay(25);
  TEST_ASSERT_EQUAL_UINT16(250, scheduler.txDelayMs());
}

void test_ax25_scheduler_retains_frequency_override_with_job() {
  Ax25TxScheduler scheduler;
  const uint8_t frame[] = {0x11};
  Ax25TxOverride txOverride = {.freqTx = 146.520f, .bw = 1, .ctcssTx = 0};
  TEST_ASSERT_TRUE(scheduler.enqueue(frame, sizeof(frame), &txOverride));
  TEST_ASSERT_TRUE(scheduler.head()->hasTxOverride);
  TEST_ASSERT_FLOAT_WITHIN(0.001f, 146.520f, scheduler.head()->txOverride.freqTx);
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

void test_oversized_ax25_data_frame_is_dropped() {
  resetCaptured();
  uint8_t frame[AX25_MAX_KISS_DATA_LEN + 4];
  frame[0] = KISS_FEND;
  frame[1] = KISS_CMD_DATA;
  memset(frame + 2, 0x55, AX25_MAX_KISS_DATA_LEN + 1);
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

void test_global_freedv_mode_selects_digital_routing() {
  FakeStream usb;
  ProtocolSession oldUsbSession = protocolUsbSession;
  const uint8_t payload[] = { 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x70 };

  protocolUsbSession.stream = &usb;
  protocolUsbSession.connected = true;
  protocolUsbSession.flags = HOST_STATE_RX_AUDIO_OPEN;
  freeDvEnabledForTest = true;

  sendAudio(payload, sizeof(payload));
  TEST_ASSERT_EQUAL(0, usb.writtenLen());
  sendDigitalFrame(payload, sizeof(payload));
  TEST_ASSERT_GREATER_THAN(0, usb.writtenLen());

  freeDvEnabledForTest = false;
  protocolUsbSession = oldUsbSession;
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
  RUN_TEST(test_txdelay_frame_dispatches_kiss_parameter);
  RUN_TEST(test_persist_and_slottime_frames_dispatch_kiss_parameters);
  RUN_TEST(test_ax25_scheduler_holds_two_copied_frames_in_fifo_order);
  RUN_TEST(test_ax25_scheduler_limits_jobs_to_maximum_aprs_frame);
  RUN_TEST(test_ax25_scheduler_tests_persistence_immediately_when_channel_clears);
  RUN_TEST(test_ax25_scheduler_restarts_defer_when_channel_becomes_busy);
  RUN_TEST(test_ax25_scheduler_does_not_restart_head_backoff_when_second_frame_arrives);
  RUN_TEST(test_ax25_scheduler_uses_kiss_txdelay_units);
  RUN_TEST(test_ax25_scheduler_retains_frequency_override_with_job);
  RUN_TEST(test_multiple_complete_frames_in_one_buffer);
  RUN_TEST(test_split_frame_across_loop_calls);
  RUN_TEST(test_non_zero_kiss_port_is_ignored);
  RUN_TEST(test_unknown_kiss_command_is_ignored);
  RUN_TEST(test_multiple_fend_bytes_are_ignored);
  RUN_TEST(test_vendor_frame_validates_prefix_and_version);
  RUN_TEST(test_oversized_ax25_data_frame_is_dropped);
  RUN_TEST(test_unknown_escape_drops_frame_and_recovers);
  RUN_TEST(test_oversized_frame_is_dropped_and_recovers);
  RUN_TEST(test_send_kiss_data_frame_escapes_fend_and_fesc);
  RUN_TEST(test_send_kiss_data_frame_broadcasts_to_connected_secondary_stream);
  RUN_TEST(test_send_kv4p_vendor_frame_escapes_payload);
  RUN_TEST(test_send_audio_routes_only_to_rx_audio_open_sessions);
  RUN_TEST(test_global_freedv_mode_selects_digital_routing);
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
