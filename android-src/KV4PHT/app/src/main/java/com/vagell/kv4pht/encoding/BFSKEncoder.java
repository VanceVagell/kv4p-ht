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
        byte[] binaryData = convertStringToBinary(data);

        ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();

        // TODO figure out why adding noise breaks everything. start by implementing bit-by-bit advancing in BFSKDecoder (see TODO there).
        // Simulated noise
        /* for (int i = 0; i < 1013; i++) {
            byteOutputStream.write(generateTone(Math.random() * 5000, 1));
        } */

        byteOutputStream.write(generateTones(BFSKDecoder.START_OF_DATA_MARKER)); // Write start-of-data marker
        for (byte b : binaryData) {
            byteOutputStream.write(generateTone(b == 0 ? freqZero : freqOne, samplesPerBit)); // Write data
        }
        byteOutputStream.write(generateTones(BFSKDecoder.END_OF_DATA_MARKER)); // Write end-of-data marker

        // Simulated noise
        /* for (int i = 0; i < 237; i++) {
            byteOutputStream.write(generateTone(Math.random() * 5000, 1));
        } */

        return byteOutputStream.toByteArray();
    }

    private byte[] convertStringToBinary(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        BitSet bitSet = BitSet.valueOf(bytes);
        byte[] binaryData = new byte[bitSet.length()];
        for (int i = 0; i < bitSet.length(); i++) {
            binaryData[i] = (byte) (bitSet.get(i) ? 1 : 0);
        }

        return binaryData;
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
