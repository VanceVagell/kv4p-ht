#pragma once

#include <Arduino.h>
#include <driver/adc.h>
#include <driver/i2s.h>
#include <driver/dac.h>
#include <esp_task_wdt.h>
#include "globals.h"
#include "protocol.h"

void initI2STx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;
  static const i2s_config_t i2sTxConfig = {
    .mode             = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
    .sample_rate      = AUDIO_SAMPLE_RATE,
    .bits_per_sample  = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format   = I2S_CHANNEL_FMT_ONLY_RIGHT,
    .intr_alloc_flags = 0,
    .dma_buf_count    = 8,
    .dma_buf_len      = I2S_WRITE_LEN,
    .use_apll         = true};
  i2s_driver_install(I2S_NUM_0, &i2sTxConfig, 0, NULL);
  i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN);
}

void processTxAudio(uint8_t *src, size_t len) {
  int16_t buffer16[I2S_WRITE_LEN];
  for (int i = 0; i < len; i++) {
    buffer16[i] = (int16_t)(src[i]) << 8;  // Convert 8-bit unsigned PCM to 16-bit signed PCM
  }
  size_t bytesWritten = 0;
  ESP_ERROR_CHECK(i2s_write(I2S_NUM_0, buffer16, len * sizeof(int16_t), &bytesWritten, portMAX_DELAY));
}

void inline txAudioLoop() {
}