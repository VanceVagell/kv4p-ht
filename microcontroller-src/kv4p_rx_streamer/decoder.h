/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include "config.h"
#include "frames.h"
#include "decoder_ffsk.h"
#include "decoder_nemo.h"

// Telegram decoder task. audioLoop() (core 1, watchdog-registered) copies
// each unmuted audio frame into a small SPSC ring; this task (core 0)
// drains it and runs the demodulator selected by the active channel's
// dataProto. Only one decoder ever runs — one radio, one channel.
//
// Ring frames: [u16 length][int16 samples]. FFSK consumes the 16 kHz
// stream, NEMO the 48 kHz one, so the frame size also identifies the rate.

#define DEC_RING_SIZE 16384  // power of two; ~170 ms of 48 kHz audio

static uint8_t *decRing = nullptr;
static volatile uint32_t decWr = 0, decRd = 0;  // monotonic byte positions
static TaskHandle_t decoderTaskHandle = nullptr;
static volatile uint8_t decRequestedProto = PROTO_NONE;  // set by decoderReconfigure()
static uint8_t decActiveProto = PROTO_NONE;              // owned by the task
volatile uint32_t decFeedDrops = 0;

static FfskDemod ffskDemod;
static FfskFramer ffskFramer;
static NemoDecoder nemoDec;
static int16_t *nemoEnvBuf = nullptr;  // 18 kB, allocated only while NEMO is active

// Re-read the active channel/VFO protocol; the task applies the switch.
void decoderReconfigure() {
  decRequestedProto = activeDataProto();
}

// --- producer side (audioLoop, core 1) ---

static void decRingCopyAt(uint32_t pos, const uint8_t *data, size_t len) {
  size_t idx = pos & (DEC_RING_SIZE - 1);
  size_t first = min(len, (size_t)(DEC_RING_SIZE - idx));
  memcpy(decRing + idx, data, first);
  if (len > first) memcpy(decRing, data + first, len - first);
}

void decoderFeed(const int16_t *b48k, size_t n48, const int16_t *b16k, size_t n16) {
  uint8_t proto = decRequestedProto;
  if (proto == PROTO_NONE || !decRing || !decoderTaskHandle) return;
  const int16_t *payload = (proto == PROTO_NEMO_LIO) ? b48k : b16k;
  uint16_t len = (uint16_t)(((proto == PROTO_NEMO_LIO) ? n48 : n16) * sizeof(int16_t));
  uint32_t pos = decWr;
  if (DEC_RING_SIZE - (pos - decRd) < (uint32_t)len + 2) {
    decFeedDrops = decFeedDrops + 1;
    return;
  }
  decRingCopyAt(pos, (const uint8_t *)&len, 2);
  decRingCopyAt(pos + 2, (const uint8_t *)payload, len);
  decWr = pos + 2 + len;  // single publish: the consumer never sees a torn frame
  xTaskNotifyGive(decoderTaskHandle);
}

// --- consumer side (decoder task, core 0) ---

static void decRingPop(uint8_t *dst, size_t len) {
  size_t idx = decRd & (DEC_RING_SIZE - 1);
  size_t first = min(len, (size_t)(DEC_RING_SIZE - idx));
  memcpy(dst, decRing + idx, first);
  if (len > first) memcpy(dst + first, decRing, len - first);
  decRd = decRd + len;
}

// FFSK bit sink: framer -> burst decode -> frame queue.
static void decFfskBitSink(void *, int bit) {
  static uint8_t burst[FFSK_BURST_BITS];
  uint16_t burstLen = 0;
  bool have = (bit < 0) ? ffskFramerFlush(&ffskFramer, burst, &burstLen)
                        : ffskFramerPushBit(&ffskFramer, (uint8_t)bit, burst, &burstLen);
  if (!have) return;
  stBursts = stBursts + 1;
  FfskResult results[FFSK_MAX_RESULTS];
  int n = ffskDecodeBurst(burst, burstLen, results, FFSK_MAX_RESULTS);
  for (int i = 0; i < n; i++) {
    FrameRecord r = {};
    r.proto = PROTO_FFSK_VDV;
    r.rawLen = results[i].len;
    memcpy(r.raw, results[i].bytes, results[i].len);
    strlcpy(r.label, results[i].label, sizeof(r.label));
    r.repairedBits = results[i].repaired;
    frameEnqueue(r);
  }
}

