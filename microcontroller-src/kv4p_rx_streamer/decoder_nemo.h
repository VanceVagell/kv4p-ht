/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

// NEMO / VicosLio (G2) telegram decoder — best-effort port of the validated
// on-air chain in ../lio-decoder (src/bast_chain.py demod() front-end,
// clock_recovery_mm, and the HDLC framing + CRC acceptance from
// src/bast_chain.py frames() / src/decode_air.py extract_frames()).
//
// Physical layer (patent EP 0566773): the FM baseband carries AMI-coded
// cos^2 half-wave pulses of a ~2400 Hz tone at 4800 Bd (mark = pulse,
// space = gap). Front-end (streaming, 48 kHz in):
//   x - moving_avg(x,200) -> |x| -> freq-xlate @2400 Hz + 43-tap FIR,
//   decimate by 2 -> |z| -> moving_avg(5)  => envelope @ 24 kHz
// Bursts are gated by an adaptive energy detector into a buffer; on burst
// end: median bias removal -> Mueller & Muller clock recovery (sps=5) ->
// HDLC deframe (EOF = run of six 1s, destuff, byte-offset sweep) -> CRC.
//
// Two CRC conventions close on real captures (verified against the Python
// references): LSB-first bytes + X.25 FCS (LE trailer) — what the backend
// re-verifies — and MSB-first bytes + CRC-16/CCITT 0x1021 with the
// decode_air.py (init,xorout) variants. Both are accepted and uploaded;
// the server's crc_ok decides what drives the map.
//
// Pure stdint/math — no Arduino dependencies, host-buildable.

#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <math.h>

#define NEMO_FS        48000.0f
#define NEMO_FC        2400.0f
#define NEMO_DC_LEN    200   // moving-average DC removal window @48k
#define NEMO_XL_TAPS   43    // firwin(43, 4000/(fs/2)) — cutoff is load-bearing
#define NEMO_XL_CUTOFF 4000.0f
#define NEMO_NCO_LEN   20    // 2400/48000 = 1/20: the NCO cycles in 20 steps
#define NEMO_SPS       5.0f  // 24000 / 4800

#define NEMO_ENV_MAX   9000  // 375 ms of 24 kHz envelope (int16) = 18 kB
#define NEMO_PRE_ENV   256   // ~10.7 ms preroll kept before gate-open
#define NEMO_MAX_BITS  2048
#define NEMO_MIN_BURST 1920  // 80 ms @ 24 kHz, like the reference pipeline
#define NEMO_HOLD_ENV  480   // 20 ms sustained-below before the gate closes
#define NEMO_FRAME_MAX 32
#define NEMO_MIN_SEG   16    // bits; decode_air extract_frames min_seg

struct NemoDecoder {
  // DC removal (sliding sum) on the raw 48 kHz samples
  int32_t dcSum;
  int16_t dcHist[NEMO_DC_LEN];
  int dcPos;
  // NCO mix + FIR history (complex), consumed at the decimated rate
  float ncoCos[NEMO_NCO_LEN], ncoSin[NEMO_NCO_LEN];
  int ncoPhase;
  float taps[NEMO_XL_TAPS];
  float xr[NEMO_XL_TAPS], xi[NEMO_XL_TAPS];
  int xPos;
  int decimPhase;
  // moving_avg(5) on the magnitude
  float ma5[5];
  float ma5Sum;
  int ma5Pos;
  // burst gate (normalized envelope, adaptive floor like the FFSK squelch)
  float actEnv, actAlpha, actFloor, floorRise, floorFall;
  bool bursting;
  int belowCount;
  // envelope capture
  int16_t preRing[NEMO_PRE_ENV];
  int prePos, preFill;
  int16_t *envBuf;  // NEMO_ENV_MAX entries, allocated by the caller
  int envLen;
};

// frame sink: `how` names the CRC convention that closed ("x25" or "ccitt")
typedef void (*NemoFrameSink)(void *ctx, const uint8_t *frame, int len, const char *how);

