package com.vagell.kv4pht.encoding;

import android.os.Environment;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.function.Consumer;

public class BFSKDecoder {
    public static final byte[] START_OF_DATA_MARKER = new byte[]{1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0}; // 2x pattern that's not an alphanumeric ASCII character
    public static final byte[] END_OF_DATA_MARKER =   new byte[]{1, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 1, 0, 1}; // 2x pattern that's not an alphanumeric ASCII character
    private static final int DATA_PARSE_EVERY_MS = 1000; // milliseconds to wait before parsing data again (to avoid wasted CPU cycles & battery constantly looking for data)
    private int markerSearchGranularity; // Bytes to skip while searching for start marker in audio data (higher risks missing, lower increases CPU use)
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
    private double[] cosineTableZero;
    private double[] cosineTableOne;
    private boolean decoding = false;
    private static final boolean DEBUG_SAVE_AUDIO = false;
    private static final boolean VERBOSE_DECODE_DEBUG = false;

    public BFSKDecoder(float sampleRate, int baudRate, double freqZero, double freqOne, int bufferSize, Consumer<String> callback) {
        this.sampleRate = sampleRate;
        this.baudRate = baudRate; // Max 1200
        this.freqZero = freqZero;
        this.freqOne = freqOne;
        this.samplesPerBit = (int) (sampleRate / baudRate);
        markerSearchGranularity = samplesPerBit / 30;
        this.buffer = new CircularBuffer(bufferSize);
        this.callback = callback;
        initializeSineTables();

        // TODO Dynamically adjust this up (stricter) or down based on baud rate. Lower baud can be stricter on threshold.
        markerCorrelationThreshold = 1000;

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
            // Remove noise above and below the frequencies of interest to improve decode.
            accumulatedAudio = bandpassFilter(this.freqZero - 100, this.freqOne + 100, accumulatedAudio, sampleRate);

            // Attempt decode.
            int startMarker = findMarker(accumulatedAudio, accumulatedAudio.length - samplesPerBit - 1, START_OF_DATA_MARKER, true); // Look backwards b/c marker will appear at end of circular audio buffer, no sense in parsing the front.
            if (startMarker != -1) {
                int startOfData = startMarker + (START_OF_DATA_MARKER.length * samplesPerBit);
                Log.d("DEBUG", "startOfData: " + startOfData);

                int endOfData = findMarker(accumulatedAudio, startOfData, END_OF_DATA_MARKER, false); // For end marker, look forward from the start marker, we know it must be after it.
                if (endOfData != -1) {
                    Log.d("DEBUG", "endOfData: " + endOfData);

                    decodeData(accumulatedAudio, startOfData, endOfData);
                }
            }
        }
        decoding = false;
    }

    // Bandpass filter method that filters out frequencies below 'low' and above 'high'.
    public byte[] bandpassFilter(double low, double high, byte[] data, double sampleRate) {
        int n = data.length;

        // Find the next power of 2 greater than or equal to the length of the data.
        int paddedLength = nextPowerOf2(n);

        // FFT transformer
        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);

        // Convert the byte array into a double array (FFT requires double input) and pad with zeros.
        double[] audioDoubleData = new double[paddedLength];
        for (int i = 0; i < n; i++) {
            audioDoubleData[i] = data[i];
        }

        // Perform the FFT to get the frequency components.
        Complex[] transformed = fft.transform(audioDoubleData, TransformType.FORWARD);

        // Determine the frequency resolution.
        double frequencyResolution = sampleRate / paddedLength;

        // Apply the bandpass filter.
        for (int i = 0; i < transformed.length / 2; i++) {
            double frequency = i * frequencyResolution;

            // Zero out frequencies outside the specified range.
            if (frequency < low || frequency > high) {
                transformed[i] = Complex.ZERO;  // Zero out this frequency component.
                transformed[transformed.length - i - 1] = Complex.ZERO;  // Mirror frequency for the negative side.
            }
        }

        // Perform the inverse FFT to convert the filtered signal back into the time domain.
        Complex[] inverseTransformed = fft.transform(transformed, TransformType.INVERSE);

        // Convert the Complex array back into real data (taking the real part) and truncate to original size.
        byte[] filteredAudioData = new byte[n]; // Original size, not the padded size
        for (int i = 0; i < n; i++) {
            filteredAudioData[i] = (byte) Math.round(inverseTransformed[i].getReal());
        }

        return filteredAudioData;
    }

    // Helper method to find the next power of 2 greater than or equal to the given number
    private int nextPowerOf2(int num) {
        return (int) Math.pow(2, Math.ceil(Math.log(num) / Math.log(2)));
    }

    // Returns the first index at which the given marker appears in bufferAudio, or -1 if not found.
    private int findMarker(byte[] bufferedAudio, int lookFrom, byte[] marker, boolean lookBackwards) {
        if (VERBOSE_DECODE_DEBUG)
            Log.d("DEBUG", "================ findMarker from " + lookFrom + (lookBackwards ? " backwards " : " forwards ") + "================");

        int markerLen = marker.length;
        int totalSamples = bufferedAudio.length;
        double zeroCorrelation = 0;
        double oneCorrelation = 0;

        int directionalSearchGranularity = lookBackwards ? 0 - markerSearchGranularity : markerSearchGranularity;
        for (int sampleOffset = lookFrom; sampleOffset <= totalSamples && sampleOffset >= 0; sampleOffset += directionalSearchGranularity) {
            boolean markerMatch = true;
            for (int j = 0; j < markerLen; j++) {
                int from = sampleOffset + (j * samplesPerBit);
                int to = Math.min((sampleOffset + (j * samplesPerBit) + samplesPerBit), bufferedAudio.length);

                if (from >= to) {
                    break;
                }

                zeroCorrelation = correlateZero(bufferedAudio, from, to);
                oneCorrelation = correlateOne(bufferedAudio, from, to);

                // Note we don't check threshold at this point, just coarsely looking at relative correlations.
                // We apply threshold when we verify later.
                if ((marker[j] == 0 && oneCorrelation > zeroCorrelation) || // This bit of marker is 0 and doesn't match?
                    (marker[j] == 1 && zeroCorrelation > oneCorrelation)) { // This bit of marker is 1 and doesn't match?
                    markerMatch = false;
                    break;
                }

                if (VERBOSE_DECODE_DEBUG && j > (markerLen / 2))
                    Log.d("DEBUG", "Found potential marker[" + j + "]: " + (oneCorrelation > zeroCorrelation ? 1 : 0) + " correlation: " + (oneCorrelation > zeroCorrelation ? oneCorrelation : zeroCorrelation) + " from: " + from);
            }
            if (markerMatch) {
                // Revisit the second-to-last bit on the marker, and use it to phase lock.
                // Find the sampleOffset within the next 1/2 samplesPerBit after it where the
                // correlation for this bit is highest. We only look ahead this limited amount
                // so we don't cross into the next bit.
                double highestCorrelation = -999999;
                int sampleOffsetWithHighestCorellation = sampleOffset + ((markerLen - 2) * samplesPerBit);
                int startAt = Math.max(0, sampleOffset + ((markerLen - 2) * samplesPerBit) - markerSearchGranularity); // Slightly before second-to-last bit of marker.
                int stopAt = startAt + samplesPerBit;
                for (int i = startAt; i < stopAt; i++) { // i++ means finest granularity search at this point.
                    int from = i;
                    int to = Math.min(i + samplesPerBit, bufferedAudio.length);

                    if (from >= to) {
                        break;
                    }

                    double meanCorrelation = 0;

                    // Find and average the correlation of the last 3 bits (to avoid finding a local maxima correlation for a single bit).
                    try {
                        double firstCorrelation = marker[marker.length - 3] == 1 ?
                                correlateOne(bufferedAudio, from - samplesPerBit, to - samplesPerBit) :
                                correlateZero(bufferedAudio, from - samplesPerBit, to - samplesPerBit);
                        double secondCorrelation = marker[marker.length - 2] == 1 ?
                                correlateOne(bufferedAudio, from, to) :
                                correlateZero(bufferedAudio, from, to);
                        double thirdCorrelation = marker[marker.length - 1] == 1 ?
                                correlateOne(bufferedAudio, from + samplesPerBit, to + samplesPerBit) :
                                correlateZero(bufferedAudio, from + samplesPerBit, to + samplesPerBit);
                        meanCorrelation = (firstCorrelation + secondCorrelation + thirdCorrelation) / 3;
                    } catch (IndexOutOfBoundsException ioobe) {
                        continue;
                    }

                    if (meanCorrelation > highestCorrelation) {
                        highestCorrelation = meanCorrelation;
                        sampleOffsetWithHighestCorellation = i;
                    }
                }
                if (VERBOSE_DECODE_DEBUG)
                    Log.d("DEBUG", "Phase aligned with MEAN correlation: " + highestCorrelation);

                int likelyMarkerStart = (sampleOffsetWithHighestCorellation + (samplesPerBit * 2)) - (samplesPerBit * markerLen);

                if (VERBOSE_DECODE_DEBUG)
                    Log.d("DEBUG", "Likely marker start: " + likelyMarkerStart);

                // Double check all marker bits now that we're phase aligned. If this fails,
                // we didn't really find the marker.
                boolean verified = true;
                for (int k = 0; k < markerLen; k++) {
                    int from = likelyMarkerStart + (k * samplesPerBit);
                    int to = Math.min(from + samplesPerBit, bufferedAudio.length);

                    if (from >= to) {
                        if (VERBOSE_DECODE_DEBUG)
                            Log.d("DEBUG", "After phase alignment, marker could NOT be re-verified (reached audio end before verifying).");

                        verified = false;
                        break;
                    }

                    zeroCorrelation = correlateZero(bufferedAudio, from, to);
                    oneCorrelation = correlateOne(bufferedAudio, from, to);

                    if (VERBOSE_DECODE_DEBUG)
                        Log.d("DEBUG", "Re-verifying position [" + k + "], from: " + from + " to: " + to + " zeroCorrelation: " + zeroCorrelation + " oneCorrelation: " + oneCorrelation);

                    if ((zeroCorrelation < markerCorrelationThreshold && oneCorrelation < markerCorrelationThreshold) || // Poor match altogether? e.g. noisy audio or sampleOffset is poorly aligned to bits
                            (marker[k] == 0 && oneCorrelation > zeroCorrelation) || // This bit of marker is 0 and doesn't match?
                            (marker[k] == 1 && zeroCorrelation > oneCorrelation)) { // This bit of marker is 1 and doesn't match?
                        // This bit of marker DID NOT match
                        if (VERBOSE_DECODE_DEBUG)
                            Log.d("DEBUG", "After phase alignment, marker could NOT be re-verified.");

                        verified = false;
                        break;
                    }
                }

                if (verified) {
                    if (VERBOSE_DECODE_DEBUG)
                        Log.d("DEBUG", "Re-verified marker start: " + likelyMarkerStart);
                    return likelyMarkerStart;
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
            int to = Math.min(from + samplesPerBit, bufferedAudio.length);
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

        if (!DEBUG_SAVE_AUDIO)
            buffer.reset();
    }

    // This method precomputes the necessary sine and cosine values, because otherwise we'd be calling
    // Math.sin/cos over and over when decoding data and this is a very slow and expensive call.
    private void initializeSineTables() {
        sineTableZero = new double[samplesPerBit];
        sineTableOne = new double[samplesPerBit];
        cosineTableZero = new double[samplesPerBit];
        cosineTableOne = new double[samplesPerBit];

        for (int i = 0; i < samplesPerBit; i++) {
            sineTableZero[i] = Math.sin(2.0 * Math.PI * i / (sampleRate / freqZero));
            cosineTableZero[i] = Math.cos(2.0 * Math.PI * i / (sampleRate / freqZero));

            sineTableOne[i] = Math.sin(2.0 * Math.PI * i / (sampleRate / freqOne));
            cosineTableOne[i] = Math.cos(2.0 * Math.PI * i / (sampleRate / freqOne));
        }
    }

    private double correlate(byte[] samples, double[] sineTable, double[] cosineTable, int from, int to) {
        double sineSum = 0;
        double cosineSum = 0;

        for (int i = from, j = 0; i < to; i++, j++) {
            sineSum += samples[i] * sineTable[j];
            cosineSum += samples[i] * cosineTable[j];
        }

        // Return the magnitude of the correlation (in-phase and quadrature components combined)
        return Math.sqrt(sineSum * sineSum + cosineSum * cosineSum);
    }

    // Experimental alternative that multithreads the calculation. However, in my experiments
    // this actually uses more CPU overall. TODO Worth experimenting more with.
    /* private double correlate(byte[] samples, double[] sineTable, int from, int to) {
        return IntStream.range(from, to).parallel().mapToDouble(i -> samples[i] * sineTable[i - from]).sum();
    } */

    private double correlateZero(byte[] samples, int from, int to) {
        return correlate(samples, sineTableZero, cosineTableZero, from, to);
    }

    private double correlateOne(byte[] samples, int from, int to) {
        return correlate(samples, sineTableOne, cosineTableOne, from, to);
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
