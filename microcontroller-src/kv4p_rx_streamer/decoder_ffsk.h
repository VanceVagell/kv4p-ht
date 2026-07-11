/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

// VDV 420 FFSK telegram decoder, a 1:1 port of the ffsk-decode Rust reference
// (dsp.rs / framer.rs / crc.rs / r09.rs). 2400 Bd FFSK: a logical 1 is one
// half-cycle of 1200 Hz, a 0 one full cycle of 2400 Hz, phase-continuous.
// Chain: DC block -> FIR Hilbert analytic signal -> instantaneous frequency
// -> low-pass -> Mueller & Muller clock recovery -> slicer, envelope squelch
// gating the clock recovery. Consumes the 16 kHz stream (the reference's
// roundtrip test passes at 16 kHz).
//
// Pure stdint/math — no Arduino dependencies, so it can be compiled and
// diffed against the Rust decoder on a host.

#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <math.h>

#define FFSK_FS           16000.0f
#define FFSK_BAUD         2400.0f
#define FFSK_MARK_HZ      1200.0f
#define FFSK_SPACE_HZ     2400.0f
#define FFSK_HILBERT_TAPS 65  // ((16000/250) | 1), floor 63
#define FFSK_LPF_TAPS     9
#define FFSK_PENDING_MAX  512

#define FFSK_GAIN_MU      0.175f
#define FFSK_GAIN_OMEGA   (0.25f * 0.175f * 0.175f)
#define FFSK_OMEGA_REL    0.01f

#define FFSK_SQ_OPEN      4.0f
#define FFSK_SQ_CLOSE     2.0f
#define FFSK_SQ_HOLD_S    0.030f
#define FFSK_SQ_FLOOR_MIN 1e-5f

// --- demodulator ---

// sink receives sliced bits (0/1) and -1 when the squelch gate closes.
typedef void (*FfskBitSink)(void *ctx, int bit);

struct FfskDemod {
  float dcX1, dcY1;
  float hilbertTaps[FFSK_HILBERT_TAPS];
  float hist[FFSK_HILBERT_TAPS];
  int histPos;
  float prevRe, prevIm;
  float lpfTaps[FFSK_LPF_TAPS];
  float lpfHist[FFSK_LPF_TAPS];
  int lpfPos;
  float envelope, envAlpha, noiseFloor, floorRise, floorFall;
  bool gateOpen;
  uint32_t belowCount, holdSamples;
  float pending[FFSK_PENDING_MAX];
  int pendLen;
  float pos, omega, omegaMid, omegaLim, lastSample;
};

static void ffskDemodInit(FfskDemod *d) {
  memset(d, 0, sizeof(*d));
  const int n = FFSK_HILBERT_TAPS;
  const int mid = n / 2;
  for (int k = 0; k < n; k++) {
    int m = k - mid;
    if (m % 2 == 0) {
      d->hilbertTaps[k] = 0.0f;
    } else {
      float w = 0.42f - 0.5f * cosf(2.0f * (float)M_PI * k / (n - 1.0f))
                + 0.08f * cosf(4.0f * (float)M_PI * k / (n - 1.0f));
      d->hilbertTaps[k] = 2.0f / ((float)M_PI * m) * w;
    }
  }
  const float cutoff = 2000.0f / FFSK_FS;
  float sum = 0.0f;
  for (int k = 0; k < FFSK_LPF_TAPS; k++) {
    float m = k - (FFSK_LPF_TAPS - 1) / 2.0f;
    float sinc = (m == 0.0f) ? 2.0f * cutoff : sinf(2.0f * (float)M_PI * cutoff * m) / ((float)M_PI * m);
    float w = 0.54f - 0.46f * cosf(2.0f * (float)M_PI * k / (FFSK_LPF_TAPS - 1.0f));
    d->lpfTaps[k] = sinc * w;
    sum += d->lpfTaps[k];
  }
  for (int k = 0; k < FFSK_LPF_TAPS; k++) d->lpfTaps[k] /= sum;
  d->envAlpha = 1.0f - expf(-1.0f / (FFSK_FS * 0.002f));
  // Start high: the floor only keeps the gate shut until the real idle
  // level is known (~50 ms).
  d->noiseFloor = 0.05f;
  d->floorRise = 1.0f - expf(-1.0f / (FFSK_FS * 5.0f));
  d->floorFall = 1.0f - expf(-1.0f / (FFSK_FS * 0.05f));
  d->holdSamples = (uint32_t)(FFSK_FS * FFSK_SQ_HOLD_S);
  d->omega = d->omegaMid = FFSK_FS / FFSK_BAUD;
  d->omegaLim = d->omegaMid * FFSK_OMEGA_REL;
}