static void nemoInit(NemoDecoder *d, int16_t *envBuf) {
  memset(d, 0, sizeof(*d));
  d->envBuf = envBuf;
  for (int k = 0; k < NEMO_NCO_LEN; k++) {
    d->ncoCos[k] = cosf(-2.0f * (float)M_PI * k / NEMO_NCO_LEN);
    d->ncoSin[k] = sinf(-2.0f * (float)M_PI * k / NEMO_NCO_LEN);
  }
  // windowed-sinc (Hamming) low-pass like scipy.signal.firwin, unity DC gain
  const float fc = NEMO_XL_CUTOFF / NEMO_FS;
  float sum = 0.0f;
  for (int k = 0; k < NEMO_XL_TAPS; k++) {
    float m = k - (NEMO_XL_TAPS - 1) / 2.0f;
    float sinc = (m == 0.0f) ? 2.0f * fc : sinf(2.0f * (float)M_PI * fc * m) / ((float)M_PI * m);
    float w = 0.54f - 0.46f * cosf(2.0f * (float)M_PI * k / (NEMO_XL_TAPS - 1.0f));
    d->taps[k] = sinc * w;
    sum += d->taps[k];
  }
  for (int k = 0; k < NEMO_XL_TAPS; k++) d->taps[k] /= sum;
  const float fs2 = NEMO_FS / 2.0f;  // 24 kHz envelope rate
  d->actAlpha = 1.0f - expf(-1.0f / (fs2 * 0.005f));
  d->actFloor = 0.05f;
  d->floorRise = 1.0f - expf(-1.0f / (fs2 * 5.0f));
  d->floorFall = 1.0f - expf(-1.0f / (fs2 * 0.05f));
}

// --- CRC conventions ---

// X.25 FCS: reflected poly 0x8408, init 0xFFFF, xorout 0xFFFF, LE trailer.
static uint16_t nemoCrcX25(const uint8_t *p, int n) {
  uint16_t crc = 0xFFFF;
  for (int i = 0; i < n; i++) {
    crc ^= p[i];
    for (int b = 0; b < 8; b++) crc = (crc & 1) ? (uint16_t)((crc >> 1) ^ 0x8408) : (uint16_t)(crc >> 1);
  }
  return crc ^ 0xFFFF;
}

// CRC-16/CCITT poly 0x1021, MSB-first, non-reflected, BE trailer.
static uint16_t nemoCrcCcitt(const uint8_t *p, int n, uint16_t init, uint16_t xorout) {
  uint16_t crc = init;
  for (int i = 0; i < n; i++) {
    crc ^= (uint16_t)(p[i] << 8);
    for (int b = 0; b < 8; b++) crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
  }
  return crc ^ xorout;
}

static bool nemoX25Ok(const uint8_t *fb, int n) {
  return n >= 4 && nemoCrcX25(fb, n - 2) == (uint16_t)(fb[n - 2] | (fb[n - 1] << 8));
}

// (init, xorout) variants seen to close on real frames; (0,0) excluded —
// it false-positives on near-zero data (see decode_air.py).
static bool nemoCcittOk(const uint8_t *fb, int n) {
  static const uint16_t variants[3][2] = {{0x0000, 0xFFFF}, {0xFFFF, 0xFFFF}, {0xFFFF, 0x0000}};
  if (n < 4) return false;
  uint16_t recv = (uint16_t)((fb[n - 2] << 8) | fb[n - 1]);
  for (int v = 0; v < 3; v++) {
    if (nemoCrcCcitt(fb, n - 2, variants[v][0], variants[v][1]) == recv) return true;
  }
  return false;
}

