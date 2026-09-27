#include <math.h>
#include <vector>

#include <Arduino.h>
#include <unity.h>

#include "dsp/softSquelchEffect.h"
#include "AfskModulator.h"

namespace {

static std::vector<float> afskSamples;

void captureAfsk(const float *samples, size_t count) {
  afskSamples.insert(afskSamples.end(), samples, samples + count);
}

void processSamples(SoftSquelchEffect &squelch, const std::vector<float> &samples) {
  for (float sample : samples) {
    squelch.process((int16_t)lroundf(sample * 32767.0f));
  }
}

void test_soft_squelch_opens_for_afsk_and_closes_for_hf_noise() {
  SoftSquelchEffect squelch(AUDIO_SAMPLE_RATE, ZCR_DECAY_TIME, SQ_CLOSE_DELAY);
  squelch.setActive(true);
  squelch.setHardwareSquelched(false);
  squelch.setDeadbandLevel(1); // level zero intentionally bypasses soft squelch

  afskSamples.clear();
  AfskModulator mod(AUDIO_SAMPLE_RATE, captureAfsk, 38, 3);
  const uint8_t payload[128] = {};
  float buffer[256];
  mod.modulate(payload, sizeof(payload), buffer, sizeof(buffer) / sizeof(buffer[0]));
  processSamples(squelch, afskSamples);
  TEST_ASSERT_TRUE_MESSAGE(squelch.isSoftOpen(), "Clean 1200/2200 Hz AFSK should open soft squelch");
  TEST_ASSERT_TRUE_MESSAGE(squelch.isCarrierDetected(), "Clean AFSK should assert the raw carrier decision");

  SoftSquelchEffect ctcssSquelch(AUDIO_SAMPLE_RATE, ZCR_DECAY_TIME, SQ_CLOSE_DELAY);
  ctcssSquelch.setActive(true);
  ctcssSquelch.setHardwareSquelched(false);
  ctcssSquelch.setDeadbandLevel(1);
  ctcssSquelch.setCtcssTone(1);
  processSamples(ctcssSquelch, afskSamples);
  TEST_ASSERT_FALSE_MESSAGE(ctcssSquelch.isSoftOpen(), "AFSK without the selected CTCSS tone should remain muted");
  TEST_ASSERT_TRUE_MESSAGE(ctcssSquelch.isCarrierDetected(), "CTCSS must not suppress the CSMA carrier decision");

  std::vector<float> highFrequencyNoise(AUDIO_SAMPLE_RATE / 2);
  for (size_t i = 0; i < highFrequencyNoise.size(); i++) {
    highFrequencyNoise[i] = 0.8f * sinf(2.0f * (float)M_PI * 3750.0f * (float)i / AUDIO_SAMPLE_RATE);
  }
  processSamples(squelch, highFrequencyNoise);
  TEST_ASSERT_FALSE_MESSAGE(squelch.isSoftOpen(), "3.5-4 kHz energy should close soft squelch");
}

} // namespace

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_soft_squelch_opens_for_afsk_and_closes_for_hf_noise);
  return UNITY_END();
}
