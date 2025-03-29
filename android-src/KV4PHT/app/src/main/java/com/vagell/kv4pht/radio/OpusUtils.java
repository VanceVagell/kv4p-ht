package com.vagell.kv4pht.radio;

import static io.github.jaredmdobson.OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND;

import io.github.jaredmdobson.OpusApplication;
import io.github.jaredmdobson.OpusDecoder;
import io.github.jaredmdobson.OpusEncoder;
import lombok.SneakyThrows;

public final class OpusUtils {
    private OpusUtils() {}

    public static class OpusDecoderWrapper {
        private final OpusDecoder decoder;
        private final short[] pcmShorts; // Preallocated buffer
        private final int frameSize;

        @SneakyThrows
        public OpusDecoderWrapper(int sampleRate, int frameSize) {
            this.decoder = new OpusDecoder(sampleRate, 1); // Mono
            this.frameSize = frameSize;
            this.pcmShorts = new short[frameSize]; // Preallocate
        }

        @SneakyThrows
        public int decode(byte[] opusData, int len, float[] floatData)  {
            int decodedSamples = decoder.decode(opusData, 0, len, pcmShorts, 0, frameSize, false);
            // Convert 16-bit PCM to float (-1.0 to 1.0)
            for (int i = 0; i < decodedSamples; i++) {
                floatData[i] = pcmShorts[i] / 32768.0f;
            }
            return decodedSamples;
        }
    }

    public static class OpusEncoderWrapper {
        private final OpusEncoder encoder;
        private final int frameSize;
        private final short[] pcmBuffer; // 20ms at 48kHz

        @SneakyThrows
        public OpusEncoderWrapper(int sampleRate, int frameSize) {
            this.encoder = new OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP); // Mono
            this.frameSize = frameSize;
            this.encoder.setUseVBR(true);
            this.encoder.setMaxBandwidth(OPUS_BANDWIDTH_NARROWBAND);
            this.pcmBuffer = new short[frameSize];
        }

        @SneakyThrows
        public int encode(float[] frame, byte[] opusData) {
            for (int i = 0; i < frameSize; i++) {
                pcmBuffer[i] = (short) (frame[i] * 32768.0f);
            }
            return encoder.encode(pcmBuffer, 0, frameSize, opusData, 0, opusData.length);
        }
    }
}

