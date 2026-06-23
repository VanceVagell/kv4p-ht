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
#include <AudioTools/AudioCodecs/CodecADPCM.h>
#include <driver/dac.h>
#include <esp_task_wdt.h>
#include <AfskDemodulator.h>
#include <math.h>
#include "globals.h"
#include "protocol.h"
#include "debug.h"
#include "audioResampler.h"

class SerialOutput : public AudioOutput {
public:
  size_t write(const uint8_t *data, size_t len) override {
    if (len > 0) {
      if (len > PROTO_MTU) {
        len = PROTO_MTU;
      }
      if (mode == MODE_RX) {
        sendAudio((uint8_t*)data, len);
      }
      return len;
    }
    return len;
  } 
};

#define DECAY_TIME 0.25  // seconds

class DCOffsetRemover : public AudioEffect {
public:
  DCOffsetRemover(float decay_time = 0.25f, float sample_rate = AUDIO_SAMPLE_RATE): prev_y(0.0f) {
    alpha = 1.0f - expf(-1.0f / (sample_rate * (decay_time / logf(2.0f))));
  }
  DCOffsetRemover(const DCOffsetRemover &) = default;
  effect_t process(effect_t input) {
    return active() ? remove_dc(input) : input;
  }
  DCOffsetRemover *clone() override {
    return new DCOffsetRemover(*this);
  }
private:
  float prev_y;
  float alpha;
  int16_t remove_dc(int16_t x) {
    prev_y = alpha * x + (1.0f - alpha) * prev_y;
    return x - (int16_t)prev_y;
  }
};

static void onAfskPacketDecoded(const uint8_t *frame, size_t len) {
  if (frame && len > 0) {
    pulseAprsRxLED();
    sendAx25Packet(frame, len);
  }
}

AfskDemodulator afskDemod(AUDIO_SAMPLE_RATE, 2, onAfskPacketDecoded);

class AfskTapEffect : public AudioEffect {
public:
  AfskTapEffect *clone() override {
    return new AfskTapEffect(*this);
  }

  effect_t process(effect_t input) {
    if (active()) {
      samples[sampleCount++] = (int16_t)input;
      if (sampleCount >= AFSK_TAP_BUFFER_SAMPLES) {
        afskDemod.processSamples(samples, sampleCount);
        sampleCount = 0;
      }
    }
    return input;
  }

  void flush() {
    if (sampleCount > 0) {
      afskDemod.processSamples(samples, sampleCount);
      sampleCount = 0;
    }
    afskDemod.flush();
  }

private:
  static const size_t AFSK_TAP_BUFFER_SAMPLES = 256;
  int16_t samples[AFSK_TAP_BUFFER_SAMPLES];
  size_t sampleCount = 0;
};

class SoftSquelchEffect : public AudioEffect {
public:
  SoftSquelchEffect(uint32_t sampleRate = AUDIO_SAMPLE_RATE) {
    begin(sampleRate);
  }

  SoftSquelchEffect *clone() override {
    return new SoftSquelchEffect(*this);
  }

  void begin(uint32_t sampleRate) {
    if (sampleRate == 0) {
      sampleRate = AUDIO_SAMPLE_RATE;
    }
    if (sampleRate != currentSampleRate) {
      currentSampleRate = sampleRate;
      resetState();
      recalculateCoefficients();
    } else if (!coefficientsReady) {
      recalculateCoefficients();
    }
  }

  effect_t process(effect_t input) override {
    if (!active()) {
      return input;
    }
    if (!coefficientsReady) {
      begin(currentSampleRate == 0 ? AUDIO_SAMPLE_RATE : currentSampleRate);
    }

    uint8_t level = softSquelchDeadbandLevel;
    if (level > 8) {
      level = 8;
    }
    deadbandLevel = level;
    deadband = deadbandForLevel(level);

    float x = (float)input / 32768.0f;
    float filtered = bpf.filter(x);

    bool crossing = false;
    if (filtered > deadband || filtered < -deadband) {
      crossing = ((previousOutsideSample < -deadband && filtered > deadband) || (previousOutsideSample > deadband && filtered < -deadband));
      previousOutsideSample = filtered;
    }

    float crossingInstantRate = crossing ? (float)currentSampleRate : 0.0f;
    iirZcr += zcrAlpha * (crossingInstantRate - iirZcr);
    updateDecision();
    return input;
  }

  float getIirZcr() const {
    return iirZcr;
  }

  bool isSoftOpen() const {
    return softSqOpen;
  }