// >2 distinct byte values, so constant/degenerate segments don't pass
// (bast_chain.py frames() uses the same guard).
static bool nemoFrameNontrivial(const uint8_t *fb, int n) {
  uint8_t first = fb[0], second = 0;
  bool haveSecond = false;
  for (int i = 1; i < n; i++) {
    if (fb[i] == first) continue;
    if (!haveSecond) { second = fb[i]; haveSecond = true; continue; }
    if (fb[i] != second) return true;
  }
  return false;
}

// --- burst back-end ---

// Approximate median of the burst envelope via a 256-bin histogram
// (documented deviation from the reference's exact median).
static float nemoMedian(const int16_t *env, int n) {
  uint16_t hist[256];
  memset(hist, 0, sizeof(hist));
  for (int i = 0; i < n; i++) {
    int v = env[i] < 0 ? 0 : env[i];
    hist[v >> 7]++;
  }
  int half = n / 2, acc = 0;
  for (int b = 0; b < 256; b++) {
    acc += hist[b];
    if (acc >= half) return (float)((b << 7) + 64);
  }
  return 0.0f;
}

// Faithful gr::digital::clock_recovery_mm_ff port (bast_chain.py):
// cubic Catmull-Rom interpolation, omega=sps=5, gain_omega=7.65625e-3,
// mu=0.5, gain_mu=0.175, omega_rel=5e-3. Values are normalized to the
// int16 scale so the +-1 error clamp behaves like the float reference.
static int nemoClockRecover(const int16_t *env, int n, float median, uint8_t *bits, int maxBits) {
  const float scale = 1.0f / 32768.0f;
  float omegaMid = NEMO_SPS, omega = NEMO_SPS;
  const float omegaLim = 5e-3f * omegaMid;
  int ii = 2;
  float mu = 0.5f, last = 0.0f;
  int nbits = 0;
  while (ii < n - 2 && nbits < maxBits) {
    if (ii < 1) break;
    float t = mu;
    float p0 = ((float)env[ii - 1] - median) * scale;
    float p1 = ((float)env[ii] - median) * scale;
    float p2 = ((float)env[ii + 1] - median) * scale;
    float p3 = ((float)env[ii + 2] - median) * scale;
    float y = p1 + 0.5f * t * (p2 - p0 + t * (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3 + t * (3.0f * (p1 - p2) + p3 - p0)));
    float dY = y > 0.0f ? 1.0f : -1.0f;
    float dLo = last > 0.0f ? 1.0f : -1.0f;
    float err = dLo * y - dY * last;
    if (err > 1.0f) err = 1.0f;
    if (err < -1.0f) err = -1.0f;
    bits[nbits++] = y > 0.0f ? 1 : 0;
    last = y;
    omega += 7.65625e-3f * err;
    float dev = omega - omegaMid;
    if (dev > omegaLim) dev = omegaLim;
    if (dev < -omegaLim) dev = -omegaLim;
    omega = omegaMid + dev;
    mu += omega + 0.175f * err;
    ii += (int)mu;
    mu -= (float)(int)mu;
  }
  return nbits;
}

// Remove a 0 that follows exactly five consecutive 1s (HDLC destuffing).
static int nemoDestuff(const uint8_t *bits, int n, uint8_t *out) {
  int ones = 0, m = 0;
  for (int i = 0; i < n; i++) {
    uint8_t ch = bits[i];
    if (ones == 5) {
      ones = 0;
      if (ch == 0) continue;
    }
    out[m++] = ch;
    ones = ch ? ones + 1 : 0;
  }
  return m;
}

