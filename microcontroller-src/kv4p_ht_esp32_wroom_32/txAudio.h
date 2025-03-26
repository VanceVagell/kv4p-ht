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

// Tx runaway detection stuff
uint32_t txStartTime = -1;
const uint16_t RUNAWAY_TX_SEC = 200;

static const i2s_config_t i2sTxConfig = {
  .mode             = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
  .sample_rate      = AUDIO_SAMPLE_RATE,
  .bits_per_sample  = I2S_BITS_PER_SAMPLE_16BIT,
  .channel_format   = I2S_CHANNEL_FMT_ONLY_RIGHT,
  .intr_alloc_flags = 0,
  .dma_buf_count    = 8,
  .dma_buf_len      = I2S_WRITE_LEN,
  .use_apll         = true};

void initI2STx() {
  // Remove any previous driver (rx or tx) that may have been installed.
  if (i2sStarted) {
    i2s_driver_uninstall(I2S_NUM_0);
  }
  i2sStarted = true;
  i2s_driver_install(I2S_NUM_0, &i2sTxConfig, 0, NULL);
  i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN);
  i2s_zero_dma_buffer(I2S_NUM_0);
}

void processTxAudio(uint8_t *src, size_t len) {
  static int16_t buffer16[I2S_WRITE_LEN];
  for (int i = 0; i < len; i++) {
    buffer16[i] = (int16_t)(src[i]) << 8;
  }
  size_t bytesWritten = 0;
  ESP_ERROR_CHECK(i2s_write(I2S_NUM_0, buffer16, len * sizeof(int16_t), &bytesWritten, portMAX_DELAY));
}

void inline txAudioLoop() {
  if (mode == MODE_TX) {
    // Check for runaway tx
    if ((millis() - txStartTime) > RUNAWAY_TX_SEC * 1000) {
      setMode(MODE_RX);
      esp_task_wdt_reset();
    }
  }
}