static void ffskRecoverBits(FfskDemod *d, FfskBitSink sink, void *ctx) {
  for (;;) {
    int idx = (int)d->pos;
    if (idx + 1 >= d->pendLen) break;
    float frac = d->pos - (float)idx;
    float x = d->pending[idx] + frac * (d->pending[idx + 1] - d->pending[idx]);
    float sliceLast = d->lastSample >= 0.0f ? 1.0f : -1.0f;
    float sliceX = x >= 0.0f ? 1.0f : -1.0f;
    float e = sliceLast * x - sliceX * d->lastSample;
    if (e > 1.0f) e = 1.0f;
    if (e < -1.0f) e = -1.0f;
    d->omega += FFSK_GAIN_OMEGA * e;
    float lo = d->omegaMid - d->omegaLim, hi = d->omegaMid + d->omegaLim;
    if (d->omega < lo) d->omega = lo;
    if (d->omega > hi) d->omega = hi;
    d->pos += d->omega + FFSK_GAIN_MU * e;
    d->lastSample = x;
    sink(ctx, x >= 0.0f ? 1 : 0);
  }
  int consumed = (int)d->pos;
  if (consumed > d->pendLen) consumed = d->pendLen;
  if (consumed > 0) {
    memmove(d->pending, d->pending + consumed, (size_t)(d->pendLen - consumed) * sizeof(float));
    d->pendLen -= consumed;
    d->pos -= (float)consumed;
  }
}

// Tracks squelch state; returns whether the gate is open.
static bool ffskUpdateGate(FfskDemod *d, FfskBitSink sink, void *ctx) {
  if (d->gateOpen) {
    if (d->envelope < d->noiseFloor * FFSK_SQ_CLOSE) {
      if (++d->belowCount >= d->holdSamples) {
        d->gateOpen = false;
        ffskRecoverBits(d, sink, ctx);
        sink(ctx, -1);  // gate closed
        d->pendLen = 0;
        d->pos = 0.0f;
        d->lastSample = 0.0f;
        d->omega = d->omegaMid;
      }
    } else {
      d->belowCount = 0;
    }
  } else if (d->envelope > d->noiseFloor * FFSK_SQ_OPEN) {
    d->gateOpen = true;
    d->belowCount = 0;
  }
  return d->gateOpen;
}

