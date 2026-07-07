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
#include <esp_task_wdt.h>
#include <AfskModulator.h>
#include "globals.h"
#include "protocol.h"
#include "audioResampler.h"

bool txStreamConfigured = false;
bool txDecodeStreamStarted = false;
I2SStream out;
AudioInfo txInfo(AUDIO_SAMPLE_RATE, 1, 16);
AudioInfo txAudioInfo(AUDIO_WIRE_SAMPLE_RATE, 1, 16);
AudioUpsampleOutput txUpsample(out);
ADPCMDecoder txAdpcmDecoder(AV_CODEC_ID_ADPCM_IMA_WAV, AUDIO_FRAME_BYTES);
EncodedAudioStream txDecodeStream(&txUpsample, &txAdpcmDecoder);

// Tx runaway detection stuff
uint32_t txStartTime = -1;
const uint16_t RUNAWAY_TX_SEC = 200;

float txAfskBlock[TX_AFSK_BLOCK_SAMPLES];
int16_t txAfskPcm[TX_AFSK_BLOCK_SAMPLES];

static void onAfskTxSamples(const float *samples, size_t count) {
  if (!samples || count == 0 || !txStreamConfigured) {
    return;
  }
  if (count > TX_AFSK_BLOCK_SAMPLES) {
    count = TX_AFSK_BLOCK_SAMPLES;
  }
  for (size_t i = 0; i < count; i++) {
    float s = samples[i] * TX_AFSK_GAIN;
    if (s > 1.0f) s = 1.0f;
    if (s < -1.0f) s = -1.0f;
    txAfskPcm[i] = (int16_t)lroundf(s * 32767.0f);
  }
  out.write((uint8_t *)txAfskPcm, count * sizeof(int16_t));
  esp_task_wdt_reset();
}

AfskModulator afskMod(AUDIO_SAMPLE_RATE, onAfskTxSamples);

void initI2STx() {  
  auto config = out.defaultConfig(TX_MODE);
  config.copyFrom(txInfo);
  config.pin_data = hw.pins.pinAudioOut;
  config.pin_ws = 27;
  config.use_apll = true;
  config.auto_clear = false;
  config.signal_type = PDM;
  out.begin(config);
  txUpsample.begin();
  if (!txDecodeStreamStarted) {
    txDecodeStream.begin(txAudioInfo);
    txDecodeStreamStarted = true;
  }
  i2s_zero_dma_buffer(I2S_NUM_0);
  txStreamConfigured = true;
}

void endI2STx() {
  if (txStreamConfigured) {
    // Set pin to INPUT before stopping I2S to avoid end-of-TX click.
    // If left as output, the last PDM bit may hold the line high or low,
    // causing a DC step across the AC-coupling cap and producing a pop.
    // Forcing the pin to high-Z prevents this.
    pinMode(hw.pins.pinAudioOut, INPUT); 
    // ADPCMDecoder::end() is not safe to re-begin on the pinned adpcm library.
    // Keep the decoder alive across PTT transitions and only stop the hardware output path.
    txUpsample.end();
    out.end();
  }
  txStreamConfigured = false;
}

void processTxAudio(uint8_t *src, size_t len) {
  if (!src || len == 0 || !txStreamConfigured) {
    return;
  }
  txDecodeStream.write(src, len);
  esp_task_wdt_reset();
}

void processTxAx25(uint8_t *src, size_t len) {
  if (!src || len == 0) {
    return;
  }
  afskMod.modulate(src, len, txAfskBlock, TX_AFSK_BLOCK_SAMPLES, TX_AFSK_LEAD_SILENCE_MS, TX_AFSK_TAIL_SILENCE_MS);
}

void inline txAudioLoop() {
  if (mode == MODE_TX) {
    // Check for runaway tx
    if ((millis() - txStartTime) > RUNAWAY_TX_SEC * 1000) {
      setMode(rxIdleMode());
      esp_task_wdt_reset();
    }
  }
}
