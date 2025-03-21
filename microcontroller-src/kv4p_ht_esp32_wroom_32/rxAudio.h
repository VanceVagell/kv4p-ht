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
#include <AudioTools/AudioCodecs/CodecOpusOgg.h>
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
  
bool rxStreamConfidured = false;
AnalogAudioStream in;
AudioInfo rxInfo(AUDIO_SAMPLE_RATE + SAMPLING_RATE_OFFSET, 1, 16);
//OpusAudioEncoder rxEnc;
EncoderL8 rxEnc(true); 
SerialOutput rxAudioOutput;
EncodedAudioStream rxOut(&rxAudioOutput, &rxEnc);
AudioEffectStream effects(in);  
StreamCopy rxCopier(rxOut, effects);
Boost mute(0.0);
Boost gain(16.0);
Compressor comp(AUDIO_SAMPLE_RATE + SAMPLING_RATE_OFFSET);

void initI2SRx() {
  if (hardware_version == HW_VER_V2_0C) {
    // v2.0c has a lower input ADC range
    adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_0);
  } else {
    adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_12);
  }
  //AudioToolsLogger.begin(debugPrinter, AudioToolsLogLevel::Debug);
  auto config = in.defaultConfig(RX_MODE);
  config.copyFrom(rxInfo);
  config.auto_clear = true;
  config.is_auto_center_read = true;
  config.buffer_size = I2S_READ_LEN;
  config.buffer_count = 4;
  config.adc_pin = ADC_PIN; 
  in.begin(config);
  rxEnc.setAudioInfo(rxInfo);
  // configure OPUS additinal parameters
  /*
  auto &enc_cfg = rxEnc.config();
  enc_cfg.application = OPUS_APPLICATION_AUDIO;
  enc_cfg.frame_sizes_ms_x2 = OPUS_FRAMESIZE_40_MS;
  enc_cfg.vbr = 1;
  enc_cfg.max_bandwidth = OPUS_BANDWIDTH_WIDEBAND;
  rxEnc.begin(enc_cfg);
  */
  rxEnc.setAudioInfo(config);
  rxEnc.begin();

  effects.clear();
  effects.addEffect(&gain);
  effects.addEffect(&comp);
  effects.addEffect(&mute);
  effects.begin(rxInfo);

  // open output
  rxOut.begin(rxInfo);
  // ADC bias
  dac_output_enable(DAC_CHANNEL_2);  // GPIO26 (DAC1)
  dac_output_voltage(DAC_CHANNEL_2, (255.0 / 3.3) * ADC_BIAS_VOLTAGE);

  rxStreamConfidured = true;
}

void endI2SRx() {
  if (rxStreamConfidured) {
    rxOut.end();
    effects.end();
    rxEnc.end();
    in.end();
  }
  rxStreamConfidured = false;
}
  
void rxAudioLoop() {
  if (mode == MODE_RX) {
    mute.setActive(squelched);
    rxCopier.copy();
    esp_task_wdt_reset();
  }
}