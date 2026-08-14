package com.vagell.kv4pht.radio;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
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

    @Test
    public void statefulEncoderMatchesFfmpegAcrossBlocks() {
        // Generated with FFmpeg 8.1.2 from deterministicPcm(498), using:
        // ffmpeg -f s16le -ar 16000 -ac 1 -i input.pcm -c:a adpcm_ima_wav -block_size 128 vector.wav
        short[] pcm = deterministicPcm(498);
        byte[] actual = new byte[256];
        ImaAdpcm.Encoder encoder = new ImaAdpcm.Encoder();

        assertEquals(128, encoder.encodeBlock(pcm, 0, 249, actual, 0));
        assertEquals(128, encoder.encodeBlock(pcm, 249, 249, actual, 128));

        assertArrayEquals(fromHex(
                "589e00007777777715101121223233343434334433ffbf001000100111121223f3ff08100000010111f18f000001101010f10e0000011001f10c0001011111df0001101011bf10100112f10d010101f11a001111f11b101111af101011f20a011112af101111af1110119f0101118f1010018f0101f11810118f0111f1181011" +
                "edae5200112231af11219f11208f1110f11010f11010f11011f110101f01110f11110f11f101110e11111f10e110110e11e101211f10f211111f11f111111f11e111e101121f11e111e111111e111e111d11e111e111d111111e111d111d111e121e211e211e211e211e211e211e121dd111e221d111e2211d211e121dd211e2"),
                actual);
    }

    @Test
    public void resetStartsNextBlockAtStepIndexZero() {
        short[] pcm = deterministicPcm(498);
        byte[] block = new byte[128];
        ImaAdpcm.Encoder encoder = new ImaAdpcm.Encoder();

        encoder.encodeBlock(pcm, 0, 249, block, 0);
        encoder.reset();
        encoder.encodeBlock(pcm, 249, 249, block, 0);

        assertEquals(0, block[2] & 0xff);
    }

    private static short[] deterministicPcm(int samples) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) (((i * 997 + i * i * 13) % 50001) - 25000);
        }
        return pcm;
    }

    private static byte[] fromHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