// One destuffed segment: sweep byte offsets 0..7 under both conventions.
static void nemoTrySegment(const uint8_t *ds, int dsLen, NemoFrameSink sink, void *ctx) {
  for (int off = 0; off < 8; off++) {
    int nby = (dsLen - off) / 8;
    if (nby < 4 || nby > NEMO_FRAME_MAX) continue;
    uint8_t lsb[NEMO_FRAME_MAX], msb[NEMO_FRAME_MAX];
    for (int i = 0; i < nby; i++) {
      uint8_t vl = 0, vm = 0;
      for (int j = 0; j < 8; j++) {
        uint8_t b = ds[off + i * 8 + j] & 1;
        vl |= (uint8_t)(b << j);
        vm |= (uint8_t)(b << (7 - j));
      }
      lsb[i] = vl;
      msb[i] = vm;
    }
    if (nemoFrameNontrivial(lsb, nby) && nemoX25Ok(lsb, nby)) sink(ctx, lsb, nby, "x25");
    if (nemoFrameNontrivial(msb, nby) && nemoCcittOk(msb, nby)) sink(ctx, msb, nby, "ccitt");
  }
}

// Deframe one polarity: split on runs of >=6 ones (EOF/preamble tone),
// destuff each segment, try the CRC conventions.
static void nemoFramesForPolarity(const uint8_t *bits, int nbits, uint8_t pol, NemoFrameSink sink, void *ctx) {
  static uint8_t ds[NEMO_MAX_BITS];  // single consumer task; keep off its stack
  int i = 0;
  while (i < nbits) {
    int segStart = i, segEnd = nbits, ones = 0;
    int j = i;
    for (; j < nbits; j++) {
      if ((bits[j] ^ pol) & 1) {
        if (++ones == 6) {
          segEnd = j - 5;
          break;
        }
      } else {
        ones = 0;
      }
    }
    if (segEnd - segStart >= NEMO_MIN_SEG) {
      int m = 0;
      // destuff on the polarity-adjusted view
      int ones2 = 0;
      for (int k = segStart; k < segEnd; k++) {
        uint8_t ch = (bits[k] ^ pol) & 1;
        if (ones2 == 5) {
          ones2 = 0;
          if (ch == 0) continue;
        }
        ds[m++] = ch;
        ones2 = ch ? ones2 + 1 : 0;
      }
      nemoTrySegment(ds, m, sink, ctx);
    }
    if (j >= nbits) break;
    j++;
    while (j < nbits && ((bits[j] ^ pol) & 1)) j++;
    i = j;
  }
}

static void nemoProcessBurst(NemoDecoder *d, NemoFrameSink sink, void *ctx) {
  static uint8_t bits[NEMO_MAX_BITS];
  float median = nemoMedian(d->envBuf, d->envLen);
  int nbits = nemoClockRecover(d->envBuf, d->envLen, median, bits, NEMO_MAX_BITS);
  if (nbits < NEMO_MIN_SEG) return;
  // pol=1 (inverted) is the validated convention; try true polarity too.
  nemoFramesForPolarity(bits, nbits, 1, sink, ctx);
  nemoFramesForPolarity(bits, nbits, 0, sink, ctx);
}

