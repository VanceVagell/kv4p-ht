#pragma once

#include <math.h>
#include <stdint.h>

// First-order voice sections at the 16 kHz ADPCM PCM rate. Their relative
// responses are fitted to the measured 446 MHz TX/RX link response. A narrow
// RX high-pass and peak limiter contain static without reducing voice level.
// The same provisional coefficients are used for both RF module types.
class FirstOrderIIR {
public:
  void reset() {
    previousInput = 0.0f;
    previousOutput = 0.0f;
    wet = 0.0f;
    enabled = false;
  }

  void setEnabled(bool on) { enabled = on; }

  int16_t process(int16_t sample) {
    return clampToInt16(processWithDry((float)sample, (float)sample));
  }

protected:
  FirstOrderIIR(float b0, float b1, float pole)
      : b0(b0), b1(b1), pole(pole) { reset(); }

  float processWithDry(float x, float dry) {
    const float filtered = b0 * x + b1 * previousInput + pole * previousOutput;
    previousInput = x;
    previousOutput = filtered;
    const float target = enabled ? 1.0f : 0.0f;
    // A 4 ms blend avoids a discontinuity when the host changes voice mode.
    if (wet < target) wet = fminf(target, wet + 1.0f / 64.0f);
    if (wet > target) wet = fmaxf(target, wet - 1.0f / 64.0f);
    return dry + wet * (filtered - dry);
  }

  float wetLevel() const { return wet; }

  static int16_t clampToInt16(float sample) {
    if (sample >= 32767.0f) return 32767;
    if (sample <= -32768.0f) return -32768;
    return (int16_t)lroundf(sample);
  }

private:
  const float b0;
  const float b1;
  const float pole;
  bool enabled;
  float wet;
  float previousInput;
  float previousOutput;
};

class RxVoiceDeEmphasis : public FirstOrderIIR {
public:
  RxVoiceDeEmphasis()
      : FirstOrderIIR(0.55857897f, -0.19756946f, 0.95000000f) { reset(); }

  void reset() {
    FirstOrderIIR::reset();
    highPassState1 = 0.0f;
    highPassState2 = 0.0f;
    limiterGain = 1.0f;
  }

  int16_t process(int16_t sample) {
    // A 180 Hz Butterworth high-pass suppresses the sub-300 Hz gain hump while retaining over 94% amplitude at 300 Hz.
    const float x = (float)sample;
    const float highPass = HP_B0 * x + highPassState1;
    highPassState1 = HP_B1 * x - HP_A1 * highPass + highPassState2;
    highPassState2 = HP_B2 * x - HP_A2 * highPass;
    float output = processWithDry(highPass, x);
    if (wetLevel() == 0.0f) {
      limiterGain = 1.0f;
      return sample;
    }
    const float magnitude = fabsf(output);
    const float neededGain = magnitude > LIMIT ? LIMIT / magnitude : 1.0f;
    // Immediate attack prevents hard clipping; an 80 ms release avoids
    // rapid gain modulation on loud unsquelched noise.
    limiterGain = fminf(neededGain, limiterGain + LIMITER_RELEASE_PER_SAMPLE * (1.0f - limiterGain));
    return clampToInt16(output * limiterGain);
  }

private:
  // Second-order Butterworth high-pass, 180 Hz at 16 kHz.
  static constexpr float HP_B0 = 0.95124542f;
  static constexpr float HP_B1 = -1.90249084f;
  static constexpr float HP_B2 = 0.95124542f;
  static constexpr float HP_A1 = -1.90011242f;
  static constexpr float HP_A2 = 0.90486927f;
  static constexpr float LIMIT = 30000.0f;
  static constexpr float LIMITER_RELEASE_PER_SAMPLE = 0.000780945f; // 80 ms at 16 kHz
  float highPassState1 = 0.0f;
  float highPassState2 = 0.0f;
  float limiterGain = 1.0f;
};

class TxVoicePreEmphasis : public FirstOrderIIR {
public:
  TxVoicePreEmphasis()
      : FirstOrderIIR(2.75512206f, -2.60071912f, -0.06011509f) {}
};

#ifndef PIO_NATIVE_TEST
#include <AudioTools.h>

class TxVoicePreEmphasisEffect : public audio_tools::AudioEffect {
public:
  TxVoicePreEmphasisEffect() { setActive(false); }
  audio_tools::effect_t process(audio_tools::effect_t input) override {
    return filter.process((int16_t)input);
  }
  TxVoicePreEmphasisEffect *clone() override { return new TxVoicePreEmphasisEffect(*this); }
  void configure() {
    filter.reset();
    filter.setEnabled(active());
  }
  void setActive(bool enabled) override {
    AudioEffect::setActive(enabled);
    filter.setEnabled(enabled);
  }

private:
  TxVoicePreEmphasis filter;
};
#endif
