package com.vagell.kv4pht.encoding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Consumer;

public class BFSKDecoder {
    public static final byte[] START_OF_DATA_MARKER = new byte[]{1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1};
    public static final byte[] END_OF_DATA_MARKER = new byte[]{0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0};
    private static final double FREQ_ALLOWED_DEVIANCE = 100.0; // Allowed frequency deviance in Hz
    private final int markerCorrelationThreshold;
    private final float sampleRate;
    private final int baudRate;
    private final double freqZero;
    private final double freqOne;
    private final int samplesPerBit;
    private final CircularBuffer buffer;
    private final Consumer<String> callback;
    private double[][] sineTablesZero;
    private double[][] sineTablesOne;

    public BFSKDecoder(float sampleRate, int baudRate, double freqZero, double freqOne, int bufferSize, Consumer<String> callback) {
        this.sampleRate = sampleRate;
        this.baudRate = baudRate; // Max 1200
        this.freqZero = freqZero;
        this.freqOne = freqOne;
        this.samplesPerBit = (int) (sampleRate / baudRate);
        this.buffer = new CircularBuffer(bufferSize);
        this.callback = callback;
        initializeSineTables();

        // TODO adjust this up or down based on baud rate (lower baud can be stricter on threshold).
        markerCorrelationThreshold = 50;
    }

    public synchronized void feedAudioData(byte[] audioData) {
        buffer.write(audioData);
        byte[] currentBytes = buffer.read(buffer.getSize());
        int totalBits = currentBytes.length / samplesPerBit;

        if (totalBits > 0) {
            int startOfData = findStartOfData(currentBytes);
            int endOfData = -1;
            if (startOfData != -1) {
                endOfData = findEndOfData(currentBytes, startOfData);
                if (endOfData != -1) {
                    decodeData(currentBytes, startOfData, endOfData);
                    return; // Note that this drops currentBytes from buffer because read() in a circular buffer moves the read head forward.
                }
            }

            if (startOfData == -1 || endOfData == -1){
                buffer.write(currentBytes); // Put unused bytes back, no data found in this audio window yet.
            }
        }
    }

    private int findStartOfData(byte[] bufferedData) {
        int startOfDataLen = START_OF_DATA_MARKER.length;
        int totalSamples = bufferedData.length;

        if (totalSamples >= startOfDataLen * samplesPerBit) {
            double zeroCorrelation = 0;
            double oneCorrelation = 0;
            for (int sampleOffset = 0; sampleOffset <= totalSamples; sampleOffset++) {
                boolean startOfDataMatch = true;
                for (int j = 0; j < startOfDataLen; j++) {
                    // Extract the range of samples starting from sampleOffset + j
                    byte[] samples;
                    try {
                        samples = Arrays.copyOfRange(bufferedData, sampleOffset + (j * samplesPerBit), sampleOffset + (j * samplesPerBit) + samplesPerBit);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        return -1; // Went outside bufferedData looking for start marker.
                    }
                    zeroCorrelation = correlateZero(samples);
                    oneCorrelation = correlateOne(samples);

                    if ((zeroCorrelation < markerCorrelationThreshold && oneCorrelation < markerCorrelationThreshold) || // Poor match altogether? e.g. noisy audio or sampleOffset is poorly aligned to bits
                            (START_OF_DATA_MARKER[j] == 0 && oneCorrelation > zeroCorrelation) || // This bit of marker is 0 and doesn't match?
                            (START_OF_DATA_MARKER[j] == 1 && zeroCorrelation > oneCorrelation)) { // This bit of marker is 1 and doesn't match?
                        startOfDataMatch = false;
                        break;
                    }
                }
                if (startOfDataMatch) {
                    // Further lock phase synchronization by finding the sampleOffset within the next
                    // 1/2 samplesPerBit where the correlation for this last marker is highest.
                    // We only look ahead this limited amount so we don't cross into the next bit.
                    byte lastByte = START_OF_DATA_MARKER[START_OF_DATA_MARKER.length - 1];
                    double highestCorrelation = lastByte == 1 ? oneCorrelation : zeroCorrelation;
                    int sampleOffsetWithHighestCorellation = sampleOffset;
                    final int stopAt = sampleOffset + (samplesPerBit / 2);
                    for (int i = sampleOffset; i < stopAt; i++) {
                        byte[] samples;
                        try {
                            samples = Arrays.copyOfRange(bufferedData, i, i + samplesPerBit);
                        } catch (ArrayIndexOutOfBoundsException e) {
                            return sampleOffsetWithHighestCorellation;
                        }
                        if (lastByte == 1) {
                            oneCorrelation = correlateOne(samples);
                            if (oneCorrelation > highestCorrelation) {
                                highestCorrelation = oneCorrelation;
                                sampleOffsetWithHighestCorellation = i;
                            }
                        } else { // lastByte == 0
                            zeroCorrelation = correlateZero(samples);
                            if (zeroCorrelation > highestCorrelation) {
                                highestCorrelation = zeroCorrelation;
                                sampleOffsetWithHighestCorellation = i;
                            }
                        }
                    }

                    return sampleOffsetWithHighestCorellation;
                }
            }
        }

        return -1;
    }

