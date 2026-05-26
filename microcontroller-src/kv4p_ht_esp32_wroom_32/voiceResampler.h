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
#pragma once

#include <Arduino.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "globals.h"
#include <esp_dsp.h>

#ifndef PIO_NATIVE_TEST
#include <AudioTools.h>
#endif

#define VOICE_DSP_ALIGN16 __attribute__((aligned(16)))

static constexpr int16_t VOICE_DECIMATOR_TAPS = 96;
static constexpr int16_t VOICE_DECIMATOR_COEFF_SHIFT = 15;
static constexpr int16_t VOICE_DECIMATOR_S16_SHIFT = 0;
static constexpr float VOICE_DECIMATOR_GAIN = 0.5f;

// 96-tap Hamming-windowed sinc, fc=3400 Hz at fs=48000 Hz, 0.5 DC gain.
// Coefficients are Q15. For dsps_fird_s16, ESP-DSP applies (shift - 15),
// so Q15 coefficients use shift=0 to produce a signed PCM16 result.
static constexpr int16_t voiceFloatToQ15(float coeff) {
  return (coeff * VOICE_DECIMATOR_GAIN) >= 0.999969482421875f ? 32767 :
         (coeff * VOICE_DECIMATOR_GAIN) <= -1.0f ? -32768 :
         (int16_t)((coeff * VOICE_DECIMATOR_GAIN) * (float)(1 << VOICE_DECIMATOR_COEFF_SHIFT) +
                   ((coeff * VOICE_DECIMATOR_GAIN) >= 0.0f ? 0.5f : -0.5f));
}

static int16_t voiceDecimatorCoeffs[VOICE_DECIMATOR_TAPS] VOICE_DSP_ALIGN16 = {
  voiceFloatToQ15(0.0003967285f), voiceFloatToQ15(0.0005187988f), voiceFloatToQ15(0.0005798340f), voiceFloatToQ15(0.0005187988f), voiceFloatToQ15(0.0003356934f), voiceFloatToQ15(0.0000610352f), voiceFloatToQ15(-0.0003356934f), voiceFloatToQ15(-0.0007324219f),
  voiceFloatToQ15(-0.0010986328f), voiceFloatToQ15(-0.0013122559f), voiceFloatToQ15(-0.0012512207f), voiceFloatToQ15(-0.0008850098f), voiceFloatToQ15(-0.0001831055f), voiceFloatToQ15(0.0007629395f), voiceFloatToQ15(0.0018005371f), voiceFloatToQ15(0.0026550293f),
  voiceFloatToQ15(0.0031433105f), voiceFloatToQ15(0.0030212402f), voiceFloatToQ15(0.0021362305f), voiceFloatToQ15(0.0005187988f), voiceFloatToQ15(-0.0015869141f), voiceFloatToQ15(-0.0038146973f), voiceFloatToQ15(-0.0057067871f), voiceFloatToQ15(-0.0066833496f),
  voiceFloatToQ15(-0.0063781738f), voiceFloatToQ15(-0.0045471191f), voiceFloatToQ15(-0.0012817383f), voiceFloatToQ15(0.0029296875f), voiceFloatToQ15(0.0073852539f), voiceFloatToQ15(0.0111083984f), voiceFloatToQ15(0.0131225586f), voiceFloatToQ15(0.0126342773f),
  voiceFloatToQ15(0.0092163086f), voiceFloatToQ15(0.0029907227f), voiceFloatToQ15(-0.0052795410f), voiceFloatToQ15(-0.0142822266f), voiceFloatToQ15(-0.0221557617f), voiceFloatToQ15(-0.0270080566f), voiceFloatToQ15(-0.0270080566f), voiceFloatToQ15(-0.0207824707f),
  voiceFloatToQ15(-0.0078125000f), voiceFloatToQ15(0.0115356445f), voiceFloatToQ15(0.0358276367f), voiceFloatToQ15(0.0628356934f), voiceFloatToQ15(0.0896606445f), voiceFloatToQ15(0.1132812500f), voiceFloatToQ15(0.1308593750f), voiceFloatToQ15(0.1403503418f),
  voiceFloatToQ15(0.1402282715f), voiceFloatToQ15(0.1308593750f), voiceFloatToQ15(0.1132812500f), voiceFloatToQ15(0.0896606445f), voiceFloatToQ15(0.0628356934f), voiceFloatToQ15(0.0358276367f), voiceFloatToQ15(0.0115356445f), voiceFloatToQ15(-0.0078125000f),
  voiceFloatToQ15(-0.0207824707f), voiceFloatToQ15(-0.0270080566f), voiceFloatToQ15(-0.0270080566f), voiceFloatToQ15(-0.0221557617f), voiceFloatToQ15(-0.0142822266f), voiceFloatToQ15(-0.0052795410f), voiceFloatToQ15(0.0029907227f), voiceFloatToQ15(0.0092163086f),
  voiceFloatToQ15(0.0126342773f), voiceFloatToQ15(0.0131225586f), voiceFloatToQ15(0.0111083984f), voiceFloatToQ15(0.0073852539f), voiceFloatToQ15(0.0029296875f), voiceFloatToQ15(-0.0012817383f), voiceFloatToQ15(-0.0045471191f), voiceFloatToQ15(-0.0063781738f),
  voiceFloatToQ15(-0.0066833496f), voiceFloatToQ15(-0.0057067871f), voiceFloatToQ15(-0.0038146973f), voiceFloatToQ15(-0.0015869141f), voiceFloatToQ15(0.0005187988f), voiceFloatToQ15(0.0021362305f), voiceFloatToQ15(0.0030212402f), voiceFloatToQ15(0.0031433105f),
  voiceFloatToQ15(0.0026550293f), voiceFloatToQ15(0.0018005371f), voiceFloatToQ15(0.0007629395f), voiceFloatToQ15(-0.0001831055f), voiceFloatToQ15(-0.0008850098f), voiceFloatToQ15(-0.0012512207f), voiceFloatToQ15(-0.0013122559f), voiceFloatToQ15(-0.0010986328f),
  voiceFloatToQ15(-0.0007324219f), voiceFloatToQ15(-0.0003356934f), voiceFloatToQ15(0.0000610352f), voiceFloatToQ15(0.0003356934f), voiceFloatToQ15(0.0005187988f), voiceFloatToQ15(0.0005798340f), voiceFloatToQ15(0.0005187988f), voiceFloatToQ15(0.0003967285f),
};

