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

static_assert(VOICE_FRAME_SAMPLES_WIRE == 249, "128-byte mono IMA ADPCM block must decode to 249 samples");
static_assert(VOICE_FRAME_SAMPLES_48K == 747, "249 samples at 16 kHz must map to 747 samples at 48 kHz");
static_assert(VOICE_FRAME_BYTES == 128, "4-bit ADPCM frame must be 128 bytes");
static constexpr float kExpectedDecimatorDcGain = 1.0f;

void test_upsampler_output_length() {
  int16_t in[VOICE_FRAME_SAMPLES_WIRE] = {};
  int16_t out[VOICE_FRAME_SAMPLES_48K] = {};
  TEST_ASSERT_EQUAL(VOICE_FRAME_SAMPLES_48K,
                    upsampleWireTo48kLinear(in, VOICE_FRAME_SAMPLES_WIRE, out, VOICE_FRAME_SAMPLES_48K));
}

void test_upsampler_interpolates() {
  int16_t in[] = {0, 60};
  int16_t out[6] = {};
  TEST_ASSERT_EQUAL(6, upsampleWireTo48kLinear(in, 2, out, 6));
  TEST_ASSERT_EQUAL_INT16(0, out[0]);
  TEST_ASSERT_EQUAL_INT16(20, out[1]);
  TEST_ASSERT_EQUAL_INT16(40, out[2]);
  TEST_ASSERT_EQUAL_INT16(60, out[3]);
  TEST_ASSERT_EQUAL_INT16(60, out[5]);
}

void test_decimator_output_length() {
  int16_t in[VOICE_FRAME_SAMPLES_48K] = {};
  int16_t out[VOICE_FRAME_SAMPLES_WIRE] = {};
  VoiceFirDecimator decimator;
  TEST_ASSERT_TRUE(decimator.begin());
  TEST_ASSERT_EQUAL(VOICE_FRAME_SAMPLES_WIRE,
                    decimator.process(in, VOICE_FRAME_SAMPLES_48K, out, VOICE_FRAME_SAMPLES_WIRE));
}

void test_decimator_coefficients_have_expected_dc_gain() {
  float coeffs[VOICE_DECIMATOR_TAPS];
  float sum = 0.0f;
  voiceDesignDecimatorCoeffs(coeffs);
  for (size_t i = 0; i < VOICE_DECIMATOR_TAPS; i++) {
    sum += coeffs[i];
  }
  TEST_ASSERT_FLOAT_WITHIN(0.0001f, kExpectedDecimatorDcGain, sum);
}

void test_decimator_expected_gain_for_dc_after_warmup() {
  static constexpr int16_t kInputLevel = 10000;
  static constexpr int16_t kExpectedLevel = (int16_t)(kInputLevel * kExpectedDecimatorDcGain);
  int16_t in[VOICE_FRAME_SAMPLES_48K];
  int16_t out[VOICE_FRAME_SAMPLES_WIRE] = {};
  VoiceFirDecimator decimator;

  for (size_t i = 0; i < VOICE_FRAME_SAMPLES_48K; i++) {
    in[i] = kInputLevel;
  }

  TEST_ASSERT_TRUE(decimator.begin());
  for (int frame = 0; frame < 4; frame++) {
    TEST_ASSERT_EQUAL(VOICE_FRAME_SAMPLES_WIRE,
                      decimator.process(in, VOICE_FRAME_SAMPLES_48K, out, VOICE_FRAME_SAMPLES_WIRE));
  }

  TEST_ASSERT_INT16_WITHIN(2, kExpectedLevel, out[VOICE_FRAME_SAMPLES_WIRE - 1]);
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
