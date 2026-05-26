/*
kv4p HT (see http://kv4p.com)
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

package com.vagell.kv4pht.radio;

public final class G711Ulaw {
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;
    private static final short[] DECODE_TABLE = buildDecodeTable();

    private G711Ulaw() {}

    public static byte encode(short sample) {
        int pcm = sample;
        int sign = (pcm < 0) ? 0x80 : 0x00;
        if (pcm < 0) {
            pcm = -pcm;
            if (pcm < 0) {
                pcm = CLIP;
            }
        }
        if (pcm > CLIP) {
            pcm = CLIP;
        }
        pcm += BIAS;

        int exponent = 7;
        for (int mask = 0x4000; (pcm & mask) == 0 && exponent > 0; mask >>= 1) {
            exponent--;
        }
        int mantissa = (pcm >> (exponent + 3)) & 0x0F;
        return (byte) (~(sign | (exponent << 4) | mantissa) & 0xFF);
    }

    public static short decode(byte ulaw) {
        return DECODE_TABLE[ulaw & 0xFF];
    }

    public static int encode(short[] pcm, int pcmOffset, byte[] ulaw, int ulawOffset, int samples) {
        for (int i = 0; i < samples; i++) {
            ulaw[ulawOffset + i] = encode(pcm[pcmOffset + i]);
        }
        return samples;
    }

    public static int decode(byte[] ulaw, int ulawOffset, short[] pcm, int pcmOffset, int samples) {
        for (int i = 0; i < samples; i++) {
            pcm[pcmOffset + i] = decode(ulaw[ulawOffset + i]);
        }
        return samples;
    }

    private static short[] buildDecodeTable() {
        short[] table = new short[256];
        for (int i = 0; i < table.length; i++) {
            int ulaw = ~i & 0xFF;
            int sign = ulaw & 0x80;
            int exponent = (ulaw >> 4) & 0x07;
            int mantissa = ulaw & 0x0F;
            int sample = ((mantissa << 3) + BIAS) << exponent;
            sample -= BIAS;
            table[i] = (short) (sign != 0 ? -sample : sample);
        }
        return table;
    }
}
