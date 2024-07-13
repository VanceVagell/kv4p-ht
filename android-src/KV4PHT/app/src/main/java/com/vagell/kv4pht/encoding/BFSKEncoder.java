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

        // Simulated noise (for testing)
        /* for (int i = 0; i < 10013; i++) {
            byteOutputStream.write(generateTone(Math.random() * 5000, 1));
        } */

        byteOutputStream.write(generateTones(BFSKDecoder.START_OF_DATA_MARKER)); // Write start-of-data marker
        BitSet bitset = BitSet.valueOf(binaryData);
        for (int i = 0; i < (binaryData.length * 8); i++) { // Can't use  bitset.length() here because it's not actually the number of bits, a BitSet oddity related to how it stores the bits.
            byteOutputStream.write(generateTone(bitset.get(i) ? freqOne : freqZero, samplesPerBit)); // Write data
        }
        byteOutputStream.write(generateTones(BFSKDecoder.END_OF_DATA_MARKER)); // Write end-of-data marker

        // Simulated noise (for testing)
        /* for (int i = 0; i < 20037; i++) {
            byteOutputStream.write(generateTone(Math.random() * 5000, 1));
        } */

        return byteOutputStream.toByteArray();
    }

    private byte[] generateTone(double frequency, int samplesPerBit) {
        byte[] tone = new byte[samplesPerBit];
        for (int i = 0; i < samplesPerBit; i++) {
            double angle = 2.0 * Math.PI * i / (sampleRate / frequency);
            tone[i] = (byte) (Math.sin(angle) * 127);
        }
        return tone;
    }

    private byte[] generateTones(byte[] binarySequence) {
        ByteArrayOutputStream tones = new ByteArrayOutputStream();
        for (byte b : binarySequence) {
            tones.write(generateTone(b == 0 ? freqZero : freqOne, samplesPerBit), 0, samplesPerBit);
        }
        return tones.toByteArray();
    }
}
