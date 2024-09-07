package com.vagell.kv4pht.encoding;

import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class BFSKEncoder {
    public static final int NUM_PARITY_BYTES = 20;

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
        byte[] binaryData = encodeWithFEC(data);
        ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();

        byteOutputStream.write(generateTones(BFSKDecoder.START_OF_DATA_MARKER)); // Start marker

        double phase = 0.0;
        byte[] toneBuffer = new byte[samplesPerBit];

        for (byte b : binaryData) {
            for (int i = 7; i >= 0; i--) {  // Transmit bits of each byte from MSB to LSB
                double freq = ((b >> i) & 1) == 1 ? freqOne : freqZero;  // Determine frequency for 1 or 0
                phase = generateTone(freq, samplesPerBit, phase, toneBuffer); // Maintain phase continuity
                byteOutputStream.write(toneBuffer);  // Write tone for the bit
            }
        }

        byteOutputStream.write(generateTones(BFSKDecoder.END_OF_DATA_MARKER)); // End marker
        return byteOutputStream.toByteArray();
    }

    public byte[] encodeWithFEC(String data) throws IOException {
        // Convert the string to UTF-8 bytes
        byte[] binaryData = data.getBytes(StandardCharsets.UTF_8);

        // Initialize Reed-Solomon Encoder
        ReedSolomonEncoder encoder = new ReedSolomonEncoder(GenericGF.DATA_MATRIX_FIELD_256);

        // Convert binaryData into int array (Reed-Solomon operates on int arrays)
        int[] dataInts = new int[binaryData.length];
        for (int i = 0; i < binaryData.length; i++) {
            dataInts[i] = binaryData[i] & 0xFF;  // Convert byte to unsigned int
        }

        // Add FEC parity (e.g., 5 parity bytes)
        int[] paddedData = new int[dataInts.length + NUM_PARITY_BYTES];
        System.arraycopy(dataInts, 0, paddedData, 0, dataInts.length);
        encoder.encode(paddedData, NUM_PARITY_BYTES);

        // Convert int array back to byte array for transmission
        byte[] fecData = new byte[paddedData.length];
        for (int i = 0; i < paddedData.length; i++) {
            fecData[i] = (byte) paddedData[i];
        }

        return fecData;  // Return the FEC-encoded data
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
