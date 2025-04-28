package com.vagell.kv4pht.ui;

import java.util.List;
import java.util.Arrays;

public class ToneHelper {
    // Valid tones as doubles for numeric comparison
    private static final double[] VALID_TONE_VALUES = {
            67, 71.9, 74.4, 77, 79.7, 82.5, 85.4, 88.5,
            91.5, 94.8, 97.4, 100, 103.5, 107.2, 110.9, 114.8,
            118.8, 123, 127.3, 131.8, 136.5, 141.3, 146.2, 151.4,
            156.7, 162.2, 167.9, 173.8, 179.9, 186.2, 192.8, 203.5,
            210.7, 218.1, 225.7, 233.6, 241.8, 250.3
    };

    // String representations for exact matching
    public static final List<String> VALID_TONE_STRINGS = Arrays.asList(
            "None", "67", "71.9", "74.4", "77", "79.7", "82.5", "85.4", "88.5",
            "91.5", "94.8", "97.4", "100", "103.5", "107.2", "110.9", "114.8",
            "118.8", "123", "127.3", "131.8", "136.5", "141.3", "146.2", "151.4",
            "156.7", "162.2", "167.9", "173.8", "179.9", "186.2", "192.8", "203.5",
            "210.7", "218.1", "225.7", "233.6", "241.8", "250.3"
    );

    public static boolean isValidTone(String tone) {
        return VALID_TONE_STRINGS.contains(tone);
    }

    /**
     * @param inputTone An rx or tx tone as it would be shown in the UI, such as "None", "82.5", or "100.0".
     * @return A normalized version of the time (e.g. closest valid tone within reason), or "None".
     */
    public static String normalizeTone(String inputTone) {
        if (inputTone == null || inputTone.trim().isEmpty()) {
            return "None";
        }

        String tone = inputTone.trim();

        // First check if it's an exact string match (including "None")
        if (isValidTone(tone)) {
            return tone;
        }

        // Try to parse as number
        try {
            double inputValue = Double.parseDouble(tone);

            // Special cases that should default to None
            if (inputValue == 0.0 || inputValue == 1.0) {
                return "None";
            }

            // Find the closest valid tone within 1.0 Hz tolerance
            double closestTone = -1;
            double minDistance = Double.MAX_VALUE;

            for (double validTone : VALID_TONE_VALUES) {
                double distance = Math.abs(inputValue - validTone);
                if (distance <= 1.0 && distance < minDistance) {
                    closestTone = validTone;
                    minDistance = distance;
                }
            }

            if (closestTone != -1) {
                // Return the string representation of the closest valid tone
                if (closestTone == (int)closestTone) {
                    return String.valueOf((int)closestTone); // e.g., 100 instead of 100.0
                } else {
                    return String.valueOf(closestTone);
                }
            }

        } catch (NumberFormatException e) {
            // Not a valid number, fall through to return "None"
        }

        // If we get here, the input wasn't a valid tone
        return "None";
    }

    /**
     * @param tone A rx or tx tone to find the index of, or "None" if no tone.
     * @return The index of the tone, as expected by a DRA818 or SA818S radio module.
     */
    public static int getToneIndex(String tone) {
        if (tone == null) {
            return -1;
        }

        // Normalize the tone first (handles numeric imprecision)
        String normalizedTone = normalizeTone(tone);

        // Find the index in VALID_TONE_STRINGS
        return VALID_TONE_STRINGS.indexOf(normalizedTone);
    }
}
