#include <unity.h>

#include "freeDvSquelch.h"

static FreeDv2400bDecodeResult voiceResult(bool good = true) {
  return {true, good, FreeDv2400bFrameType::VOICE,
          static_cast<uint8_t>(good ? 0 : 16), 0.0f,
          good ? 10.0f : -10.0f, 0.0f};
}

void test_level_is_clamped_to_eight() {
  FreeDvSquelch squelch(255);
  TEST_ASSERT_EQUAL_UINT8(8, squelch.level());
}

void test_no_frame_timeout_matches_close_hysteresis() {
  TEST_ASSERT_EQUAL_UINT32(
      FreeDvSquelch::BAD_FRAMES_TO_CLOSE * 40,
      FreeDvSquelch::NO_FRAME_TIMEOUT_MS);
}

void test_snr_maps_to_codec2talkie_equivalent_rssi() {
  TEST_ASSERT_EQUAL_UINT8(12, FreeDvSquelch::snrToRssi(4.46f));
  TEST_ASSERT_EQUAL_UINT8(57, FreeDvSquelch::snrToRssi(17.0f));
  TEST_ASSERT_EQUAL_UINT8(74, FreeDvSquelch::snrToRssi(41.91f));
  TEST_ASSERT_EQUAL_UINT8(12, FreeDvSquelch::snrToRssi(-100.0f));
  TEST_ASSERT_EQUAL_UINT8(74, FreeDvSquelch::snrToRssi(300.0f));
}

void test_level_zero_accepts_every_voice_frame() {
  FreeDvSquelch squelch(0);
  auto badVoice = voiceResult(false);
  TEST_ASSERT_TRUE(squelch.open());
  TEST_ASSERT_TRUE(squelch.accept(badVoice));

  badVoice.frameType = FreeDv2400bFrameType::DATA;
  TEST_ASSERT_FALSE(squelch.accept(badVoice));
}

void test_squelch_opens_after_required_good_frames() {
  FreeDvSquelch squelch(4);
  const auto good = voiceResult();

  for (uint8_t i = 1; i < FreeDvSquelch::GOOD_FRAMES_TO_OPEN; ++i) {
    TEST_ASSERT_FALSE(squelch.accept(good));
  }
  TEST_ASSERT_TRUE(squelch.accept(good));
  TEST_ASSERT_TRUE(squelch.open());
}

void test_changing_from_bypass_resets_open_state() {
  FreeDvSquelch squelch(0);
  TEST_ASSERT_TRUE(squelch.open());

  squelch.setLevel(4);
  TEST_ASSERT_FALSE(squelch.open());
  TEST_ASSERT_FALSE(squelch.accept(voiceResult()));
}

void test_squelch_closes_and_requires_reacquisition_after_bad_frames() {
  FreeDvSquelch squelch(4);
  const auto good = voiceResult();
  const auto bad = voiceResult(false);

  for (uint8_t i = 0; i < FreeDvSquelch::GOOD_FRAMES_TO_OPEN; ++i)
    squelch.accept(good);
  for (uint8_t i = 0; i < FreeDvSquelch::BAD_FRAMES_TO_CLOSE; ++i)
    TEST_ASSERT_FALSE(squelch.accept(bad));

  TEST_ASSERT_FALSE(squelch.open());
  TEST_ASSERT_FALSE(squelch.accept(good));
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_level_is_clamped_to_eight);
  RUN_TEST(test_no_frame_timeout_matches_close_hysteresis);
  RUN_TEST(test_snr_maps_to_codec2talkie_equivalent_rssi);
  RUN_TEST(test_level_zero_accepts_every_voice_frame);
  RUN_TEST(test_squelch_opens_after_required_good_frames);
  RUN_TEST(test_changing_from_bypass_resets_open_state);
  RUN_TEST(test_squelch_closes_and_requires_reacquisition_after_bad_frames);
  return UNITY_END();
}