// NEMO frame sink: CRC already checked; dedup within the burst.
struct NemoSinkState {
  uint8_t seen[6][NEMO_FRAME_MAX];
  uint8_t seenLen[6];
  int nSeen;
};
static NemoSinkState nemoSink;

static void decNemoFrameSink(void *, const uint8_t *frame, int len, const char *how) {
  for (int i = 0; i < nemoSink.nSeen; i++) {
    if (nemoSink.seenLen[i] == len && memcmp(nemoSink.seen[i], frame, len) == 0) return;
  }
  if (nemoSink.nSeen < 6) {
    memcpy(nemoSink.seen[nemoSink.nSeen], frame, len);
    nemoSink.seenLen[nemoSink.nSeen] = (uint8_t)len;
    nemoSink.nSeen++;
  }
  FrameRecord r = {};
  r.proto = PROTO_NEMO_LIO;
  r.rawLen = (uint8_t)len;
  memcpy(r.raw, frame, len);
  snprintf(r.label, sizeof(r.label), "g2.%02X %dB %s", len > 3 ? frame[3] : 0, len, how);
  frameEnqueue(r);
}

static void decApplyProto(uint8_t proto) {
  decRd = decWr;  // discard queued audio from the previous mode
  ffskFramerInit(&ffskFramer);
  if (proto == PROTO_FFSK_VDV) {
    ffskDemodInit(&ffskDemod);
  }
  if (proto == PROTO_NEMO_LIO) {
    if (!nemoEnvBuf) {
      nemoEnvBuf = (int16_t *)heap_caps_malloc(NEMO_ENV_MAX * sizeof(int16_t), MALLOC_CAP_8BIT);
    }
    nemoInit(&nemoDec, nemoEnvBuf);
  } else if (nemoEnvBuf) {
    heap_caps_free(nemoEnvBuf);
    nemoEnvBuf = nullptr;
  }
  decActiveProto = proto;
  Serial.printf("[decoder] proto=%u heap=%u\n", proto, ESP.getFreeHeap());
}

static void decoderTask(void *) {
  static int16_t frame[FRAME_SAMPLES_48K];  // largest ring frame
  for (;;) {
    ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(100));
    if (decActiveProto != decRequestedProto) {
      decApplyProto(decRequestedProto);
    }
    while (decWr - decRd >= 2) {
      uint16_t len;
      decRingPop((uint8_t *)&len, 2);
      if (len > sizeof(frame) || decWr - decRd < len) {
        decRd = decWr;  // corrupt/partial (e.g. mid-switch); resync
        break;
      }
      decRingPop((uint8_t *)frame, len);
      int n = len / sizeof(int16_t);
      if (decActiveProto == PROTO_FFSK_VDV && n == FRAME_SAMPLES_16K) {
        ffskDemodProcess(&ffskDemod, frame, n, decFfskBitSink, nullptr);
      } else if (decActiveProto == PROTO_NEMO_LIO && n == FRAME_SAMPLES_48K) {
        nemoSink.nSeen = 0;
        int bursts = nemoFeed(&nemoDec, frame, n, decNemoFrameSink, nullptr);
        stBursts = stBursts + bursts;
      }
    }
  }
}

void decoderStart() {
  framesInit();
  decRing = (uint8_t *)heap_caps_malloc(DEC_RING_SIZE, MALLOC_CAP_8BIT);
  ffskCrcInit();
  decoderReconfigure();
  xTaskCreatePinnedToCore(decoderTask, "decoder", 6144, nullptr, 2, &decoderTaskHandle, 0);
}
