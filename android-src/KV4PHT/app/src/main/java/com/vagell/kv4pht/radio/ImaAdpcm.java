/*
 * KV4P-HT (see http://kv4p.com)
 * Copyright (C) 2025 Vance Vagell
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.vagell.kv4pht.radio;

public final class ImaAdpcm {
    private static final int[] INDEX_TABLE = {
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
    };

    private static final int[] STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    private ImaAdpcm() {}

    public static int encodedSize(int samples) {
        return 4 + samples / 2;
    }

    public static int encodeBlock(short[] pcm, int pcmOffset, int samples, byte[] adpcm, int adpcmOffset) {
        if (samples <= 0) {
            return 0;
        }

        int[] state = {pcm[pcmOffset], 0};
        adpcm[adpcmOffset] = (byte) state[0];
        adpcm[adpcmOffset + 1] = (byte) (state[0] >> 8);
        adpcm[adpcmOffset + 2] = (byte) state[1];
        adpcm[adpcmOffset + 3] = 0;

        int out = adpcmOffset + 4;
        boolean highNibble = false;
        int packed = 0;
        for (int i = 1; i < samples; i++) {
            int code = encodeNibble(pcm[pcmOffset + i], state);
            if (!highNibble) {
                packed = code & 0x0f;
                highNibble = true;
            } else {
                adpcm[out++] = (byte) (packed | (code << 4));
                highNibble = false;
            }
        }
        if (highNibble) {
            adpcm[out++] = (byte) packed;
        }
        return out - adpcmOffset;
    }

    public static int decodeBlock(byte[] adpcm, int adpcmOffset, int len, short[] pcm, int pcmOffset, int samples) {
        if (len < 4 || samples <= 0) {
            return 0;
        }

        int[] state = {
                (short) ((adpcm[adpcmOffset] & 0xff) | ((adpcm[adpcmOffset + 1] & 0xff) << 8)),
                clampIndex(adpcm[adpcmOffset + 2] & 0xff)
        };
        int written = 0;
        pcm[pcmOffset + written++] = (short) state[0];

        for (int i = adpcmOffset + 4; i < adpcmOffset + len && written < samples; i++) {
            int packed = adpcm[i] & 0xff;
            decodeNibble(packed & 0x0f, state);
            pcm[pcmOffset + written++] = (short) state[0];
            if (written >= samples) {
                break;
            }
            decodeNibble((packed >> 4) & 0x0f, state);
            pcm[pcmOffset + written++] = (short) state[0];
        }
        return written;
    }

    private static int encodeNibble(short sample, int[] state) {
        int predictor = state[0];
        int index = state[1];
        int step = STEP_TABLE[index];
        int diff = sample - predictor;
        int code = 0;
        if (diff < 0) {
            code = 8;
            diff = -diff;
        }

        int delta = step >> 3;
        if (diff >= step) {
            code |= 4;
            diff -= step;
            delta += step;
        }
        if (diff >= (step >> 1)) {
            code |= 2;
            diff -= step >> 1;
            delta += step >> 1;
        }
        if (diff >= (step >> 2)) {
            code |= 1;
            delta += step >> 2;
        }

        predictor += (code & 8) != 0 ? -delta : delta;
        state[0] = clampInt16(predictor);
        state[1] = clampIndex(index + INDEX_TABLE[code]);
        return code;
    }

    private static void decodeNibble(int code, int[] state) {
        int predictor = state[0];
        int index = state[1];
        int step = STEP_TABLE[index];
        int delta = step >> 3;
        if ((code & 4) != 0) {
            delta += step;
        }
        if ((code & 2) != 0) {
            delta += step >> 1;
        }
        if ((code & 1) != 0) {
            delta += step >> 2;
        }

        predictor += (code & 8) != 0 ? -delta : delta;
        state[0] = clampInt16(predictor);
        state[1] = clampIndex(index + INDEX_TABLE[code & 0x0f]);
    }

    private static int clampInt16(int sample) {
        if (sample > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (sample < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return sample;
    }

    private static int clampIndex(int index) {
        if (index < 0) {
            return 0;
        }
        if (index > 88) {
            return 88;
        }
        return index;
    }
}