  bool consumeOpenChanged() {
    bool wasChanged = openChanged;
    openChanged = false;
    return wasChanged;
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
  static constexpr float SOFT_SQUELCH_ZCR_TAU_SEC = 0.100f;
  static constexpr float SOFT_SQUELCH_CLOSE_DELAY_SEC = 0.250f;
  static constexpr int SOFT_SQUELCH_BPF_TAPS = 65;
  static constexpr int SOFT_SQUELCH_BPF_STATE_LEN = SOFT_SQUELCH_BPF_TAPS + 4;

  uint32_t currentSampleRate = 0;
  bool coefficientsReady = false;
  afsk::dsp::Esp32Fir bpf;
  float bpfTaps[SOFT_SQUELCH_BPF_TAPS] AUDIO_DSP_ALIGN16;
  float bpfState[SOFT_SQUELCH_BPF_STATE_LEN] AUDIO_DSP_ALIGN16;
  float previousOutsideSample = 0.0f;
  float zcrAlpha = 0.0f;
  float iirZcr = 0.0f;
  uint32_t aboveThresholdSamples = 0;
  bool softSqOpen = false;
  bool openChanged = true;
  uint8_t deadbandLevel = 0;
  float deadband = 0.45f;

  static float deadbandForLevel(uint8_t level) {
    if (level > 8) {
      level = 8;
    }
    return 0.45f - ((float)level / 8.0f) * (0.45f - 0.08f);
  }

  void resetState() {
    previousOutsideSample = 0.0f;
    iirZcr = 0.0f;
    aboveThresholdSamples = 0;
    setSoftSqOpen(false);
  }

  void recalculateCoefficients() {
    float sampleRate = (float)currentSampleRate;
    float centerHz = sqrtf(SOFT_SQUELCH_LOW_HZ * SOFT_SQUELCH_HIGH_HZ);
    afsk::dsp::afsk_design_bandpass_kaiser(bpfTaps, SOFT_SQUELCH_BPF_TAPS,
                                           SOFT_SQUELCH_LOW_HZ, SOFT_SQUELCH_HIGH_HZ,
                                           sampleRate, 30.0f);
    normalizeBpfAt(centerHz, sampleRate);
    bpf.init(bpfTaps, SOFT_SQUELCH_BPF_TAPS, bpfTaps, bpfState, SOFT_SQUELCH_BPF_STATE_LEN);
    zcrAlpha = 1.0f - expf(-1.0f / (sampleRate * SOFT_SQUELCH_ZCR_TAU_SEC));
    coefficientsReady = bpf.len == SOFT_SQUELCH_BPF_TAPS;
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

    if (aboveThresholdSamples < currentSampleRate) {
      aboveThresholdSamples++;
    }
    uint32_t closeDelaySamples = (uint32_t)((float)currentSampleRate * SOFT_SQUELCH_CLOSE_DELAY_SEC);
    if (aboveThresholdSamples >= closeDelaySamples) {
      setSoftSqOpen(false);
    }
  }

  void setSoftSqOpen(bool open) {
    if (softSqOpen != open) {
      softSqOpen = open;
      openChanged = true;
    }
  }
};

bool rxStreamConfigured = false;
AnalogAudioStream in;
AudioInfo rxInfo(AUDIO_SAMPLE_RATE, 1, 16);
AudioInfo rxAudioInfo(AUDIO_WIRE_SAMPLE_RATE, 1, 16);
SerialOutput rxAudioOutput;
ADPCMEncoder rxAdpcmEncoder(AV_CODEC_ID_ADPCM_IMA_WAV, AUDIO_FRAME_BYTES);
EncodedAudioStream rxOut(&rxAudioOutput, &rxAdpcmEncoder);
AudioDownsampleConverter rxDownsample;
AudioEffectStream effects(in);  
ConverterStream<int16_t> rxDownsampledEffects(effects, rxDownsample);
StreamCopy rxCopier(rxOut, rxDownsampledEffects, AUDIO_FRAME_SAMPLES_48K * sizeof(int16_t));
Boost mute(0.0);
Boost gain(16.0);
DCOffsetRemover dcOffsetRemover(DECAY_TIME, AUDIO_SAMPLE_RATE);
AfskTapEffect afskTapEffect;
SoftSquelchEffect softSquelchEffect(AUDIO_SAMPLE_RATE);

inline void updateEffectiveSquelchFromSoft() {
  if (!softSquelchEnabled) {
    return;
  }
  bool nextSquelched = !softSquelchEffect.isSoftOpen();
  if (softSquelchEffect.consumeOpenChanged() || nextSquelched != squelched) {
    squelched = nextSquelched;
    markDeviceStateDirty();
  }
}

inline void injectADCBias() {
  dac_output_enable(DAC_CHANNEL_2);  // GPIO26 (DAC1)
  dac_output_voltage(DAC_CHANNEL_2, (255.0 / 3.3) * hw.adcBias);
} 

inline void setUpADCAttenuator() {
  adc1_config_channel_atten(I2S_ADC_CHANNEL, hw.adcAttenuation);
}

void initI2SRx() {
  if (rxStreamConfigured) {
    return;
  }
  injectADCBias();
  setUpADCAttenuator();
  //AudioToolsLogger.begin(debugPrinter, AudioToolsLogLevel::Debug);
  auto config = in.defaultConfig(RX_MODE);
  config.copyFrom(rxInfo);
  config.is_auto_center_read = false; // We use dcOffsetRemover instead
  config.use_apll = true;
  config.auto_clear = false;
  config.adc_pin = hw.pins.pinAudioIn;
  config.sample_rate = AUDIO_SAMPLE_RATE * 1.00;
  in.begin(config);
  // effects
  effects.clear();
  softSquelchEffect.begin(rxInfo.sample_rate);
  afskTapEffect.setActive(true);
  effects.addEffect(dcOffsetRemover);
  effects.addEffect(gain);
  effects.addEffect(afskTapEffect);
  effects.addEffect(softSquelchEffect);
  effects.addEffect(mute);
  effects.begin(rxInfo);
  // open output
  rxDownsample.begin();
  rxOut.begin(rxAudioInfo);
  rxCopier.setMinCopySize(sizeof(int16_t));
  rxCopier.setCheckAvailable(false);
  rxStreamConfigured = true;
}

void endI2SRx() {
  if (rxStreamConfigured) {
    afskTapEffect.flush();
    rxOut.end();
    effects.end();
    in.end();
  }
  rxStreamConfigured = false;
}
  
void rxAudioLoop() {
  if (mode == MODE_RX || mode == MODE_STOPPED) {
    updateEffectiveSquelchFromSoft();
    mute.setActive(squelched);
    rxCopier.copy();
    updateEffectiveSquelchFromSoft();
    esp_task_wdt_reset();
  }
}
