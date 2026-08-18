#pragma once

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <AfskDemodulator.h>

class CtcssDetector {
public:
  explicit CtcssDetector(uint32_t sampleRate) : sampleRate(sampleRate) {
    setToneIndex(0);
  }

  void setToneIndex(uint8_t index) {
    toneIndex = index <= TONE_COUNT ? index : 0;
    targetHz = toneIndex == 0 ? 0.0f : toneFrequency(toneIndex);
    reset();
  }

  uint8_t getToneIndex() const { return toneIndex; }
  float getTargetHz() const { return targetHz; }
  bool isDetected() const { return toneIndex == 0 || detected; }

  void reset() {
    sampleCount = 0;
    signalEnergy = 0.0;
    targetReal = 0.0;
    targetImag = 0.0;
    oscillator.init(sampleRate == 0 ? 1.0f : (float)sampleRate, targetHz);
    detected = toneIndex == 0;
  }

  void process(int16_t sample) {
    if (toneIndex == 0 || sampleRate == 0) {
      return;
    }

    float x = (float)sample / 32768.0f;
    targetReal += x * oscillator.cos();
    targetImag -= x * oscillator.sin();
    signalEnergy += x * x;
    oscillator.next();

    sampleCount++;
    if (sampleCount >= detectionWindowSamples()) {
      float toneEnergy = (targetReal * targetReal) + (targetImag * targetImag);
      float normalized = signalEnergy > 0.0f ? (2.0f * toneEnergy) / ((float)sampleCount * signalEnergy) : 0.0f;
      detected = signalEnergy >= MINIMUM_WINDOW_ENERGY && normalized >= MINIMUM_TONE_ENERGY_RATIO;
      sampleCount = 0;
      signalEnergy = 0.0;
      targetReal = 0.0;
      targetImag = 0.0;
      oscillator.init((float)sampleRate, targetHz);
    }
  }

  static float toneFrequency(uint8_t index) {
    static const float frequencies[TONE_COUNT] = {
      67.0f, 71.9f, 74.4f, 77.0f, 79.7f, 82.5f, 85.4f, 88.5f, 91.5f, 94.8f,
      97.4f, 100.0f, 103.5f, 107.2f, 110.9f, 114.8f, 118.8f, 123.0f, 127.3f, 131.8f,
      136.5f, 141.3f, 146.2f, 151.4f, 156.7f, 162.2f, 167.9f, 173.8f, 179.9f, 186.2f,
      192.8f, 203.5f, 210.7f, 218.1f, 225.7f, 233.6f, 241.8f, 250.3f
    };
    return index >= 1 && index <= TONE_COUNT ? frequencies[index - 1] : 0.0f;
  }

private:
  static constexpr uint8_t TONE_COUNT = 38;
  static constexpr float DETECTION_WINDOW_SECONDS = 0.4f;
  static constexpr float MINIMUM_WINDOW_ENERGY = 0.01f;
  static constexpr float MINIMUM_TONE_ENERGY_RATIO = 0.35f;

  uint32_t sampleRate;
  uint8_t toneIndex = 0;
  float targetHz = 0.0f;
  afsk::dsp::DdsOsc oscillator;
  uint32_t sampleCount = 0;
  float signalEnergy = 0.0f;
  float targetReal = 0.0f;
  float targetImag = 0.0f;
  bool detected = true;

  uint32_t detectionWindowSamples() const {
    uint32_t samples = (uint32_t)((float)sampleRate * DETECTION_WINDOW_SECONDS);
    return samples == 0 ? 1 : samples;
  }
};
