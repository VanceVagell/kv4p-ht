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
#include <driver/adc.h>
#include <driver/i2s.h>
#include <driver/dac.h>
#include <esp_task_wdt.h>
#include "globals.h"
#include "protocol.h"
#include "debug.h"

static const i2s_config_t i2sRxConfig = {
  .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_ADC_BUILT_IN),
  .sample_rate          = AUDIO_SAMPLE_RATE + SAMPLING_RATE_OFFSET,
  .bits_per_sample      = I2S_BITS_PER_SAMPLE_16BIT,
  .channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT,
  .communication_format = i2s_comm_format_t(I2S_COMM_FORMAT_STAND_I2S | I2S_COMM_FORMAT_STAND_MSB),
  .intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1,
  .dma_buf_count        = 4,
  .dma_buf_len          = I2S_READ_LEN,
  .use_apll             = true,
  .tx_desc_auto_clear   = false,
  .fixed_mclk           = 0};

void iir_lowpass_reset();

void initI2SRx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;
  // Initialize ADC
  adc1_config_width(ADC_WIDTH_BIT_12);
  if (hardware_version == HW_VER_V2_0C) {
    // v2.0c has a lower input ADC range
    adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_0);
  } else {
    adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_12);
  }
  ESP_ERROR_CHECK(i2s_driver_install(I2S_NUM_0, &i2sRxConfig, 0, NULL));
  ESP_ERROR_CHECK(i2s_set_adc_mode(I2S_ADC_UNIT, I2S_ADC_CHANNEL));
  dac_output_enable(DAC_CHANNEL_2);  // GPIO26 (DAC1)
  dac_output_voltage(DAC_CHANNEL_2, 138);
  iir_lowpass_reset();
}
  
  #define DECAY_TIME 0.25  // seconds
  #define ALPHA (1.0f - expf(-1.0f / (AUDIO_SAMPLE_RATE * (DECAY_TIME / logf(2.0f)))))
  static float prev_y = 0.0f;
  
  void iir_lowpass_reset() {
    prev_y = 0.0f;
  }
  
  // IIR Low-pass filter (float state)
  int16_t iir_lowpass(int16_t x) {
    float x_f = (float)x;
    // IIR calculation: y[n] = α * x[n] + (1 - α) * y[n-1]
    prev_y = ALPHA * x_f + (1.0f - ALPHA) * prev_y;
    // Convert result back to int16
    return (int16_t)prev_y;
  }
  
  // High-pass: x[n] - LPF(x[n])
  int16_t remove_dc(int16_t x) {
    return x - iir_lowpass(x);
  }

void rxAudioLoop() {
  if (mode == MODE_RX) {
    size_t bytesRead = 0;
    static uint16_t buffer16[I2S_READ_LEN];
    static uint8_t buffer8[I2S_READ_LEN];
    ESP_ERROR_CHECK(i2s_read(I2S_NUM_0, &buffer16, sizeof(buffer16), &bytesRead, 0));
    if (bytesRead > 0) {
      size_t samplesRead = bytesRead / 2;
      for (int i = 0; i < samplesRead; i++) {
        int16_t sample = remove_dc(2048 - (int16_t)(buffer16[i] & 0xfff));
        buffer8[i]     = squelched ? 0 : (sample >> 4);  // Signed
      }
      if (samplesRead > PROTO_MTU) {
        _LOGE("Audio farame will be clipped: requested %d, clipped to %d", samplesRead, PROTO_MTU);
      }
      sendAudio(buffer8, samplesRead);
      esp_task_wdt_reset();
    }
  }
}