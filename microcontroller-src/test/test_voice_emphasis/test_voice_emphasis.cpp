#include <unity.h>
#include <math.h>
#include "../../kv4p_ht_esp32_wroom_32/dsp/voiceEmphasis.h"

template <typename Filter>
static float gainAt(int hz) {
  Filter filter;
  filter.setEnabled(true);
  const int n = 16000;
  float inI = 0, inQ = 0, outI = 0, outQ = 0;
  for (int i = 0; i < n; ++i) {
    const float phase = 2.0f * 3.14159265358979323846f * hz * i / 16000.0f;
    const int16_t input = (int16_t)lroundf(2000.0f * sinf(phase));
    const int16_t output = filter.process(input);
    if (i >= n / 2) {
      inI += input * cosf(phase); inQ += input * sinf(phase);
      outI += output * cosf(phase); outQ += output * sinf(phase);
    }
  }
  return hypotf(outI, outQ) / hypotf(inI, inQ);
}

void test_measured_uhf_residual_correction() {
  const int frequencies[] = {300, 500, 1000, 1500, 2500, 3000};
  const float measuredFlatTxDb[] = {-1.06f, -0.25f, 0.0f, 0.0f, -0.73f, -1.06f};
  const float measuredFlatLinkDb[] = {-0.82f, -0.08f, 0.0f, -0.44f, -2.40f, -3.00f};
  const float rxReference = gainAt<RxVoiceDeEmphasis>(1000);
  for (int i = 0; i < 6; ++i) {
    const float pre = gainAt<TxVoicePreEmphasis>(frequencies[i]);
    const float de = gainAt<RxVoiceDeEmphasis>(frequencies[i]) / rxReference;
    const float targetTxDb = 20.0f * log10f((float)frequencies[i] / 1000.0f);
    TEST_ASSERT_FLOAT_WITHIN(1.5f, targetTxDb,
        measuredFlatTxDb[i] + 20.0f * log10f(pre));
    TEST_ASSERT_FLOAT_WITHIN(1.5f, 0.0f,
        measuredFlatLinkDb[i] + 20.0f * log10f(pre * de));
  }
}

void test_voice_response() {
  const int frequencies[] = {300, 500, 1000, 1500, 2500, 3000};
  float previousPre = 0.0f;
  float previousDe = 100.0f;
  for (int hz : frequencies) {
    const float pre = gainAt<TxVoicePreEmphasis>(hz);
    const float de = gainAt<RxVoiceDeEmphasis>(hz);
    TEST_ASSERT_TRUE(pre > previousPre);
    TEST_ASSERT_TRUE(de < previousDe);
    previousPre = pre;
    previousDe = de;
  }
  TEST_ASSERT_FLOAT_WITHIN(0.02f, 1.0f, gainAt<TxVoicePreEmphasis>(1000));
  TEST_ASSERT_FLOAT_WITHIN(0.01f, 0.1385f, gainAt<RxVoiceDeEmphasis>(1000));
}

void test_rx_unity_dc_does_not_boost_static() {
  RxVoiceDeEmphasis rx;
  rx.setEnabled(true);
  uint32_t seed = 1;
  for (int i = 0; i < 16000; ++i) {
    seed = seed * 1664525u + 1013904223u;
    const int16_t input = (seed & 0x80000000u) ? 30000 : -30000;
    const int16_t output = rx.process(input);
    TEST_ASSERT_TRUE(output >= -30000 && output <= 30000);
  }
  rx.reset();
  rx.setEnabled(true);
  int16_t steady = 0;
  for (int i = 0; i < 1000; ++i) steady = rx.process(20000);
  TEST_ASSERT_INT16_WITHIN(1, 20000, steady);
}

void test_bypass_toggle_and_reset() {
  TxVoicePreEmphasis tx;
  TEST_ASSERT_EQUAL_INT16(1200, tx.process(1200));
  tx.setEnabled(true);
  int16_t last = 0;
  for (int i = 0; i < 300; ++i) last = tx.process(1200);
  TEST_ASSERT_TRUE(last < 1200);
  tx.setEnabled(false);
  for (int i = 0; i < 64; ++i) last = tx.process(1200);
  TEST_ASSERT_EQUAL_INT16(1200, last);
  tx.reset();
  TEST_ASSERT_EQUAL_INT16(1200, tx.process(1200));
}

void test_saturation_and_frame_continuity() {
  TxVoicePreEmphasis tx;
  tx.setEnabled(true);
  for (int i = 0; i < 500; ++i) tx.process(0);
  TEST_ASSERT_EQUAL_INT16(32767, tx.process(32767));
  TEST_ASSERT_EQUAL_INT16(-32768, tx.process(-32768));
  TxVoicePreEmphasis boundary;
  boundary.setEnabled(true);
  for (int i = 0; i < 248; ++i) boundary.process(0);
  // A pulse on the last sample of a 249-sample ADPCM frame must leave the
  // inverse-filter tail on the first sample of the next frame.
  TEST_ASSERT_TRUE(boundary.process(2000) > 2000);
  TEST_ASSERT_TRUE(boundary.process(0) < -4000);
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_voice_response);
  RUN_TEST(test_measured_uhf_residual_correction);
  RUN_TEST(test_rx_unity_dc_does_not_boost_static);
  RUN_TEST(test_bypass_toggle_and_reset);
  RUN_TEST(test_saturation_and_frame_continuity);
  return UNITY_END();
}
