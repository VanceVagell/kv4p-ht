/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2026 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.ui;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Produces conservative human-readable summaries of common APRS object comments. */
final class AprsObjectSummary {
    private static final Pattern HISTORIC_SYMBOL_PREFIX = Pattern.compile(
        "^[^a-z0-9](?=(?:RNG\\d{4}|R\\d{1,4}[km]|\\d{3,4}\\.\\d))",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORIC_LETTER_PREFIX = Pattern.compile(
        "^[air]\\s*(?=(?:RNG\\d{4}|R\\d{1,4}[km]))", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE = Pattern.compile("\\bRNG(\\d{4})\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_RANGE = Pattern.compile("\\bR(\\d{1,4})([km])\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTITUDE = Pattern.compile("/A=(\\d{6})\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFSET = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)\\s*MHz\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern FREQUENCY_PAIR = Pattern.compile(
        "\\b(\\d{3,4}(?:\\.\\d+)?)\\s*/\\s*(\\d{3,4}(?:\\.\\d+)?)\\b");
    private static final Pattern FREQUENCY = Pattern.compile(
        "(?<![\\d.+-])(\\d{3,4}(?:\\.\\d+)?)\\s*MHz\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_FREQUENCY_BEFORE_OFFSET = Pattern.compile(
        "\\b(\\d{3,4}(?:\\.\\d+)?)\\s+(?=[+-]\\d)");
    private static final Pattern MODE = Pattern.compile("\\(([^)]{1,20})\\)");

    final String cardText;
    final String mapLabel;

    private AprsObjectSummary(String cardText, String mapLabel) {
        this.cardText = cardText;
        this.mapLabel = mapLabel;
    }

    static AprsObjectSummary from(String objectName, String comment) {
        String name = normalizeWhitespace(objectName);
        String remaining = comment == null ? "" : comment.trim();
        remaining = HISTORIC_SYMBOL_PREFIX.matcher(remaining).replaceFirst("");
        remaining = HISTORIC_LETTER_PREFIX.matcher(remaining).replaceFirst("");

        String mode = firstGroup(MODE, remaining);
        Extracted range = extractRange(remaining);
        remaining = range.remaining;
        Extracted altitude = extract(ALTITUDE, remaining, value ->
            "Altitude " + Integer.parseInt(value) + " ft");
        remaining = altitude.remaining;
        Extracted frequencies = extractFrequencies(remaining);
        remaining = frequencies.remaining;
        Extracted offset = extract(OFFSET, remaining, AprsObjectSummary::formatOffset);
        remaining = offset.remaining;

        String description = cleanDescription(remaining);
        List<String> cardLines = new ArrayList<>();
        if (!description.isEmpty()) cardLines.add(description);
        String frequencyLine = joinParts(frequencies.value, offset.value);
        if (!frequencyLine.isEmpty()) cardLines.add(frequencyLine);
        String detailLine = joinParts(range.value, altitude.value);
        if (!detailLine.isEmpty()) cardLines.add(detailLine);
        String card = cardLines.isEmpty() ? normalizeWhitespace(comment)
            : String.join("\n", cardLines);

        List<String> labelParts = new ArrayList<>();
        if (!name.isEmpty()) labelParts.add(name);
        if (!frequencies.value.isEmpty()) labelParts.add(frequencies.value);
        String service = serviceName(mode, description);
        if (!service.isEmpty()) labelParts.add(service);
        return new AprsObjectSummary(card, String.join(" · ", labelParts));
    }

    private static Extracted extractRange(String text) {
        Matcher standard = RANGE.matcher(text);
        if (standard.find()) {
            String value = "Range " + Integer.parseInt(standard.group(1)) + " mi";
            return new Extracted(removeMatch(text, standard), value);
        }
        Matcher shortRange = SHORT_RANGE.matcher(text);
        if (!shortRange.find()) return new Extracted(text, "");
        String unit = shortRange.group(2).equalsIgnoreCase("k") ? "km" : "mi";
        String value = "Range " + Integer.parseInt(shortRange.group(1)) + " " + unit;
        return new Extracted(removeMatch(text, shortRange), value);
    }

    private static Extracted extractFrequencies(String text) {
        Matcher pair = FREQUENCY_PAIR.matcher(text);
        if (pair.find()) {
            String value = decimal(pair.group(1)) + " / " + decimal(pair.group(2)) + " MHz";
            return new Extracted(removeMatch(text, pair), value);
        }
        Matcher frequency = FREQUENCY.matcher(text);
        if (frequency.find()) {
            String value = decimal(frequency.group(1)) + " MHz";
            return new Extracted(removeMatch(text, frequency), value);
        }
        Matcher bare = BARE_FREQUENCY_BEFORE_OFFSET.matcher(text);
        if (bare.find()) {
            String value = decimal(bare.group(1)) + " MHz";
            return new Extracted(removeMatch(text, bare), value);
        }
        return new Extracted(text, "");
    }

    private static Extracted extract(Pattern pattern, String text, Formatter formatter) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return new Extracted(text, "");
        return new Extracted(removeMatch(text, matcher), formatter.format(matcher.group(1)));
    }

    private static String removeMatch(String text, Matcher matcher) {
        return text.substring(0, matcher.start()) + " " + text.substring(matcher.end());
    }

    private static String formatOffset(String raw) {
        BigDecimal offset = new BigDecimal(raw);
        if (offset.compareTo(BigDecimal.ZERO) == 0) return "Simplex";
        return "Offset " + offset.stripTrailingZeros().toPlainString() + " MHz";
    }

    private static String decimal(String raw) {
        return new BigDecimal(raw).stripTrailingZeros().toPlainString();
    }

    private static String cleanDescription(String value) {
        return normalizeWhitespace(joinCommaSeparatedParts(
            value.replace('(', ' ').replace(')', ' ')));
    }

    private static String joinCommaSeparatedParts(String value) {
        StringBuilder result = new StringBuilder();
        for (String part : value.split(",")) {
            String normalized = part.trim();
            if (normalized.isEmpty()) continue;
            if (result.length() > 0) result.append(" · ");
            result.append(normalized);
        }
        return result.toString();
    }

    private static String serviceName(String mode, String description) {
        if (mode != null && !mode.trim().isEmpty()) return normalizeWhitespace(mode);
        String normalized = description.toLowerCase(Locale.ROOT);
        if (normalized.contains("winlink")) return "Winlink";
        if (normalized.contains("voice")) return "Voice";
        if (normalized.contains("data")) return "Data";
        if (normalized.contains("bbs")) return "BBS";
        return "";
    }

    private static String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String joinParts(String first, String second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        return first + " · " + second;
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private interface Formatter {
        String format(String value);
    }

    private static final class Extracted {
        final String remaining;
        final String value;

        Extracted(String remaining, String value) {
            this.remaining = remaining;
            this.value = value;
        }
    }
}
