/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include <AudioTools.h>
#include <driver/adc.h>
#include <driver/dac.h>
#include <esp_task_wdt.h>
#include "hardware.h"
#include "config.h"
#include "streamer.h"

// Capture chain (mirrors the upstream RX path, minus ADPCM/AFSK):
// I2S-driven ADC DMA @ 48 kHz on GPIO34/ADC1 -> DC removal -> x16 gain
// -> 65-tap FIR low-pass + decimate by 3 -> PCM16 @ 16 kHz -> stream ring.

AnalogAudioStream adcIn;
bool squelchOpen = false;

// --- One-pole DC blocker (same time constant as upstream's DCOffsetRemover) ---
static float dcAlpha = 0.0f;
static float dcPrev = 0.0f;

static inline int16_t removeDc(int16_t x) {
  dcPrev = dcAlpha * x + (1.0f - dcAlpha) * dcPrev;
  return (int16_t)(x - (int16_t)dcPrev);
}

// --- Windowed-sinc low-pass FIR, decimating 48 kHz -> 16 kHz ---
// Cutoff 6.8 kHz like upstream's decimator; plain C convolution is ~1M MAC/s
// at this rate, no DSP library needed.
#define FIR_TAPS 65
static float firCoeffs[FIR_TAPS];
static int16_t firHistory[FIR_TAPS - 1];

static void designFir() {
  const float fc = 6800.0f / CAPTURE_SAMPLE_RATE;  // normalized cutoff
  const int M = FIR_TAPS - 1;
  float sum = 0.0f;
  for (int k = 0; k < FIR_TAPS; k++) {
    float n = k - M / 2.0f;
    float sinc = (n == 0.0f) ? 2.0f * fc : sinf(2.0f * (float)M_PI * fc * n) / ((float)M_PI * n);
    float hamming = 0.54f - 0.46f * cosf(2.0f * (float)M_PI * k / M);
    firCoeffs[k] = sinc * hamming;
    sum += firCoeffs[k];
  }
  for (int k = 0; k < FIR_TAPS; k++) {
    firCoeffs[k] /= sum;  // unity DC gain
  }
  memset(firHistory, 0, sizeof(firHistory));
}

// input: FRAME_SAMPLES_48K samples; output: FRAME_SAMPLES_16K samples
static void decimateFrame(const int16_t *input, int16_t *output) {
  static int16_t ext[FIR_TAPS - 1 + FRAME_SAMPLES_48K];
  memcpy(ext, firHistory, sizeof(firHistory));
  memcpy(ext + FIR_TAPS - 1, input, FRAME_SAMPLES_48K * sizeof(int16_t));
  for (int j = 0; j < FRAME_SAMPLES_16K; j++) {
    const int16_t *x = ext + (FIR_TAPS - 1) + j * DECIMATION_RATIO;
    float acc = 0.0f;
    for (int k = 0; k < FIR_TAPS; k++) {
      acc += firCoeffs[k] * x[-k];
    }
    if (acc > 32767.0f) acc = 32767.0f;
    if (acc < -32768.0f) acc = -32768.0f;
    output[j] = (int16_t)lroundf(acc);
  }
  memcpy(firHistory, ext + FRAME_SAMPLES_48K, sizeof(firHistory));
}

// DAC on GPIO26 biases the ADC analog front-end (board requirement, upstream identical)
static inline void injectADCBias() {
  dac_output_enable(DAC_CHANNEL_2);
  dac_output_voltage(DAC_CHANNEL_2, (uint8_t)((255.0f / 3.3f) * hw.adcBias));
}

void audioInit() {
  injectADCBias();
  adc1_config_channel_atten(I2S_ADC_CHANNEL, hw.adcAttenuation);
  dcAlpha = 1.0f - expf(-1.0f / (CAPTURE_SAMPLE_RATE * (0.25f / logf(2.0f))));
  designFir();
  auto config = adcIn.defaultConfig(RX_MODE);
  config.copyFrom(AudioInfo(CAPTURE_SAMPLE_RATE, 1, 16));
  config.is_auto_center_read = false;  // DC handled by removeDc()
  config.use_apll = true;
  config.auto_clear = false;
  config.adc_pin = hw.pins.pinAudioIn;
  config.sample_rate = CAPTURE_SAMPLE_RATE;
  adcIn.begin(config);
}

// Called from loop(): pull one 15 ms frame from the ADC DMA, process, publish.
// Reads accumulate across calls so a partial DMA read never discards samples.
void audioLoop() {
  static int16_t buf48k[FRAME_SAMPLES_48K];
  static int16_t buf16k[FRAME_SAMPLES_16K];
  static size_t filled = 0;
  filled += adcIn.readBytes((uint8_t *)buf48k + filled, sizeof(buf48k) - filled);
  esp_task_wdt_reset();
  if (filled < sizeof(buf48k)) {
    return;
  }
  filled = 0;
  bool mute = cfg.muteWhenClosed && !squelchOpen;
  for (int i = 0; i < FRAME_SAMPLES_48K; i++) {
    int32_t s = removeDc(buf48k[i]) * 16;  // 12-bit ADC data -> full 16-bit scale
    if (s > 32767) s = 32767;
    if (s < -32768) s = -32768;
    buf48k[i] = mute ? 0 : (int16_t)s;
  }
  decimateFrame(buf48k, buf16k);
  ringWrite((uint8_t *)buf16k, sizeof(buf16k));
  esp_task_wdt_reset();
}
