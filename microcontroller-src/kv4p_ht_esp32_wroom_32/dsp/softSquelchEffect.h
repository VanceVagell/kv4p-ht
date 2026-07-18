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

#include <Arduino.h>
#include <AudioTools.h>
#include <AfskDemodulator.h>
#include <math.h>
#include <string.h>
#include "../globals.h"

#define ZCR_DECAY_TIME 0.100f  // seconds
#define SQ_CLOSE_DELAY 0.250f  // seconds

class SoftSquelchEffect : public AudioEffect {
public:
  SoftSquelchEffect(uint32_t sampleRate = AUDIO_SAMPLE_RATE, float zcrTauSec = ZCR_DECAY_TIME, float closeDelaySec = SQ_CLOSE_DELAY)
    : sampleRate(sampleRate == 0 ? AUDIO_SAMPLE_RATE : sampleRate),
      zcrTauSec(zcrTauSec),
      closeDelaySec(closeDelaySec) {
    zcrAlpha = 1.0f - expf(-1.0f / ((float)this->sampleRate * this->zcrTauSec));
    initBpf();
    resetState();
  }

  SoftSquelchEffect *clone() override {
    SoftSquelchEffect *copy = new SoftSquelchEffect(sampleRate, zcrTauSec, closeDelaySec);
    copy->setActive(active());
    copy->setId(id());
    return copy;
  }

  void resetState() {
    memset(bpfState, 0, sizeof(bpfState));
    previousOutsideSample = 0.0f;
    if (isBypassed()) {
      iirZcr = 0.0f;
      aboveThresholdSamples = 0;
      setSoftSqOpen(true);
      return;
    }
    iirZcr = resetClosedZcr();
    aboveThresholdSamples = closeDelaySamples();
    setSoftSqOpen(false);
  }

  void setHardwareSquelched(bool squelched) {
    hardwareSquelched = squelched;
  }

  effect_t process(effect_t input) override {
    if (!active() || hardwareSquelched) {
      return input;
    }

    float x = (float)input / 32768.0f;
    float filtered = bpf.filter(x);

    bool crossing = false;
    if (filtered > deadband || filtered < -deadband) {
      crossing = ((previousOutsideSample < -deadband && filtered > deadband) || (previousOutsideSample > deadband && filtered < -deadband));
      previousOutsideSample = filtered;
    }

    float crossingInstantRate = crossing ? (float)sampleRate : 0.0f;
    iirZcr += zcrAlpha * (crossingInstantRate - iirZcr);
    updateDecision();
    return input;
  }

  float getIirZcr() const {
    return iirZcr;
  }

  bool isSoftOpen() const {
    if (isBypassed()) {
      return true;
    }
    return softSqOpen;
  }

  void setDeadbandLevel(uint8_t level) {
    if (level > 8) {
      level = 8;
    }
    deadbandLevel = level;
    deadband = deadbandForLevel(level);
    resetState();
  }

  uint8_t getDeadbandLevel() const {
    return deadbandLevel;
  }

  float getDeadband() const {
    return deadband;
  }

private:
  static constexpr float SOFT_SQUELCH_LOW_HZ = 200.0f;
  static constexpr float SOFT_SQUELCH_HIGH_HZ = 500.0f;
  static constexpr float SOFT_SQUELCH_THRESHOLD_ZCR = 30.0f;
  static constexpr int SOFT_SQUELCH_BPF_TAPS = 33;
  static constexpr int SOFT_SQUELCH_BPF_STATE_LEN = SOFT_SQUELCH_BPF_TAPS + 4;

  uint32_t sampleRate;
  float zcrTauSec;
  float closeDelaySec;
  afsk::dsp::Esp32Fir bpf;
  float bpfTaps[SOFT_SQUELCH_BPF_TAPS] AUDIO_DSP_ALIGN16;
  float bpfState[SOFT_SQUELCH_BPF_STATE_LEN] AUDIO_DSP_ALIGN16;
  float previousOutsideSample = 0.0f;
  float zcrAlpha = 0.0f;
  float iirZcr = 0.0f;
  uint32_t aboveThresholdSamples = 0;
  bool softSqOpen = false;
  bool hardwareSquelched = false;
  uint8_t deadbandLevel = 0;
  float deadband = 0.45f;

  static float deadbandForLevel(uint8_t level) {
    if (level > 8) {
      level = 8;
    }
    return 0.45f - ((float)level / 8.0f) * (0.45f - 0.08f);
  }

  bool isBypassed() const {
    return deadbandLevel == 0;
  }

  void initBpf() {
    float sampleRateFloat = (float)sampleRate;
    float centerHz = sqrtf(SOFT_SQUELCH_LOW_HZ * SOFT_SQUELCH_HIGH_HZ);
    afsk::dsp::afsk_design_bandpass_kaiser(bpfTaps, SOFT_SQUELCH_BPF_TAPS,
                                           SOFT_SQUELCH_LOW_HZ, SOFT_SQUELCH_HIGH_HZ,
                                           sampleRateFloat, 30.0f);
    normalizeBpfAt(centerHz, sampleRateFloat);
    bpf.init(bpfTaps, SOFT_SQUELCH_BPF_TAPS, bpfTaps, bpfState, SOFT_SQUELCH_BPF_STATE_LEN);
  }

  uint32_t closeDelaySamples() const {
    return (uint32_t)((float)sampleRate * closeDelaySec);
  }

  float resetClosedZcr() const {
    if (zcrTauSec <= 0.0f || closeDelaySec <= 0.0f) {
      return SOFT_SQUELCH_THRESHOLD_ZCR;
    }
    return SOFT_SQUELCH_THRESHOLD_ZCR * expf(closeDelaySec / zcrTauSec);
  }

  void normalizeBpfAt(float frequencyHz, float sampleRate) {
    float omega = 2.0f * PI * frequencyHz / sampleRate;
    float real = 0.0f;
    float imag = 0.0f;
    for (int i = 0; i < SOFT_SQUELCH_BPF_TAPS; i++) {
      real += bpfTaps[i] * cosf(omega * i);
      imag -= bpfTaps[i] * sinf(omega * i);
    }
    float gain = sqrtf((real * real) + (imag * imag));
    if (gain <= 0.0f) {
      return;
    }
    float invGain = 1.0f / gain;
    for (int i = 0; i < SOFT_SQUELCH_BPF_TAPS; i++) {
      bpfTaps[i] *= invGain;
    }
  }

  void updateDecision() {
    if (iirZcr < SOFT_SQUELCH_THRESHOLD_ZCR) {
      aboveThresholdSamples = 0;
      setSoftSqOpen(true);
      return;
    }

    if (aboveThresholdSamples < sampleRate) {
      aboveThresholdSamples++;
    }
    if (aboveThresholdSamples >= closeDelaySamples()) {
      setSoftSqOpen(false);
    }
  }

  void setSoftSqOpen(bool open) {
    softSqOpen = open;
  }
};
