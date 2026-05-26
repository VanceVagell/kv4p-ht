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

#include "voiceResampler.h"

static_assert(VOICE_FRAME_SAMPLES_8K == 160, "20 ms at 8 kHz must be 160 samples");
static_assert(VOICE_FRAME_SAMPLES_48K == 960, "20 ms at 48 kHz must be 960 samples");
static_assert(VOICE_FRAME_BYTES == 160, "G.711 20 ms frame must be 160 bytes");

void test_upsampler_output_length() {
  int16_t in[VOICE_FRAME_SAMPLES_8K] = {};
  int16_t out[VOICE_FRAME_SAMPLES_48K] = {};
  TEST_ASSERT_EQUAL(VOICE_FRAME_SAMPLES_48K,
                    upsample8kTo48kLinear(in, VOICE_FRAME_SAMPLES_8K, out, VOICE_FRAME_SAMPLES_48K));
}

void test_upsampler_interpolates() {
  int16_t in[] = {0, 60};
  int16_t out[12] = {};
  TEST_ASSERT_EQUAL(12, upsample8kTo48kLinear(in, 2, out, 12));
  TEST_ASSERT_EQUAL_INT16(0, out[0]);
  TEST_ASSERT_EQUAL_INT16(10, out[1]);
  TEST_ASSERT_EQUAL_INT16(50, out[5]);
  TEST_ASSERT_EQUAL_INT16(60, out[6]);
  TEST_ASSERT_EQUAL_INT16(60, out[11]);
}

void test_decimator_output_length() {
  int16_t in[VOICE_FRAME_SAMPLES_48K] = {};
  int16_t out[VOICE_FRAME_SAMPLES_8K] = {};
  VoiceFirDecimator decimator;
  TEST_ASSERT_TRUE(decimator.begin());
  TEST_ASSERT_EQUAL(VOICE_FRAME_SAMPLES_8K,
                    decimator.process(in, VOICE_FRAME_SAMPLES_48K, out, VOICE_FRAME_SAMPLES_8K));
}

void test_decimator_coefficients_have_expected_dc_gain() {
  int32_t sum = 0;
  for (size_t i = 0; i < VOICE_DECIMATOR_TAPS; i++) {
    sum += voiceDecimatorCoeffs[i];
  }
  TEST_ASSERT_EQUAL_INT32((int32_t)((1 << VOICE_DECIMATOR_COEFF_SHIFT) * VOICE_DECIMATOR_GAIN), sum);
}

void test_decimator_expected_gain_for_dc_after_warmup() {
  static constexpr int16_t kInputLevel = 10000;
  static constexpr int16_t kExpectedLevel = (int16_t)(kInputLevel * VOICE_DECIMATOR_GAIN);
  int16_t in[VOICE_FRAME_SAMPLES_48K];
  int16_t out[VOICE_FRAME_SAMPLES_8K] = {};
  VoiceFirDecimator decimator;

  for (size_t i = 0; i < VOICE_FRAME_SAMPLES_48K; i++) {
    in[i] = kInputLevel;
  }

  TEST_ASSERT_TRUE(decimator.begin());
  for (int frame = 0; frame < 4; frame++) {
    TEST_ASSERT_EQUAL(VOICE_FRAME_SAMPLES_8K,
                      decimator.process(in, VOICE_FRAME_SAMPLES_48K, out, VOICE_FRAME_SAMPLES_8K));
  }

  TEST_ASSERT_INT16_WITHIN(2, kExpectedLevel, out[VOICE_FRAME_SAMPLES_8K - 1]);
}

void setup() {
  UNITY_BEGIN();
  RUN_TEST(test_upsampler_output_length);
  RUN_TEST(test_upsampler_interpolates);
  RUN_TEST(test_decimator_output_length);
  RUN_TEST(test_decimator_coefficients_have_expected_dc_gain);
  RUN_TEST(test_decimator_expected_gain_for_dc_after_warmup);
  UNITY_END();
}

void loop() {}
