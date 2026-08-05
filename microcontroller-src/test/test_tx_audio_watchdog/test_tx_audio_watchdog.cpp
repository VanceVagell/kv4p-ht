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

#include "utils.h"

void test_timeout_is_derived_from_fifty_audio_frames() {
  TEST_ASSERT_EQUAL_UINT16(50, TX_AUDIO_TIMEOUT_FRAMES);
  TEST_ASSERT_EQUAL_UINT32(778, TX_AUDIO_TIMEOUT_MS);
}

void test_watchdog_remains_active_before_timeout() {
  TxWatchDog watchdog;
  watchdog.reset(0);
  TEST_ASSERT_FALSE(watchdog.expired(TX_AUDIO_TIMEOUT_MS - 1));
}

void test_watchdog_expires_at_timeout() {
  TxWatchDog watchdog;
  watchdog.reset(0);
  TEST_ASSERT_TRUE(watchdog.expired(TX_AUDIO_TIMEOUT_MS));
}

void test_audio_frame_restarts_timeout() {
  TxWatchDog watchdog;
  watchdog.reset(0);
  watchdog.onFrame(TX_AUDIO_TIMEOUT_MS - 1);
  TEST_ASSERT_FALSE(watchdog.expired(TX_AUDIO_TIMEOUT_MS));
  TEST_ASSERT_TRUE(watchdog.expired((TX_AUDIO_TIMEOUT_MS * 2) - 1));
}

void test_timeout_handles_millis_wraparound() {
  TxWatchDog watchdog;
  uint32_t lastFrame = UINT32_MAX - 100;
  uint32_t now = TX_AUDIO_TIMEOUT_MS - 101;
  watchdog.reset(lastFrame);
  TEST_ASSERT_TRUE(watchdog.expired(now));
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_timeout_is_derived_from_fifty_audio_frames);
  RUN_TEST(test_watchdog_remains_active_before_timeout);
  RUN_TEST(test_watchdog_expires_at_timeout);
  RUN_TEST(test_audio_frame_restarts_timeout);
  RUN_TEST(test_timeout_handles_millis_wraparound);
  return UNITY_END();
}
