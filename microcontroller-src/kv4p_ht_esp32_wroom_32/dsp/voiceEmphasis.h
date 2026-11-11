#pragma once

#include <math.h>
#include <stdint.h>

// First-order voice sections at the 16 kHz ADPCM PCM rate. Their relative
// responses are fitted to the measured 446 MHz TX/RX link response. TX has
// unity gain at 1 kHz; RX has unity gain at DC to avoid boosting static.
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
    const float x = (float)sample;
    const float filtered = b0 * x + b1 * previousInput + pole * previousOutput;
    previousInput = x;
    previousOutput = filtered;
    const float target = enabled ? 1.0f : 0.0f;
    // A 4 ms blend avoids a discontinuity when the host changes voice mode.
    if (wet < target) wet = fminf(target, wet + 1.0f / 64.0f);
    if (wet > target) wet = fmaxf(target, wet - 1.0f / 64.0f);
    const float y = x + wet * (filtered - x);
    if (y >= 32767.0f) return 32767;
    if (y <= -32768.0f) return -32768;
    return (int16_t)lroundf(y);
  }

protected:
  FirstOrderIIR(float b0, float b1, float pole)
      : b0(b0), b1(b1), pole(pole) { reset(); }

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
  // Unity DC gain: (b0 + b1) / (1 - pole) = 1. The nonnegative impulse
  // response cannot amplify a bounded PCM input into clipping.
  RxVoiceDeEmphasis()
      : FirstOrderIIR(0.07736347f, -0.02736347f, 0.95000000f) {}
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
