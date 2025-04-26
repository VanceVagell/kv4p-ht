/*
KV4P-HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

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
#include <AudioTools/AudioCodecs/CodecOpus.h>
#include <driver/dac.h>
#include <esp_task_wdt.h>
#include "globals.h"
#include "protocol.h"
#include "debug.h"

// Custom Output to Forward Encoded Data to Serial
class SerialOutput : public AudioOutput {
public:
  size_t write(const uint8_t *data, size_t len) override {
    if (len > 0) {
      if (len > PROTO_MTU) {
        len = PROTO_MTU;
      }
      sendAudio((uint8_t*)data, len);
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

bool rxStreamConfigured = false;
AnalogAudioStream in;
AudioInfo rxInfo(AUDIO_SAMPLE_RATE, 1, 16);
OpusAudioEncoder rxEnc;
SerialOutput rxAudioOutput;
EncodedAudioStream rxOut(&rxAudioOutput, &rxEnc);
AudioEffectStream effects(in);  
StreamCopy rxCopier(rxOut, effects);
Boost mute(0.0);
Boost gain(16.0);
DCOffsetRemover dcOffsetRemover(DECAY_TIME, AUDIO_SAMPLE_RATE);

inline void injectADCBias() {
  dac_output_enable(DAC_CHANNEL_2);  // GPIO26 (DAC1)
  dac_output_voltage(DAC_CHANNEL_2, (255.0 / 3.3) * ADC_BIAS_VOLTAGE);
} 

inline void setUpADCAttenuator() {
  if (hardware_version == HW_VER_V2_0C) { // v2.0c has a lower input ADC range
    adc1_config_channel_atten(I2S_ADC_CHANNEL, ADC_ATTENUATION_v20C);
  } else {
    adc1_config_channel_atten(I2S_ADC_CHANNEL, ADC_ATTENUATION);
  }
}

void initI2SRx() {
  injectADCBias();
  setUpADCAttenuator();
  //AudioToolsLogger.begin(debugPrinter, AudioToolsLogLevel::Debug);
  auto config = in.defaultConfig(RX_MODE);
  config.copyFrom(rxInfo);
  config.is_auto_center_read = false; // We use dcOffsetRemover instead
  config.use_apll = true;
  config.auto_clear = false;
  config.adc_pin = ADC_PIN;
  config.sample_rate = AUDIO_SAMPLE_RATE * 1.02; // 2% over sample rate to avoid buffer underruns
  in.begin(config);
  rxEnc.setAudioInfo(rxInfo);
  // configure OPUS additinal parameters
  auto &encoderConfig = rxEnc.config();
  encoderConfig.application = OPUS_APPLICATION_AUDIO;
  encoderConfig.frame_sizes_ms_x2 = OPUS_FRAMESIZE_40_MS;
  encoderConfig.vbr = 1;
  encoderConfig.max_bandwidth = OPUS_BANDWIDTH_NARROWBAND;
  rxEnc.begin(encoderConfig);
  // effects
  effects.clear();
  effects.addEffect(dcOffsetRemover);
  effects.addEffect(gain);
  effects.addEffect(mute);
  effects.begin(rxInfo);
  // open output
  rxOut.begin(rxInfo);
  rxStreamConfigured = true;
}

void endI2SRx() {
  if (rxStreamConfigured) {
    rxOut.end();
    effects.end();
    in.end();
  }
  rxStreamConfigured = false;
}
  
void rxAudioLoop() {
  if (mode == MODE_RX) {
    mute.setActive(squelched);
    rxCopier.copy();
    esp_task_wdt_reset();
  }
}