static void ffskDemodProcess(FfskDemod *d, const int16_t *samples, int count, FfskBitSink sink, void *ctx) {
  const int n = FFSK_HILBERT_TAPS;
  for (int si = 0; si < count; si++) {
    float x = (float)samples[si] / 32768.0f;

    float dc = x - d->dcX1 + 0.995f * d->dcY1;
    d->dcX1 = x;
    d->dcY1 = dc;

    // Analytic signal: real part is the input delayed to the Hilbert
    // filter's group delay, imaginary part the filter out. Only odd-m taps
    // are nonzero (mid is even, so odd k).
    d->hist[d->histPos] = dc;
    d->histPos = (d->histPos + 1) % n;
    int base = d->histPos + n - 1;
    float im = 0.0f;
    for (int k = 1; k < n; k += 2) {
      int j = base - k;
      if (j >= n) j -= n;
      im += d->hilbertTaps[k] * d->hist[j];
    }
    int jr = d->histPos + n / 2;
    if (jr >= n) jr -= n;
    float re = d->hist[jr];

    // Envelope squelch: the floor follows the envelope down in ~50 ms and
    // up in ~5 s (so bursts do not raise it).
    float mag = sqrtf(re * re + im * im);
    d->envelope += (mag - d->envelope) * d->envAlpha;
    float alpha = (d->envelope < d->noiseFloor) ? d->floorFall : d->floorRise;
    d->noiseFloor += (d->envelope - d->noiseFloor) * alpha;
    if (d->noiseFloor < FFSK_SQ_FLOOR_MIN) d->noiseFloor = FFSK_SQ_FLOOR_MIN;

    // Instantaneous frequency of the analytic signal
    float cross = im * d->prevRe - re * d->prevIm;
    float dot = re * d->prevRe + im * d->prevIm;
    d->prevRe = re;
    d->prevIm = im;
    float freqHz = atan2f(cross, dot) * FFSK_FS / (2.0f * (float)M_PI);

    // Soft symbol: +1 at the mark tone, -1 at the space tone
    float softRaw = ((FFSK_MARK_HZ + FFSK_SPACE_HZ) / 2.0f - freqHz) / ((FFSK_SPACE_HZ - FFSK_MARK_HZ) / 2.0f);

    d->lpfHist[d->lpfPos] = softRaw;
    d->lpfPos = (d->lpfPos + 1) % FFSK_LPF_TAPS;
    float soft = 0.0f;
    for (int k = 0; k < FFSK_LPF_TAPS; k++) {
      soft += d->lpfTaps[k] * d->lpfHist[(d->lpfPos + k) % FFSK_LPF_TAPS];
    }

    if (ffskUpdateGate(d, sink, ctx)) {
      if (d->pendLen >= FFSK_PENDING_MAX) {
        ffskRecoverBits(d, sink, ctx);  // frees room; omega ~6.7 samples/bit
      }
      if (d->pendLen < FFSK_PENDING_MAX) {
        d->pending[d->pendLen++] = soft;
      }
    }
  }
  ffskRecoverBits(d, sink, ctx);
}

// --- access-code framer ---

#define FFSK_ACCESS_CODE 0xFC01u  // first-transmitted bit in the MSB
#define FFSK_CODE_TOLERANCE 1
#define FFSK_BURST_BITS 216       // 24 on-air bytes of 9 bits
#define FFSK_MIN_EMIT_BITS 45     // 5 on-air bytes

struct FfskFramer {
  uint16_t shift;
  uint32_t bitsSeen;
  bool collecting;
  uint8_t invert;
  uint8_t bits[FFSK_BURST_BITS];
  uint16_t len;
};

static void ffskFramerInit(FfskFramer *f) { memset(f, 0, sizeof(*f)); }

static bool ffskTakeBurst(FfskFramer *f, uint8_t *burst, uint16_t *burstLen) {
  bool emit = f->collecting && f->len >= FFSK_MIN_EMIT_BITS;
  if (emit) {
    memcpy(burst, f->bits, f->len);
    *burstLen = f->len;
  }
  f->collecting = false;
  f->len = 0;
  return emit;
}

static void ffskStartCollecting(FfskFramer *f) {
  // Distance between the code and its complement is 16, so the closer of
  // the two decides the polarity.
  f->invert = __builtin_popcount((f->shift ^ FFSK_ACCESS_CODE) & 0xFFFF) > 8 ? 1 : 0;
  f->collecting = true;
  f->len = 0;
}