    private int findEndOfData(byte[] bufferedData, int startOfData) {
        int endOfDataLen = END_OF_DATA_MARKER.length;
        int totalSamples = bufferedData.length;

        if (totalSamples >= (START_OF_DATA_MARKER.length * samplesPerBit) + (endOfDataLen * samplesPerBit)) {
            for (int sampleOffset = (startOfData + START_OF_DATA_MARKER.length * samplesPerBit);
                     sampleOffset <= totalSamples - endOfDataLen * samplesPerBit;
                     sampleOffset += samplesPerBit) { // Skip ahead in samplesPerBit increments since findStartOfData already aligned us to bits.
                boolean endOfDataMatch = true;
                for (int j = 0; j < endOfDataLen; j++) {
                    // Extract the range of samples starting from sampleOffset + j
                    byte[] samples;
                    try {
                        samples = Arrays.copyOfRange(bufferedData, sampleOffset + (j * samplesPerBit), sampleOffset + (j * samplesPerBit) + samplesPerBit);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        return -1; // Went outside bufferedData looking for end marker.
                    }
                    double zeroCorrelation = correlateZero(samples);
                    double oneCorrelation = correlateOne(samples);

                    if ((zeroCorrelation < markerCorrelationThreshold && oneCorrelation < markerCorrelationThreshold) || // Poor match altogether? e.g. noisy audio or sampleOffset is poorly aligned to bits
                            (END_OF_DATA_MARKER[j] == 0 && oneCorrelation > zeroCorrelation) || // This bit of marker is 0 and doesn't match?
                            (END_OF_DATA_MARKER[j] == 1 && zeroCorrelation > oneCorrelation)) { // This bit of marker is 1 and doesn't match?
                        endOfDataMatch = false;
                        break;
                    }
                }
                if (endOfDataMatch) {
                    return sampleOffset;
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
            double zeroCorrelation = correlateZero(samples);
            double oneCorrelation = correlateOne(samples);
            // Note we just force a decode here, unlike when trying to detect start and end of data, where
            // we also check how high-quality the decode is (via correlation). Since we only get here
            // when we detected strong start/end markers, we KNOW this is a bit so we just do our best
            // to figure out what it's encoding.
            binaryData[i] = (byte) (oneCorrelation > zeroCorrelation ? 1 : 0);
        }

        String decodedString = convertBinaryToString(binaryData);
        if (!decodedString.isEmpty()) {
            callback.accept(decodedString);
        }
        buffer.reset();
    }

    // This method precomputes the necessary sine values, because otherwise we'd be calling
    // Math.sin over and over when decoding data and this is a very slow and expensive call.
    private void initializeSineTables() {
        int devianceSteps = 2; // +1 for above and -1 for below the base frequency
        sineTablesZero = new double[devianceSteps * 2 + 1][samplesPerBit];
        sineTablesOne = new double[devianceSteps * 2 + 1][samplesPerBit];

        for (int i = 0; i < samplesPerBit; i++) {
            for (int d = -devianceSteps; d <= devianceSteps; d++) {
                double deviance = d * FREQ_ALLOWED_DEVIANCE;
                sineTablesZero[d + devianceSteps][i] = Math.sin(2.0 * Math.PI * i / (sampleRate / (freqZero + deviance)));
                sineTablesOne[d + devianceSteps][i] = Math.sin(2.0 * Math.PI * i / (sampleRate / (freqOne + deviance)));
            }
        }
    }

    private double correlate(byte[] samples, double[][] sineTables) {
        double maxCorrelation = Double.NEGATIVE_INFINITY;
        for (double[] sineTable : sineTables) {
            double sum = 0;
            for (int i = 0; i < samples.length; i++) {
                sum += samples[i] * sineTable[i];
            }
            if (sum > maxCorrelation) {
                maxCorrelation = sum;
            }
        }
        return maxCorrelation;
    }

    private double correlateZero(byte[] samples) {
        return correlate(samples, sineTablesZero);
    }

    private double correlateOne(byte[] samples) {
        return correlate(samples, sineTablesOne);
    }

    private double[] normalize(byte[] samples) {
        double[] normalizedSamples = new double[samples.length];
        double max = 0;

        // Find the maximum absolute value
        for (byte sample : samples) {
            double absValue = Math.abs(sample);
            if (absValue > max) {
                max = absValue;
            }
        }

        // Normalize the samples
        for (int i = 0; i < samples.length; i++) {
            normalizedSamples[i] = samples[i] / max;
        }

        return normalizedSamples;
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
