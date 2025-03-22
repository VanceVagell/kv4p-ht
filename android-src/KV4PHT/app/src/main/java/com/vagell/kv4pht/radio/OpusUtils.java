package com.vagell.kv4pht.radio;

import io.github.jaredmdobson.OpusApplication;
import io.github.jaredmdobson.OpusDecoder;
import io.github.jaredmdobson.OpusEncoder;
import io.github.jaredmdobson.OpusException;

public final class OpusUtils {
    private OpusUtils() {}

    public static class OpusDecoderWrapper {
        private final OpusDecoder decoder;
        private final short[] pcmShorts; // Preallocated buffer
        private final int frameSize;

        public OpusDecoderWrapper(int sampleRate, int frameSize) {
            try {
                this.decoder = new OpusDecoder(sampleRate, 1); // Mono
                this.frameSize = frameSize;
                this.pcmShorts = new short[frameSize]; // Preallocate
            } catch (OpusException e) {
                throw new RuntimeException(e);
            }
        }

        public int decode(byte[] opusData, int len, float[] floatData)  {
            try {
                int decodedSamples = decoder.decode(opusData, 0, len, pcmShorts, 0, frameSize, false);
                // Convert 16-bit PCM to float (-1.0 to 1.0)
                for (int i = 0; i < decodedSamples; i++) {
                    floatData[i] = pcmShorts[i] / 32768.0f;
                }
                return decodedSamples;
            } catch (OpusException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static class OpusEncoderWrapper {
        private final OpusEncoder encoder;

        public OpusEncoderWrapper(int sampleRate) {
            try {
                this.encoder = new OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_AUDIO); // Mono
            } catch (OpusException e) {
                throw new RuntimeException(e);
            }
        }

        public int encode(short[] pcmData, int len, byte[] opusData) {
            try {
                return encoder.encode(pcmData, 0, len, opusData, 0, opusData.length);
            } catch (OpusException e) {
                throw new RuntimeException(e);
            }
        }
    }

}