// Feeds one sliced bit; returns true when a complete burst is emitted.
static bool ffskFramerPushBit(FfskFramer *f, uint8_t bit, uint8_t *burst, uint16_t *burstLen) {
  f->shift = (uint16_t)((f->shift << 1) | (bit & 1));
  f->bitsSeen++;

  if (f->collecting) {
    f->bits[f->len++] = bit ^ f->invert;
    if (f->len >= FFSK_BURST_BITS) {
      return ffskTakeBurst(f, burst, burstLen);
    }
    // A false trigger must not swallow a real telegram arriving inside its
    // collection window; restart on an exact code match only.
    if (f->bitsSeen >= 16 && (f->shift == FFSK_ACCESS_CODE || f->shift == (uint16_t)~FFSK_ACCESS_CODE)) {
      bool emitted = ffskTakeBurst(f, burst, burstLen);
      ffskStartCollecting(f);
      return emitted;
    }
    return false;
  }

  if (f->bitsSeen >= 16
      && (__builtin_popcount((f->shift ^ FFSK_ACCESS_CODE) & 0xFFFF) <= FFSK_CODE_TOLERANCE
          || __builtin_popcount((f->shift ^ (uint16_t)~FFSK_ACCESS_CODE) & 0xFFFF) <= FFSK_CODE_TOLERANCE)) {
    ffskStartCollecting(f);
  }
  return false;
}

// Call when the squelch gate closes: returns the partial burst if long
// enough, and resets all framing state.
static bool ffskFramerFlush(FfskFramer *f, uint8_t *burst, uint16_t *burstLen) {
  f->shift = 0;
  f->bitsSeen = 0;
  return ffskTakeBurst(f, burst, burstLen);
}

// --- CRC-16 (poly 0x16f63) with on-the-fly syndrome repair ---
// The telegram is a polynomial over GF(2): last byte = lowest-order
// coefficients, MSB of each byte = lowest power within the byte. The two
// CRC bytes are transmitted inverted (XOR 0xFF).

#define FFSK_CRC_POLY 0x16f63u
#define FFSK_MIN_TG 5
#define FFSK_MAX_TG 21

static uint16_t ffskAlphaPow[FFSK_MAX_TG * 8];  // x^n mod POLY

static void ffskCrcInit() {
  uint32_t v = 1;
  for (int i = 0; i < FFSK_MAX_TG * 8; i++) {
    ffskAlphaPow[i] = (uint16_t)v;
    v <<= 1;
    if (v & (1u << 16)) v ^= FFSK_CRC_POLY;
  }
}

static uint16_t ffskCrcRemainder(const uint8_t *data, int len) {
  uint16_t rem = 0;
  for (int i = 0; i < len; i++) {
    uint8_t byte = data[len - 1 - i];
    for (int j = 0; j < 8; j++) {
      if ((byte >> (7 - j)) & 1) rem ^= ffskAlphaPow[i * 8 + j];
    }
  }
  return rem;
}

static inline uint16_t ffskBitSyndrome(int len, int bit) {
  return ffskAlphaPow[(len - 1 - bit / 8) * 8 + 7 - (bit % 8)];
}

// Checks the CRC and repairs up to `maxRepair` (<=2) bit errors in place via
// syndrome search (the Rust reference precomputes hash tables; the search
// saves ~30 kB RAM and only runs on CRC failures). Returns the number of
// repaired bits, or -1 if unrecoverable.
static int ffskCheckAndRepair(uint8_t *t, int len, int maxRepair) {
  uint16_t rem = ffskCrcRemainder(t, len);
  if (rem == 0) return 0;
  if (len < FFSK_MIN_TG || len > FFSK_MAX_TG || maxRepair < 1) return -1;
  int nbits = len * 8;
  uint16_t syn[FFSK_MAX_TG * 8];
  for (int i = 0; i < nbits; i++) {
    syn[i] = ffskBitSyndrome(len, i);
    if (syn[i] == rem) {
      t[i / 8] ^= (uint8_t)(1u << (i % 8));
      return 1;
    }
  }
  if (maxRepair < 2) return -1;
  for (int i = 0; i < nbits; i++) {
    uint16_t want = syn[i] ^ rem;
    for (int j = i + 1; j < nbits; j++) {
      if (syn[j] == want) {
        t[i / 8] ^= (uint8_t)(1u << (i % 8));
        t[j / 8] ^= (uint8_t)(1u << (j % 8));
        return 2;
      }
    }
  }
  return -1;
}

