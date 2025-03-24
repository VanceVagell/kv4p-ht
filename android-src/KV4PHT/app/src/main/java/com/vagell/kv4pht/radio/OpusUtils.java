package com.vagell.kv4pht.radio;

import static io.github.jaredmdobson.OpusFramesize.OPUS_FRAMESIZE_20_MS;

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
        private final int frameSize;
        private final short[] pcmBuffer; // 20ms at 48kHz

        public OpusEncoderWrapper(int sampleRate, int frameSize) {
            try {
                this.encoder = new OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP); // Mono
                this.frameSize = frameSize;
                this.encoder.setUseVBR(true);
                this.pcmBuffer = new short[frameSize];
            } catch (OpusException e) {
                throw new RuntimeException(e);
            }
        }

        /*
        public int encode(short[] pcmData, int len, byte[] opusData) {
            int opusLen = 0;
            int pcmIndex = 0;
            int available = pcmBufferLen + len;
            short[] combinedData = new short[available];
            // Copy leftover samples from the last call
            System.arraycopy(pcmBuffer, 0, combinedData, 0, pcmBufferLen);
            System.arraycopy(pcmData, 0, combinedData, pcmBufferLen, len);
            pcmBufferLen = 0; // Reset buffer length
            while (pcmIndex + frameSize <= available) {
                try {
                    opusLen += encoder.encode(combinedData, pcmIndex, frameSize, opusData, opusLen, opusData.length - opusLen);
                } catch (OpusException | IllegalArgumentException ignored) {
                    // Skip only this frame, continue encoding
                    throw new RuntimeException(ignored);
                }
                pcmIndex += frameSize;
            }
            // Save the remaining partial frame for the next call
            int remaining = available - pcmIndex;
            if (remaining > 0) {
                System.arraycopy(combinedData, pcmIndex, pcmBuffer, 0, remaining);
                pcmBufferLen = remaining;
            }
            return opusLen;
        }
         */
        public int encode(float[] frame, byte[] opusData) {
            try {
                for (int i = 0; i < frameSize; i++) {
                    pcmBuffer[i] = (short) (frame[i] * 32768.0f);
                }
                return encoder.encode(pcmBuffer, 0, frameSize, opusData, 0, opusData.length);
            } catch (OpusException | IllegalArgumentException e) {
                throw new RuntimeException(e);
                //return 0;
            }
        }
    }

}

