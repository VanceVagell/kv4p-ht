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
#include <math.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "globals.h"
#include <AfskDemodulator.h>

#ifndef PIO_NATIVE_TEST
#include <AudioTools.h>
#endif

#define VOICE_DSP_ALIGN16 __attribute__((aligned(16)))

static constexpr int16_t VOICE_DECIMATOR_TAPS = 65;
static constexpr int16_t VOICE_DECIMATOR_STATE_LEN = VOICE_DECIMATOR_TAPS + 4;
static constexpr float VOICE_DECIMATOR_CUTOFF_HZ = 6800.0f;

inline void voiceDesignDecimatorCoeffs(float *coeffs) {
  afsk::dsp::afsk_design_lowpass_hamming(coeffs, VOICE_DECIMATOR_TAPS, VOICE_DECIMATOR_CUTOFF_HZ, AUDIO_SAMPLE_RATE);
}

inline size_t upsampleWireTo48kLinear(const int16_t *input, size_t inputSamples, int16_t *output, size_t outputCapacity) {
  size_t needed = inputSamples * VOICE_RESAMPLE_RATIO;
  if (outputCapacity < needed) {
    return 0;
  }
  for (size_t i = 0; i < inputSamples; i++) {
    int32_t current = input[i];
    int32_t next = (i + 1 < inputSamples) ? input[i + 1] : current;
    for (size_t phase = 0; phase < VOICE_RESAMPLE_RATIO; phase++) {
      output[(i * VOICE_RESAMPLE_RATIO) + phase] = (int16_t)(current + (((next - current) * (int32_t)phase) / VOICE_RESAMPLE_RATIO));
    }
  }
  return needed;
}

class VoiceFirDecimator {
public:
  bool begin() {
    voiceDesignDecimatorCoeffs(coeffs);
    fir.initDecim(coeffs, VOICE_DECIMATOR_TAPS, coeffs, delay, VOICE_DECIMATOR_STATE_LEN, VOICE_RESAMPLE_RATIO);
    return fir.len == VOICE_DECIMATOR_TAPS;
  }

  size_t process(const int16_t *input, size_t inputSamples, int16_t *output, size_t outputCapacity) {
    size_t outputSamples = inputSamples / VOICE_RESAMPLE_RATIO;
    if (outputSamples > outputCapacity) {
      outputSamples = outputCapacity;
    }
    for (size_t i = 0; i < inputSamples; i++) {
      inputFloat[i] = (float)input[i];
    }
    int32_t written = dsps_fird_f32(&fir.fir, inputFloat, outputFloat, (int32_t)outputSamples);
    if (written < 0) {
      return 0;
    }
    for (int32_t i = 0; i < written; i++) {
      output[i] = clampFloatToInt16(outputFloat[i]);
    }
    return (size_t)written;
  }

private:
  static int16_t clampFloatToInt16(float sample) {
    if (sample > 32767.0f) {
      return 32767;
    }
    if (sample < -32768.0f) {
      return -32768;
    }
    return (int16_t)lroundf(sample);
  }

  afsk::dsp::Esp32Fir fir;
  float coeffs[VOICE_DECIMATOR_TAPS] VOICE_DSP_ALIGN16;
  float delay[VOICE_DECIMATOR_STATE_LEN] VOICE_DSP_ALIGN16;
  float inputFloat[VOICE_FRAME_SAMPLES_48K] VOICE_DSP_ALIGN16;
  float outputFloat[VOICE_FRAME_SAMPLES_WIRE] VOICE_DSP_ALIGN16;
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
    int16_t *pcmWire = (int16_t *)dst;
    size_t samplesWire = decimator.process(capture48k, VOICE_FRAME_SAMPLES_48K, pcmWire, VOICE_FRAME_SAMPLES_WIRE);
    return samplesWire * sizeof(int16_t);
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
      pcmWire[inputSamples++] = (int16_t)((uint16_t)b << 8 | pendingByte);
      hasPendingByte = false;
      if (inputSamples == VOICE_FRAME_SAMPLES_WIRE) {
        flushFrame();
      }
    }
    return len;
  }

private:
  Print *out;
  int16_t pcmWire[VOICE_FRAME_SAMPLES_WIRE] VOICE_DSP_ALIGN16;
  int16_t pcm48k[VOICE_FRAME_SAMPLES_48K] VOICE_DSP_ALIGN16;
  size_t inputSamples = 0;
  uint8_t pendingByte = 0;
  bool hasPendingByte = false;

  void flushFrame() {
    size_t samples48k = upsampleWireTo48kLinear(pcmWire, inputSamples, pcm48k, VOICE_FRAME_SAMPLES_48K);
    if (out && samples48k > 0) {
      out->write((uint8_t *)pcm48k, samples48k * sizeof(int16_t));
    }
    inputSamples = 0;
  }
};

#endif