static inline uint8_t ffskRev8(uint8_t b) {
  b = (uint8_t)((b >> 4) | (b << 4));
  b = (uint8_t)(((b & 0xCC) >> 2) | ((b & 0x33) << 2));
  b = (uint8_t)(((b & 0xAA) >> 1) | ((b & 0x55) << 1));
  return b;
}

// The two CRC bytes (already inverted for transmission) completing
// `payload` into a valid telegram. Used by the self-test modulator.
static void ffskCrcBytes(const uint8_t *payload, int len, uint8_t out[2]) {
  uint8_t tmp[FFSK_MAX_TG];
  memcpy(tmp, payload, len);
  tmp[len] = 0;
  tmp[len + 1] = 0;
  uint16_t rem = ffskCrcRemainder(tmp, len + 2);
  out[0] = ffskRev8((uint8_t)(rem >> 8)) ^ 0xff;
  out[1] = ffskRev8((uint8_t)rem) ^ 0xff;
}

// --- R09 structural validation (r09.rs parse_telegram) ---
// Used as the acceptance filter (especially for repaired frames) and to
// produce the local status label. The backend does the field-level decode.

static int ffskBcd(const uint8_t *digits, int n) {
  int acc = 0;
  for (int i = 0; i < n; i++) {
    if (digits[i] > 9) return -1;
    acc = acc * 10 + digits[i];
  }
  return acc;
}

static bool ffskParseR091x(const uint8_t *data, uint8_t *r09Type) {
  uint8_t lenNibble = data[1] & 0xf;
  if (lenNibble != 4 && lenNibble != 6) return false;
  *r09Type = lenNibble == 4 ? 14 : 16;
  uint8_t lineD[3] = {(uint8_t)(data[4] & 0xf), (uint8_t)(data[5] >> 4), (uint8_t)(data[5] & 0xf)};
  if (ffskBcd(lineD, 3) < 0) return false;
  uint8_t runD[2] = {(uint8_t)(data[6] >> 4), (uint8_t)(data[6] & 0xf)};
  if (ffskBcd(runD, 2) < 0) return false;
  if (*r09Type == 16) {
    uint8_t destD[3] = {(uint8_t)(data[7] >> 4), (uint8_t)(data[7] & 0xf), (uint8_t)(data[8] >> 4)};
    if (ffskBcd(destD, 3) < 0) return false;
  }
  return true;
}

// Conjectured vendor R09.0.7 invariants (see r09.rs annotate_r09_0_7);
// validity only — the label stays "R09.0.7".
static bool ffskR0907Valid(const uint8_t *data) {
  if (data[4] >> 4 > 9) return false;
  uint8_t routeD[3] = {(uint8_t)(data[4] & 0xf), (uint8_t)(data[5] >> 4), (uint8_t)(data[5] & 0xf)};
  if (ffskBcd(routeD, 3) < 0) return false;
  uint8_t runD[2] = {(uint8_t)(data[8] >> 4), (uint8_t)(data[8] & 0xf)};
  return ffskBcd(runD, 2) >= 0;
}