inline size_t upsample8kTo48kLinear(const int16_t *input, size_t inputSamples, int16_t *output, size_t outputCapacity) {
  size_t needed = inputSamples * VOICE_RESAMPLE_RATIO;
  if (outputCapacity < needed) {
    return 0;
  }
  for (size_t i = 0; i < inputSamples; i++) {
    int32_t current = input[i];
    int32_t next = (i + 1 < inputSamples) ? input[i + 1] : current;
    for (size_t phase = 0; phase < VOICE_RESAMPLE_RATIO; phase++) {
      output[(i * VOICE_RESAMPLE_RATIO) + phase] =
        (int16_t)(current + (((next - current) * (int32_t)phase) / VOICE_RESAMPLE_RATIO));
    }
  }
  return needed;
}

class VoiceFirDecimator {
public:
  bool begin() {
    memset(delay, 0, sizeof(delay));
    return dsps_fird_init_s16(&fir, voiceDecimatorCoeffs, delay, VOICE_DECIMATOR_TAPS,
                              VOICE_RESAMPLE_RATIO, 0, VOICE_DECIMATOR_S16_SHIFT) == ESP_OK;
  }

  size_t process(const int16_t *input, size_t inputSamples, int16_t *output, size_t outputCapacity) {
    size_t outputSamples = inputSamples / VOICE_RESAMPLE_RATIO;
    if (outputSamples > outputCapacity) {
      outputSamples = outputCapacity;
    }
    int32_t written = dsps_fird_s16(&fir, input, output, (int32_t)outputSamples);
    if (written < 0) {
      return 0;
    }
    return (size_t)written;
  }

private:
  int16_t delay[VOICE_DECIMATOR_TAPS] VOICE_DSP_ALIGN16;
  fir_s16_t fir = {};
};

#ifndef PIO_NATIVE_TEST

class VoiceDownsampleConverter : public BaseConverter {
public:
  bool begin() {
    captureSamples = 0;
    hasPendingByte = false;
    return decimator.begin();
  }

  size_t convert(uint8_t *src, size_t size) override {
    size_t produced = 0;
    size_t consumed = 0;
    while (consumed < size) {
      uint8_t b = src[consumed++];
      if (!hasPendingByte) {
        pendingByte = b;
        hasPendingByte = true;
        continue;
      }
      capture48k[captureSamples++] = (int16_t)((uint16_t)b << 8 | pendingByte);
      hasPendingByte = false;
      if (captureSamples == VOICE_FRAME_SAMPLES_48K) {
        size_t frameBytes = decimateFrame(src + produced);
        captureSamples = 0;
        produced += frameBytes;
      }
    }
    return produced;
  }

private:
  VoiceFirDecimator decimator;
  int16_t capture48k[VOICE_FRAME_SAMPLES_48K] VOICE_DSP_ALIGN16;
  size_t captureSamples = 0;
  uint8_t pendingByte = 0;
  bool hasPendingByte = false;

  size_t decimateFrame(uint8_t *dst) {
    int16_t *pcm8k = (int16_t *)dst;
    size_t samples8k = decimator.process(capture48k, VOICE_FRAME_SAMPLES_48K, pcm8k, VOICE_FRAME_SAMPLES_8K);
    return samples8k * sizeof(int16_t);
  }
};

class VoiceUpsampleOutput : public AudioOutput {
public:
  explicit VoiceUpsampleOutput(Print &out) : out(&out) {}

  void setOutput(Print &target) {
    out = &target;
  }

  bool begin() override {
    AudioOutput::begin();
    inputSamples = 0;
    hasPendingByte = false;
    return true;
  }

  size_t write(const uint8_t *data, size_t len) override {
    size_t consumed = 0;
    while (consumed < len) {
      uint8_t b = data[consumed++];
      if (!hasPendingByte) {
        pendingByte = b;
        hasPendingByte = true;
        continue;
      }
      pcm8k[inputSamples++] = (int16_t)((uint16_t)b << 8 | pendingByte);
      hasPendingByte = false;
      if (inputSamples == VOICE_FRAME_SAMPLES_8K) {
        flushFrame();
      }
    }
    return len;
  }

private:
  Print *out;
  int16_t pcm8k[VOICE_FRAME_SAMPLES_8K] VOICE_DSP_ALIGN16;
  int16_t pcm48k[VOICE_FRAME_SAMPLES_48K] VOICE_DSP_ALIGN16;
  size_t inputSamples = 0;
  uint8_t pendingByte = 0;
  bool hasPendingByte = false;

  void flushFrame() {
    size_t samples48k = upsample8kTo48kLinear(pcm8k, inputSamples, pcm48k, VOICE_FRAME_SAMPLES_48K);
    if (out && samples48k > 0) {
      out->write((uint8_t *)pcm48k, samples48k * sizeof(int16_t));
    }
    inputSamples = 0;
  }
};

#endif
