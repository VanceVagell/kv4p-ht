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
#pragma once

#include <FreeDv2400b.h>
#include <math.h>
#include <stdint.h>

class FreeDvSquelch {
public:
  static constexpr uint8_t GOOD_FRAMES_TO_OPEN = 5;
  static constexpr uint8_t BAD_FRAMES_TO_CLOSE = 10;
  static constexpr uint32_t FRAME_DURATION_MS =
      freedv2400b::TX_SAMPLES * 1000UL / freedv2400b::SAMPLE_RATE;
  static constexpr uint32_t NO_FRAME_TIMEOUT_MS =
      BAD_FRAMES_TO_CLOSE * FRAME_DURATION_MS;

  // Map calibrated FreeDV discriminator SNR onto Codec2Talkie's VHF
  // S0 through the existing Android meter's tenth bar, then encode it in
  // KV4P's SA818 RSSI wire format.
  static uint8_t snrToRssi(float snrDb) {
    static constexpr float NOISE_SNR_DB = 4.46f;
    static constexpr float S9_SNR_DB = 17.0f;
    static constexpr float IDEAL_SNR_DB = 41.91f;
    float equivalentDbm;
    if (snrDb <= NOISE_SNR_DB) {
      equivalentDbm = -147.0f;
    } else if (snrDb < S9_SNR_DB) {
      const float level =
          (snrDb - NOISE_SNR_DB) / (S9_SNR_DB - NOISE_SNR_DB);
      equivalentDbm = -147.0f + level * 54.0f;
    } else if (snrDb < IDEAL_SNR_DB) {
      const float level =
          (snrDb - S9_SNR_DB) / (IDEAL_SNR_DB - S9_SNR_DB);
      equivalentDbm = -93.0f + level * 21.0f;
    } else {
      equivalentDbm = -72.0f;
    }
    long encoded = lroundf((equivalentDbm * 10.0f + 1608.0f) / 12.0f);
    if (encoded < 0) encoded = 0;
    if (encoded > 255) encoded = 255;
    return static_cast<uint8_t>(encoded);
  }

  explicit FreeDvSquelch(uint8_t level = 4)
      : level_(clampLevel(level)), goodFrames_(0), badFrames_(0),
        open_(level_ == 0) {}

  void setLevel(uint8_t level) {
    const uint8_t clampedLevel = clampLevel(level);
    if (clampedLevel == level_) return;
    level_ = clampedLevel;
    reset();
  }

  uint8_t level() const { return level_; }
  bool open() const { return open_; }

  bool accept(const FreeDv2400bDecodeResult &result) {
    if (result.frameType != FreeDv2400bFrameType::VOICE) return false;
    if (level_ == 0) {
      open_ = true;
      return true;
    }

    static const uint8_t maxUwErrors[9] = {16, 7, 6, 5, 4, 3, 2, 1, 0};
    static const float minimumSnrDb[9] =  {-100.0f, 0.0f, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f};
    const bool good = result.synchronized && result.uniqueWordErrors <= maxUwErrors[level_] && result.discriminatorSnrDb >= minimumSnrDb[level_];
    if (good) {
      badFrames_ = 0;
      if (goodFrames_ < 255) ++goodFrames_;
      if (goodFrames_ >= GOOD_FRAMES_TO_OPEN) open_ = true;
    } else {
      goodFrames_ = 0;
      if (badFrames_ < 255) ++badFrames_;
      if (badFrames_ >= BAD_FRAMES_TO_CLOSE) open_ = false;
    }
    return open_ && good;
  }

  void reset() {
    goodFrames_ = 0;
    badFrames_ = 0;
    open_ = level_ == 0;
  }

private:
  static uint8_t clampLevel(uint8_t level) { return level > 8 ? 8 : level; }

  uint8_t level_;
  uint8_t goodFrames_;
  uint8_t badFrames_;
  bool open_;
};