// `data`/`len` exclude the CRC bytes. Returns whether the telegram is
// structurally acceptable given the repair count, and writes a label.
static bool ffskAcceptTelegram(const uint8_t *data, int len, int repaired, char *label, size_t labelSz) {
  if (len < 3) return false;
  uint8_t mode = data[0] >> 4;

  if (mode == 9) {
    // R09.x: fixed 3-byte head plus a payload length in byte 1
    if (3 + (data[1] & 0xf) == len) {
      uint8_t r09Type = data[0] & 0xf;
      uint8_t r09Length = data[1] & 0xf;
      if (r09Type == 1) {
        uint8_t t;
        if (ffskParseR091x(data, &t)) {
          snprintf(label, labelSz, "R09.%u", t);
          return true;
        }
        // R09.14/16 with non-BCD digits is a miscorrection, not data
        if (r09Length == 4 || r09Length == 6) return false;
      }
      bool annotated = (r09Type == 0 && r09Length == 7 && ffskR0907Valid(data));
      // A repaired frame is only trusted when the R09.0.7 invariants hold;
      // anything else must pass the CRC exactly.
      if (repaired > 0 && !(annotated && data[2] == 0 && data[3] == 0)) return false;
      snprintf(label, labelSz, "R09.%u.%u", r09Type, r09Length);
      return true;
    }
    // C09.x: fixed 4-byte head plus a payload length in byte 2
    if (repaired == 0 && 4 + (data[2] & 0xf) == len) {
      snprintf(label, labelSz, "C09.%u.%u", data[2] >> 4, data[2] & 0xf);
      return true;
    }
    return false;
  }

  // Too little redundancy besides the CRC to trust a repaired frame here.
  if (repaired > 0) return false;
  if (len == 3) {
    snprintf(label, labelSz, "R%02u", mode);
    return true;
  }
  if (len == 4) {
    snprintf(label, labelSz, "C%02u", mode);
    return true;
  }
  return false;
}

// --- burst decode (r09.rs decode_burst) ---

struct FfskResult {
  uint8_t bytes[FFSK_MAX_TG];  // telegram incl. CRC in de-inverted (checkable) form
  uint8_t len;
  uint8_t repaired;
  char label[16];
};

#define FFSK_MAX_RESULTS 4

// Decodes every telegram in a burst of sliced bits (the bits following the
// access code). On air each byte is 9 bits: 8 data bits LSB-first plus one
// fill bit; length is unsignalled, so every plausible bit offset and length
// is tried and the CRC + structure checks single out real frames.
static int ffskDecodeBurst(const uint8_t *bits, int nbits, FfskResult *out, int maxOut) {
  int found = 0;
  for (int offset = 0; offset < 3; offset++) {
    if (nbits <= offset) break;
    const uint8_t *b = bits + offset;
    int nb = nbits - offset;
    uint8_t byteArray[FFSK_BURST_BITS / 9];
    int nBytes = nb / 9;
    for (int i = 0; i < nBytes; i++) {
      uint8_t v = 0;
      for (int j = 0; j < 8; j++) v |= (uint8_t)((b[i * 9 + j] & 1) << j);
      byteArray[i] = v;
    }
    int maxLen = nBytes < FFSK_MAX_TG ? nBytes : FFSK_MAX_TG;
    for (int len = FFSK_MIN_TG; len <= maxLen; len++) {
      uint8_t cand[FFSK_MAX_TG];
      memcpy(cand, byteArray, len);
      cand[len - 2] ^= 0xff;  // CRC bytes are transmitted inverted
      cand[len - 1] ^= 0xff;
      int repaired = ffskCheckAndRepair(cand, len, 2);
      if (repaired < 0) continue;
      char label[16];
      if (!ffskAcceptTelegram(cand, len - 2, repaired, label, sizeof(label))) continue;

      // Dedup overlapping length/offset hits, keeping the least-repaired.
      bool dup = false;
      for (int k = 0; k < found; k++) {
        if (out[k].len == len && memcmp(out[k].bytes, cand, len) == 0) {
          if (repaired < out[k].repaired) out[k].repaired = (uint8_t)repaired;
          dup = true;
          break;
        }
      }
      if (!dup && found < maxOut) {
        memcpy(out[found].bytes, cand, len);
        out[found].len = (uint8_t)len;
        out[found].repaired = (uint8_t)repaired;
        strncpy(out[found].label, label, sizeof(out[found].label) - 1);
        out[found].label[sizeof(out[found].label) - 1] = '\0';
        found++;
      }
    }
  }
  return found;
}

// --- boot self-test (port of tests/roundtrip.rs) ---
#ifdef DECODER_SELFTEST

struct FfskSelfTestCtx {
  FfskFramer framer;
  FfskResult results[FFSK_MAX_RESULTS];
  int nResults;
};

