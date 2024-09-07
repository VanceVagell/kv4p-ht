package com.vagell.kv4pht.encoding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

public class BFSKEncoder {
    private final float sampleRate;
    private final int baudRate;
    private final double freqZero;
    private final double freqOne;
    private final int samplesPerBit;

    public BFSKEncoder(float sampleRate, int baudRate, double freqZero, double freqOne) {
        this.sampleRate = sampleRate;
        this.baudRate = baudRate;
        this.freqZero = freqZero;
        this.freqOne = freqOne;
        this.samplesPerBit = (int) (sampleRate / baudRate);
    }

    public byte[] encode(String data) throws IOException {
        byte[] binaryData = data.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();

        byteOutputStream.write(generateTones(BFSKDecoder.START_OF_DATA_MARKER)); // Start marker

        BitSet bitset = BitSet.valueOf(binaryData);
        double phase = 0.0;
        byte[] toneBuffer = new byte[samplesPerBit];
        for (int i = 0; i < (binaryData.length * 8); i++) {
            double freq = bitset.get(i) ? freqOne : freqZero;
            phase = generateTone(freq, samplesPerBit, phase, toneBuffer); // Maintain phase continuity
            byteOutputStream.write(toneBuffer);
        }

        byteOutputStream.write(generateTones(BFSKDecoder.END_OF_DATA_MARKER)); // End marker
        return byteOutputStream.toByteArray();
    }

    private double generateTone(double frequency, int samplesPerBit, double initialPhase, byte[] tone) {
        double phase = initialPhase;
        for (int i = 0; i < samplesPerBit; i++) {
            double angle = 2.0 * Math.PI * frequency * (i / sampleRate) + phase;
            tone[i] = (byte) (Math.sin(angle) * 127);
        }
        return phase + 2.0 * Math.PI * frequency * (samplesPerBit / sampleRate);
    }

    private byte[] generateTones(byte[] binarySequence) {
        ByteArrayOutputStream tones = new ByteArrayOutputStream();
        double phase = 0.0; // Start with an initial phase of 0
        byte[] previousTone = new byte[samplesPerBit];
        byte[] currentTone = new byte[samplesPerBit];

        // Initialize with the first bit's tone
        double initialFreq = binarySequence[0] == 0 ? freqZero : freqOne;
        phase = generateTone(initialFreq, samplesPerBit, phase, previousTone);
        tones.write(previousTone, 0, samplesPerBit);

        // For each subsequent bit, generate tone and apply crossfade
        for (int i = 1; i < binarySequence.length; i++) {
            double freq = binarySequence[i] == 0 ? freqZero : freqOne;
            phase = generateTone(freq, samplesPerBit, phase, currentTone);

            // Write the current tone to the output
            tones.write(currentTone, 0, samplesPerBit);

            // Copy currentTone to previousTone for the next iteration
            System.arraycopy(currentTone, 0, previousTone, 0, samplesPerBit);
        }

        return tones.toByteArray();
    }
}
