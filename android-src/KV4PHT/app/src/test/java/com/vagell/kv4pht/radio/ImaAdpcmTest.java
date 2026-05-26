package com.vagell.kv4pht.radio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImaAdpcmTest {
    @Test
    public void encodedSizeIncludesBlockHeaderAndPackedNibbles() {
        assertEquals(128, ImaAdpcm.encodedSize(249));
    }

    @Test
    public void encodeDecodeReportsExpectedLengths() {
        short[] pcm = new short[249];
        byte[] adpcm = new byte[ImaAdpcm.encodedSize(pcm.length)];
        short[] decoded = new short[pcm.length];

        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (i * 40 - 4000);
        }

        assertEquals(adpcm.length, ImaAdpcm.encodeBlock(pcm, 0, pcm.length, adpcm, 0));
        assertEquals(pcm.length, ImaAdpcm.decodeBlock(adpcm, 0, adpcm.length, decoded, 0, decoded.length));
        assertEquals(pcm[0], decoded[0]);
    }

    @Test
    public void roundTripIsApproximatelyCorrectForSpeechLevelRamp() {
        short[] pcm = new short[249];
        byte[] adpcm = new byte[ImaAdpcm.encodedSize(pcm.length)];
        short[] decoded = new short[pcm.length];

        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(i / 12.0) * 8000);
        }

        ImaAdpcm.encodeBlock(pcm, 0, pcm.length, adpcm, 0);
        ImaAdpcm.decodeBlock(adpcm, 0, adpcm.length, decoded, 0, decoded.length);

        long totalError = 0;
        for (int i = 0; i < pcm.length; i++) {
            totalError += Math.abs(pcm[i] - decoded[i]);
        }

        assertTrue(totalError / pcm.length < 2500);
    }
}
