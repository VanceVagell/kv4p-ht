package com.vagell.kv4pht.encoding;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class BFSKDecoder {
    public static final byte[] START_OF_DATA_MARKER = new byte[]{1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1};
    public static final byte[] END_OF_DATA_MARKER = new byte[]{0, 0, 0, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0};
    private static final int DATA_PARSE_EVERY_MS = 1000; // milliseconds to wait before parsing data again (to avoid wasted CPU cycles constantly looking for data)
    private static final int START_MARKER_SEARCH_GRANULARITY = 30; // Bytes to skip while searching for start marker in audio data (higher risks missing, lower increases CPU use)
    private long lastParseMs = System.currentTimeMillis();
    private final int markerCorrelationThreshold;
    private final float sampleRate;
    private final int baudRate;
    private final double freqZero;
    private final double freqOne;
    private final int samplesPerBit;
    private final CircularBuffer buffer;
    private final Consumer<String> callback;
    private double[] sineTableZero;
    private double[] sineTableOne;
    private boolean decoding = false;

    public BFSKDecoder(float sampleRate, int baudRate, double freqZero, double freqOne, int bufferSize, Consumer<String> callback) {
        this.sampleRate = sampleRate;
        this.baudRate = baudRate; // Max 1200
        this.freqZero = freqZero;
        this.freqOne = freqOne;
        this.samplesPerBit = (int) (sampleRate / baudRate);
        this.buffer = new CircularBuffer(bufferSize);
        this.callback = callback;
        initializeSineTables();

        // TODO Dynamically adjust this up (stricter) or down based on baud rate. Lower baud can be stricter on threshold.
        markerCorrelationThreshold = 1000;
    }

    public void feedAudioData(byte[] audioData) {
        buffer.write(audioData);
        if (decoding) { // Another thread is already decoding, let it finish first.
            return;
        }

        // Rate limit how often we try to find data in the audio.
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (decoding || (now - lastParseMs) < DATA_PARSE_EVERY_MS) {
                return;
            }
            lastParseMs = now;
            decoding = true;
        }

        byte[] accumulatedAudio = buffer.readAll();
        int potentialBits = accumulatedAudio.length / samplesPerBit;

        if (potentialBits > 0) {
            int startOfData = findStartOfData(accumulatedAudio);
            int endOfData = -1;
            if (startOfData != -1) {
                endOfData = findEndOfData(accumulatedAudio, startOfData);
                if (endOfData != -1) {
                    decodeData(accumulatedAudio, startOfData, endOfData);
                }
            }
        }
        decoding = false;
    }

    private int findStartOfData(byte[] bufferedAudio) {
        int startOfDataLen = START_OF_DATA_MARKER.length;
        int totalSamples = bufferedAudio.length;

        if (totalSamples >= startOfDataLen * samplesPerBit) {
            double zeroCorrelation = 0;
            double oneCorrelation = 0;
            for (int sampleOffset = 0; sampleOffset <= totalSamples; sampleOffset += START_MARKER_SEARCH_GRANULARITY) {
                boolean startOfDataMatch = true;
                for (int j = 0; j < startOfDataLen; j++) {
                    int from = sampleOffset + (j * samplesPerBit);
                    int to = Math.min((sampleOffset + (j * samplesPerBit) + samplesPerBit), bufferedAudio.length);
                    zeroCorrelation = correlateZero(bufferedAudio, from, to);
                    oneCorrelation = correlateOne(bufferedAudio, from, to);

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
                    // TODO consider using second-to-last part of START_OF_DATA_MARKER to align, since it guarantees subsequent bit differs in value (should align better with sharper edge).
                    byte lastByte = START_OF_DATA_MARKER[START_OF_DATA_MARKER.length - 1];
                    double highestCorrelation = lastByte == 1 ? oneCorrelation : zeroCorrelation;
                    int sampleOffsetWithHighestCorellation = sampleOffset;
                    final int stopAt = sampleOffset + (samplesPerBit / 2);
                    int startAt = Math.max(0, sampleOffset - START_MARKER_SEARCH_GRANULARITY); // Step back a little to make up for low granularity of initial search.
                    for (int i = startAt; i < stopAt; i++) { // i++ means finest granularity search at this point.
                        int from = i;
                        int to = Math.min(i + samplesPerBit, bufferedAudio.length);
                        if (lastByte == 1) {
                            oneCorrelation = correlateOne(bufferedAudio, from, to);
                            if (oneCorrelation > highestCorrelation) {
                                highestCorrelation = oneCorrelation;
                                sampleOffsetWithHighestCorellation = i;
                            }
                        } else { // lastByte == 0
                            zeroCorrelation = correlateZero(bufferedAudio, from, to);
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

    private int findEndOfData(byte[] bufferedAudio, int startOfData) {
        int endOfDataLen = END_OF_DATA_MARKER.length;
        int totalSamples = bufferedAudio.length;

        if (totalSamples >= (START_OF_DATA_MARKER.length * samplesPerBit) + (endOfDataLen * samplesPerBit)) {
            for (int sampleOffset = (startOfData + START_OF_DATA_MARKER.length * samplesPerBit);
                     sampleOffset <= totalSamples - endOfDataLen * samplesPerBit;
                     sampleOffset += samplesPerBit) { // Skip ahead in samplesPerBit increments since findStartOfData already aligned us to bits.
                boolean endOfDataMatch = true;
                for (int j = 0; j < endOfDataLen; j++) {
                    // Extract the range of samples starting from sampleOffset + j
                    int from = sampleOffset + (j * samplesPerBit);
                    int to = Math.min((sampleOffset + (j * samplesPerBit) + samplesPerBit), bufferedAudio.length);
                    double zeroCorrelation = correlateZero(bufferedAudio, from, to);
                    double oneCorrelation = correlateOne(bufferedAudio, from, to);

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

    private void decodeData(byte[] bufferedAudio, int startOfData, int endOfData) {
        int start = startOfData + (START_OF_DATA_MARKER.length * samplesPerBit);
        int length = endOfData - start;
        if (length <= 0) return;

        byte[] binaryData = new byte[length / samplesPerBit];

        for (int i = 0; i < binaryData.length; i++) {
            int from = start + (i * samplesPerBit);
            int to = Math.min((start + ((i + 1) * samplesPerBit)), bufferedAudio.length);
            double zeroCorrelation = correlateZero(bufferedAudio, from, to);
            double oneCorrelation = correlateOne(bufferedAudio, from, to);
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
        sineTableZero = new double[samplesPerBit];
        sineTableOne = new double[samplesPerBit];
        for (int i = 0; i < samplesPerBit; i++) {
            sineTableZero[i] = Math.sin(2.0 * Math.PI * i / (sampleRate / freqZero));
            sineTableOne[i] = Math.sin(2.0 * Math.PI * i / (sampleRate / freqOne));
        }
    }

    private double correlate(byte[] samples, double[] sineTable, int from, int to) {
        double sum = 0;
        for (int i = from, j = 0; i < to; i++, j++) {
            sum += samples[i] * sineTable[j];
        }
        return sum;
    }

    // Experimental alternative that multithreads the calculation. However, in my experiments
    // this actually uses more CPU overall. TODO Worth experimenting more with.
    /* private double correlate(byte[] samples, double[] sineTable, int from, int to) {
        return IntStream.range(from, to).parallel().mapToDouble(i -> samples[i] * sineTable[i - from]).sum();
    } */

    private double correlateZero(byte[] samples, int from, int to) {
        return correlate(samples, sineTableZero, from, to);
    }

    private double correlateOne(byte[] samples, int from, int to) {
        return correlate(samples, sineTableOne, from, to);
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
