package com.vagell.kv4pht.encoding;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Consumer;

public class BFSKDecoder {
    public static final byte[] START_OF_DATA_MARKER = new byte[]{1, 1, 1, 1, 1, 1, 1, 1};
    public static final byte[] END_OF_DATA_MARKER = new byte[]{0, 0, 0, 0, 0, 0, 0, 0};
    private final float sampleRate;
    private final int baudRate;
    private final double freqZero;
    private final double freqOne;
    private final int samplesPerBit;
    private final CircularBuffer buffer;
    private final Consumer<String> callback;

    public BFSKDecoder(float sampleRate, int baudRate, double freqZero, double freqOne, int bufferSize, Consumer<String> callback) {
        this.sampleRate = sampleRate;
        this.baudRate = baudRate;
        this.freqZero = freqZero;
        this.freqOne = freqOne;
        this.samplesPerBit = (int) (sampleRate / baudRate);
        this.buffer = new CircularBuffer(bufferSize);
        this.callback = callback;
    }

    public void feedAudioData(byte[] audioData) {
        buffer.write(audioData);
        byte[] currentBytes = buffer.read(buffer.getSize());
        int totalBits = currentBytes.length / samplesPerBit;

        if (totalBits > 0) {
            int startOfData = findStartOfData(currentBytes);
            if (findStartOfData(currentBytes) != -1) {
                int endOfData = findEndOfData(currentBytes, startOfData);

                if (endOfData != -1) {
                    decodeData(currentBytes, startOfData, endOfData);
                    return; // Note that this drops currentBytes from buffer because read() in a circular buffer moves the read head forward.
                }
            } else {
                buffer.write(currentBytes); // Put unused bytes back, no data found in this audio window yet.
            }
        }
    }

    private int findStartOfData(byte[] bufferedData) {
        int startOfDataBits = START_OF_DATA_MARKER.length;

        int totalBits = bufferedData.length / samplesPerBit;
        if (totalBits >= startOfDataBits) {
            for (int i = 0; i <= totalBits - startOfDataBits; i++) {
                boolean startOfDataMatch = true;
                for (int j = 0; j < startOfDataBits; j++) {
                    // TODO make this advance bit by bit, instead of in multiples of samplesPerBit, because we don't know alignment in random audio.
                    byte[] samples = Arrays.copyOfRange(bufferedData, (i + j) * samplesPerBit, (i + j + 1) * samplesPerBit);
                    double zeroCorrelation = correlate(samples, freqZero);
                    double oneCorrelation = correlate(samples, freqOne);
                    if ((START_OF_DATA_MARKER[j] == 0 && oneCorrelation > zeroCorrelation) || (START_OF_DATA_MARKER[j] == 1 && zeroCorrelation > oneCorrelation)) {
                        startOfDataMatch = false;
                        break;
                    }
                }
                if (startOfDataMatch) {
                    return i * samplesPerBit;
                }
            }
        }

        return -1;
    }

    private int findEndOfData(byte[] bufferedData, int startOfData) {
        int endOfDataBits = END_OF_DATA_MARKER.length;

        int totalBits = bufferedData.length / samplesPerBit;
        if (totalBits >= endOfDataBits) {
            for (int i = startOfData / samplesPerBit; i <= totalBits - endOfDataBits; i++) {
                boolean endOfDataMatch = true;
                for (int j = 0; j < endOfDataBits; j++) {
                    byte[] samples = Arrays.copyOfRange(bufferedData, (i + j) * samplesPerBit, (i + j + 1) * samplesPerBit);
                    double zeroCorrelation = correlate(samples, freqZero);
                    double oneCorrelation = correlate(samples, freqOne);
                    if ((END_OF_DATA_MARKER[j] == 0 && oneCorrelation > zeroCorrelation) || (END_OF_DATA_MARKER[j] == 1 && zeroCorrelation > oneCorrelation)) {
                        endOfDataMatch = false;
                        break;
                    }
                }
                if (endOfDataMatch) {
                    return i * samplesPerBit;
                }
            }
        }

        return -1;
    }

    private void decodeData(byte[] bufferedData, int startOfData, int endOfData) {
        int start = startOfData + (START_OF_DATA_MARKER.length * samplesPerBit);
        int length = endOfData - start;
        if (length <= 0) return;

        byte[] binaryData = new byte[length / samplesPerBit];

        for (int i = 0; i < binaryData.length; i++) {
            byte[] samples = Arrays.copyOfRange(bufferedData, start + (i * samplesPerBit), start + ((i + 1) * samplesPerBit));
            double zeroCorrelation = correlate(samples, freqZero);
            double oneCorrelation = correlate(samples, freqOne);
            binaryData[i] = (byte) (oneCorrelation > zeroCorrelation ? 1 : 0);
        }

        String decodedString = convertBinaryToString(binaryData);
        if (!decodedString.isEmpty()) {
            callback.accept(decodedString);
        }
        buffer.reset();
    }

    private double correlate(byte[] samples, double frequency) {
        double sum = 0;
        for (int i = 0; i < samples.length; i++) {
            double angle = 2.0 * Math.PI * i / (sampleRate / frequency);
            sum += samples[i] * Math.sin(angle);
        }
        return sum;
    }

    private String convertBinaryToString(byte[] binaryData) {
        BitSet bitSet = new BitSet(binaryData.length);
        for (int i = 0; i < binaryData.length; i++) {
            if (binaryData[i] == 1) {
                bitSet.set(i);
            }
        }
        byte[] bytes = bitSet.toByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