// Streaming feed of raw 48 kHz samples. Returns the number of bursts that
// were finalized (and pushed through the sink) during this call.
static int nemoFeed(NemoDecoder *d, const int16_t *s48, int n, NemoFrameSink sink, void *ctx) {
  if (!d->envBuf) return 0;
  int burstsDone = 0;
  for (int si = 0; si < n; si++) {
    int16_t x = s48[si];
    d->dcSum += x - d->dcHist[d->dcPos];
    d->dcHist[d->dcPos] = x;
    d->dcPos = (d->dcPos + 1) % NEMO_DC_LEN;
    float dc = (float)x - (float)d->dcSum / NEMO_DC_LEN;
    float y = fabsf(dc);

    float c = d->ncoCos[d->ncoPhase], s = d->ncoSin[d->ncoPhase];
    d->ncoPhase = (d->ncoPhase + 1) % NEMO_NCO_LEN;
    d->xr[d->xPos] = y * c;
    d->xi[d->xPos] = y * s;
    d->xPos = (d->xPos + 1) % NEMO_XL_TAPS;

    if (++d->decimPhase < 2) continue;  // 48 kHz -> 24 kHz
    d->decimPhase = 0;

    float zr = 0.0f, zi = 0.0f;
    int base = d->xPos + NEMO_XL_TAPS - 1;
    for (int k = 0; k < NEMO_XL_TAPS; k++) {
      int idx = base - k;
      if (idx >= NEMO_XL_TAPS) idx -= NEMO_XL_TAPS;
      zr += d->taps[k] * d->xr[idx];
      zi += d->taps[k] * d->xi[idx];
    }
    float mag = sqrtf(zr * zr + zi * zi);

    d->ma5Sum += mag - d->ma5[d->ma5Pos];
    d->ma5[d->ma5Pos] = mag;
    d->ma5Pos = (d->ma5Pos + 1) % 5;
    float env = d->ma5Sum / 5.0f;
    int16_t envQ = env > 32767.0f ? 32767 : (int16_t)env;

    // burst gate on the normalized envelope
    float norm = env / 32768.0f;
    d->actEnv += (norm - d->actEnv) * d->actAlpha;
    float alpha = (d->actEnv < d->actFloor) ? d->floorFall : d->floorRise;
    d->actFloor += (d->actEnv - d->actFloor) * alpha;
    if (d->actFloor < 1e-5f) d->actFloor = 1e-5f;

    if (!d->bursting) {
      d->preRing[d->prePos] = envQ;
      d->prePos = (d->prePos + 1) % NEMO_PRE_ENV;
      if (d->preFill < NEMO_PRE_ENV) d->preFill++;
      if (d->actEnv > d->actFloor * 4.0f) {
        d->bursting = true;
        d->belowCount = 0;
        d->envLen = 0;
        for (int k = d->preFill; k > 0; k--) {
          int idx = d->prePos - k;
          if (idx < 0) idx += NEMO_PRE_ENV;
          d->envBuf[d->envLen++] = d->preRing[idx];
        }
      }
      continue;
    }

    d->envBuf[d->envLen++] = envQ;
    bool finalize = false;
    if (d->actEnv < d->actFloor * 2.0f) {
      if (++d->belowCount >= NEMO_HOLD_ENV) finalize = true;
    } else {
      d->belowCount = 0;
    }
    if (d->envLen >= NEMO_ENV_MAX) finalize = true;  // buffer full: decode what we have

    if (finalize) {
      bool keepBursting = d->envLen >= NEMO_ENV_MAX && d->belowCount < NEMO_HOLD_ENV;
      if (d->envLen >= NEMO_MIN_BURST) {
        nemoProcessBurst(d, sink, ctx);
        burstsDone++;
      }
      d->envLen = 0;
      d->bursting = keepBursting;
      d->belowCount = 0;
      d->preFill = 0;
      d->prePos = 0;
    }
  }
  return burstsDone;
}

// --- boot self-test: CRC conventions against known-good captured frames ---
#ifdef DECODER_SELFTEST

static bool nemoSelfTest() {
  // GPS dwell frame from decode_air.py: closes CCITT (0x0000, 0xFFFF) only.
  static const uint8_t gps[15] = {0x00, 0x00, 0x0e, 0xd5, 0x09, 0x80, 0x80, 0x00,
                                  0x00, 0x00, 0x00, 0x00, 0x00, 0xf8, 0xe9};
  // Vamos 5020 beacon (bielefeld-live golden frame): closes X.25 only.
  static const uint8_t beacon[20] = {0x14, 0x00, 0x00, 0xa3, 0x4e, 0x04, 0x05, 0x6d, 0x07, 0x1a,
                                     0x01, 0x58, 0x3a, 0x00, 0x7c, 0xc1, 0xff, 0x0f, 0xab, 0xa7};
  return nemoCcittOk(gps, sizeof(gps)) && !nemoX25Ok(gps, sizeof(gps))
         && nemoX25Ok(beacon, sizeof(beacon)) && !nemoCcittOk(beacon, sizeof(beacon));
}

#endif  // DECODER_SELFTEST
