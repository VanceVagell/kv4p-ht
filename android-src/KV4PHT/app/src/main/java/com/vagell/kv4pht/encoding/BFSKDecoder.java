package com.vagell.kv4pht.encoding;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.function.Consumer;

public class BFSKDecoder {
    public static final byte[] START_OF_DATA_MARKER = new byte[]{1, 1, 1, 1, 1, 1, 0, 1}; // Not an alphanumeric ASCII character
    public static final byte[] END_OF_DATA_MARKER =   new byte[]{0, 0, 0, 0, 0, 0, 1, 0}; // Not an alphanumeric ASCII character
    private static final int DATA_PARSE_EVERY_MS = 1000; // milliseconds to wait before parsing data again (to avoid wasted CPU cycles constantly looking for data)
    private int startMarkerSearchGranularity; // Bytes to skip while searching for start marker in audio data (higher risks missing, lower increases CPU use)
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
    private static final boolean DEBUG_SAVE_AUDIO = false;

    public BFSKDecoder(float sampleRate, int baudRate, double freqZero, double freqOne, int bufferSize, Consumer<String> callback) {
        this.sampleRate = sampleRate;
        this.baudRate = baudRate; // Max 1200
        this.freqZero = freqZero;
        this.freqOne = freqOne;
        this.samplesPerBit = (int) (sampleRate / baudRate);
        startMarkerSearchGranularity = samplesPerBit / 30;
        this.buffer = new CircularBuffer(bufferSize);
        this.callback = callback;
        initializeSineTables();

        // TODO Dynamically adjust this up (stricter) or down based on baud rate. Lower baud can be stricter on threshold.
        markerCorrelationThreshold = 2000;

        // Final bits of the data start/end markers must differ for bit alignment to work properly.
        assert(START_OF_DATA_MARKER[START_OF_DATA_MARKER.length - 2] != START_OF_DATA_MARKER[START_OF_DATA_MARKER.length - 1]);
        assert(END_OF_DATA_MARKER[END_OF_DATA_MARKER.length - 2] != END_OF_DATA_MARKER[END_OF_DATA_MARKER.length - 1]);
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

        if (DEBUG_SAVE_AUDIO) {
            // Dump audio bytes to storage/emulated/0/Document/rxAudio.pcm.
            // Can be imported to Audacity as "raw data", 44kHz 8-bit mono.
            saveAudioFile(accumulatedAudio);
        }

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

    // Returns the index in bufferedAudio at which payload data begins (or -1 if no start marker found).
    private int findStartOfData(byte[] bufferedAudio) {
        Log.d("DEBUG", "================ findStartOfData ================");
        int startOfDataLen = START_OF_DATA_MARKER.length;
        int totalSamples = bufferedAudio.length;

        if (totalSamples >= startOfDataLen * samplesPerBit) {
            double zeroCorrelation = 0;
            double oneCorrelation = 0;
            for (int sampleOffset = 0; sampleOffset <= totalSamples; sampleOffset += startMarkerSearchGranularity) {
                boolean startOfDataMatch = true;
                for (int j = 0; j < startOfDataLen; j++) {
                    int from = sampleOffset + (j * samplesPerBit);
                    int to = Math.min((sampleOffset + (j * samplesPerBit) + samplesPerBit), bufferedAudio.length);
                    zeroCorrelation = correlateZero(bufferedAudio, from, to);
                    oneCorrelation = correlateOne(bufferedAudio, from, to);

                    // TODO remove this debug stuff. Next thing to try is to set the threshold to like -9000 so it's basically off. Is it just a threshold problem?
                    if (zeroCorrelation > markerCorrelationThreshold) {
                        //Log.d("DEBUG", "High zeroCorrelation: " + zeroCorrelation + " at idx: " + sampleOffset);
                    }
                    if (oneCorrelation > markerCorrelationThreshold) {
                        //Log.d("DEBUG", "High oneCorrelation: " + oneCorrelation + " at idx: " + sampleOffset);
                    }

                    if ((zeroCorrelation < markerCorrelationThreshold && oneCorrelation < markerCorrelationThreshold) || // Poor match altogether? e.g. noisy audio or sampleOffset is poorly aligned to bits
                            (START_OF_DATA_MARKER[j] == 0 && oneCorrelation > zeroCorrelation) || // This bit of marker is 0 and doesn't match?
                            (START_OF_DATA_MARKER[j] == 1 && zeroCorrelation > oneCorrelation)) { // This bit of marker is 1 and doesn't match?
                        startOfDataMatch = false;
                        break;
                    }

                    if (j > 4)
                        Log.d("DEBUG", "Found potential data start[" + j + "]: " + (oneCorrelation > zeroCorrelation ? 1 : 0) + " correlation: " + (oneCorrelation > zeroCorrelation ? oneCorrelation : zeroCorrelation) + " from: " + from);
                }
                if (startOfDataMatch) {
                    // Revisit the second-to-last bit on the start marker, and use it to phase lock.
                    // Find the sampleOffset within the next 1/2 samplesPerBit after it where the
                    // correlation for this bit is highest. We only look ahead this limited amount
                    // so we don't cross into the next bit.
                    byte secondToLastBit = START_OF_DATA_MARKER[START_OF_DATA_MARKER.length - 2];
                    double highestCorrelation = -999999;
                    int sampleOffsetWithHighestCorellation = sampleOffset + (6 * samplesPerBit);
                    int startAt = Math.max(0, sampleOffset + (6 * samplesPerBit) - startMarkerSearchGranularity); // Slightly before second-to-last bit of start marker.
                    int stopAt = startAt + samplesPerBit;
                    Log.d("DEBUG", "startAt: " + startAt + " stopAt: " + stopAt);
                    for (int i = startAt; i < stopAt; i++) { // i++ means finest granularity search at this point.
                        int from = i;
                        int to = Math.min(i + samplesPerBit, bufferedAudio.length);
                        if (secondToLastBit == 1) {
                            oneCorrelation = correlateOne(bufferedAudio, from, to);
                            if (oneCorrelation > highestCorrelation) {
                                highestCorrelation = oneCorrelation;
                                sampleOffsetWithHighestCorellation = i;
                            }
                        } else { // secondToLastBit == 0
                            zeroCorrelation = correlateZero(bufferedAudio, from, to);
                            // Log.d("DEBUG", "Checking secondToLastBit at i: " + i + " zeroCorrelation: " + zeroCorrelation);
                            if (zeroCorrelation > highestCorrelation) {
                                highestCorrelation = zeroCorrelation;
                                sampleOffsetWithHighestCorellation = i;
                            }
                        }
                    }

                    Log.d("DEBUG", "Phase aligned with correlation: " + highestCorrelation);
                    Log.d("DEBUG", "Payload data start: " + (sampleOffsetWithHighestCorellation + (samplesPerBit * 2)));
                    return (sampleOffsetWithHighestCorellation + (samplesPerBit * 2));
                }
            }
        }

        return -1;
    }

    // Returns the index in bufferedAudio at which payload data ends (or -1 if no end marker found).
    private int findEndOfData(byte[] bufferedAudio, int startOfData) {
        Log.d("DEBUG", "================ findEndOfData ================");
        int endOfDataLen = END_OF_DATA_MARKER.length;
        int totalSamples = bufferedAudio.length;

        if (totalSamples >= (START_OF_DATA_MARKER.length * samplesPerBit) + (endOfDataLen * samplesPerBit)) {
            for (int sampleOffset = startOfData;
                     sampleOffset <= totalSamples;
                     sampleOffset += (samplesPerBit * 8)) { // Skip ahead in one byte increments since findStartOfData already bit-aligned us.
                boolean endOfDataMatch = true;
                for (int j = 0; j < endOfDataLen; j++) {
                    // Extract the range of samples starting from sampleOffset + j
                    int from = sampleOffset + (j * samplesPerBit);
                    int to = Math.min((sampleOffset + (j * samplesPerBit) + samplesPerBit), bufferedAudio.length);
                    double zeroCorrelation = correlateZero(bufferedAudio, from, to);
                    double oneCorrelation = correlateOne(bufferedAudio, from, to);

                    // TODO remove this debug stuff. Next thing to try is to set the threshold to like -9000 so it's basically off. Is it just a threshold problem?
                    if (zeroCorrelation > markerCorrelationThreshold) {
                        //Log.d("DEBUG", "High zeroCorrelation: " + zeroCorrelation + " at idx: " + sampleOffset);
                    }
                    if (oneCorrelation > markerCorrelationThreshold) {
                        //Log.d("DEBUG", "High oneCorrelation: " + oneCorrelation + " at idx: " + sampleOffset);
                    }

                    if ((zeroCorrelation < markerCorrelationThreshold && oneCorrelation < markerCorrelationThreshold) || // Poor match altogether? e.g. noisy audio or sampleOffset is poorly aligned to bits
                            (END_OF_DATA_MARKER[j] == 0 && oneCorrelation > zeroCorrelation) || // This bit of marker is 0 and doesn't match?
                            (END_OF_DATA_MARKER[j] == 1 && zeroCorrelation > oneCorrelation)) { // This bit of marker is 1 and doesn't match?
                        endOfDataMatch = false;
                        break;
                    }

                    if (j > 4)
                        Log.d("DEBUG", "Found potential data end[" + j + "]: " + (oneCorrelation > zeroCorrelation ? 1 : 0) + " @ " + (oneCorrelation > zeroCorrelation ? oneCorrelation : zeroCorrelation));
                }
                if (endOfDataMatch) {
                    Log.d("DEBUG", "Payload data end: " + sampleOffset);
                    return sampleOffset;
                }
            }
        }

        return -1;
    }

    private void decodeData(byte[] bufferedAudio, int startOfData, int endOfData) {
        int length = endOfData - startOfData;
        if (length <= 0) return;

        byte[] binaryData = new byte[length / samplesPerBit];

        for (int i = 0; i < binaryData.length; i++) {
            int from = startOfData + (i * samplesPerBit);
            int to = Math.min((startOfData + ((i + 1) * samplesPerBit)), bufferedAudio.length);
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
        return new String(bytes, StandardCharsets.UTF_8)
                .replaceAll("[^a-zA-Z0-9~`!@#\\$%^&*()\\-_=+\\[\\]{}|;:'\",.<>/?\\\\ ]", ""); // Only allow meaningful chars through.
    }

    // For debugging audio in circular buffer.
    private void saveAudioFile(byte[] pcm8) {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (!dir.exists()) {
            dir.mkdir();
        }
        File backupFile = new File(dir, "rxAudio.pcm");
        try {
            if (!backupFile.exists()) {
                backupFile.delete();
            }
            backupFile.createNewFile();

            FileOutputStream outputStream = new FileOutputStream(backupFile);
            outputStream.write(pcm8);
            outputStream.flush();
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
