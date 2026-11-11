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
    highPassPreviousInput = 0.0f;
    previousHighPass = 0.0f;
    limiterGain = 1.0f;
  }

  int16_t process(int16_t sample) {
    // High-pass before de-emphasis removes sub-voice static without the
    // existing 65-tap low-stop FIR's severe attenuation at 300 Hz.
    const float x = (float)sample;
    const float highPass = HIGH_PASS_ALPHA * (previousHighPass + x - highPassPreviousInput);
    highPassPreviousInput = x;
    previousHighPass = highPass;
    float output = processWithDry(highPass, x);
    if (wetLevel() == 0.0f) {
      limiterGain = 1.0f;
      return sample;
    }
    const float magnitude = fabsf(output);
    const float neededGain = magnitude > LIMIT ? LIMIT / magnitude : 1.0f;
    // Immediate attack prevents hard clipping; an 80 ms release avoids
    // rapid gain modulation on loud unsquelched noise.
    limiterGain = fminf(neededGain, limiterGain + RELEASE * (1.0f - limiterGain));
    return clampToInt16(output * limiterGain);
  }

private:
  static constexpr float HIGH_PASS_ALPHA = 0.96149116f; // 100 Hz at 16 kHz
  static constexpr float LIMIT = 30000.0f;
  static constexpr float RELEASE = 0.000780945f; // 80 ms at 16 kHz
  float highPassPreviousInput = 0.0f;
  float previousHighPass = 0.0f;
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