static void ffskSelfTestSink(void *vctx, int bit) {
  FfskSelfTestCtx *c = (FfskSelfTestCtx *)vctx;
  uint8_t burst[FFSK_BURST_BITS];
  uint16_t burstLen = 0;
  bool have = (bit < 0) ? ffskFramerFlush(&c->framer, burst, &burstLen)
                        : ffskFramerPushBit(&c->framer, (uint8_t)bit, burst, &burstLen);
  if (have && c->nResults < FFSK_MAX_RESULTS) {
    c->nResults += ffskDecodeBurst(burst, burstLen, c->results + c->nResults,
                                   FFSK_MAX_RESULTS - c->nResults);
  }
}

// Synthesizes the reference R09.16 telegram (line 62, run 7, dest 213) as
// phase-continuous FFSK at 16 kHz and runs it through the full chain.
static bool ffskSelfTest() {
  static const uint8_t payload[9] = {0x91, 0x26, 0x33, 0x02, 0x40, 0x62, 0x07, 0x21, 0x30};
  uint8_t onAir[11];
  memcpy(onAir, payload, 9);
  ffskCrcBytes(payload, 9, onAir + 9);

  // preamble + access code + 9-bit on-air bytes (LSB-first + fill bit)
  uint8_t bits[24 + 10 + 11 * 9];
  int nb = 0;
  for (int i = 0; i < 24; i++) bits[nb++] = 1;
  static const uint8_t code[10] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
  for (int i = 0; i < 10; i++) bits[nb++] = code[i];
  for (int i = 0; i < 11; i++) {
    for (int j = 0; j < 8; j++) bits[nb++] = (onAir[i] >> j) & 1;
    bits[nb++] = 1;
  }

  FfskDemod demod;
  ffskDemodInit(&demod);
  FfskSelfTestCtx ctx;
  ffskFramerInit(&ctx.framer);
  ctx.nResults = 0;

  // 0.3 s faint noise so the squelch learns a floor, then the telegram,
  // then silence so the gate closes and the framer flushes.
  uint32_t rng = 0x12345678;
  int16_t chunk[256];
  int silence = (int)(0.3f * FFSK_FS);
  int sampleTotal = (int)ceilf(nb * FFSK_FS / FFSK_BAUD);
  float phase = 0.0f;
  int produced = 0;
  for (int seg = 0; seg < 3; seg++) {
    int segLen = seg == 1 ? sampleTotal : silence;
    for (int i = 0; i < segLen;) {
      int n = segLen - i < 256 ? segLen - i : 256;
      for (int k = 0; k < n; k++) {
        if (seg == 1) {
          int bitIndex = (int)((float)produced * FFSK_BAUD / FFSK_FS);
          if (bitIndex >= nb) bitIndex = nb - 1;
          float freq = bits[bitIndex] ? 1200.0f : 2400.0f;
          phase += 2.0f * (float)M_PI * freq / FFSK_FS;
          chunk[k] = (int16_t)(0.5f * 30000.0f * sinf(phase));
          produced++;
        } else {
          rng ^= rng << 13;
          rng ^= rng >> 17;
          rng ^= rng << 5;
          chunk[k] = (int16_t)((int32_t)(rng & 0xFF) - 128);  // faint idle noise
        }
      }
      ffskDemodProcess(&demod, chunk, n, ffskSelfTestSink, &ctx);
      i += n;
    }
  }

  for (int i = 0; i < ctx.nResults; i++) {
    const FfskResult &r = ctx.results[i];
    if (strcmp(r.label, "R09.16") == 0 && r.len == 11 && r.repaired == 0
        && memcmp(r.bytes, payload, 9) == 0
        && r.bytes[9] == (uint8_t)(onAir[9] ^ 0xff) && r.bytes[10] == (uint8_t)(onAir[10] ^ 0xff)
        && ffskCrcRemainder(r.bytes, 11) == 0) {
      return true;
    }
  }
  return false;
}

#endif  // DECODER_SELFTEST
