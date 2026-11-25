/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

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

package com.vagell.kv4pht.repeaterbook;

import com.vagell.kv4pht.ui.ToneHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client for the RepeaterBook Export API.
 *
 * <p>Distributed clients must use a per-user {@code rbuapp_} token and the approved
 * {@code kv4pht} User-Agent. The Export API does not expose a proximity query, so this
 * client fetches the user's state (US) or country (everywhere else) and filters the
 * results locally by distance, band, and FM analog suitability.</p>
 */
public class RepeaterBookApiClient {
    public static final String USER_AGENT = "kv4pht";
    public static final String TOKEN_PREFIX = "rbuapp_";
    public static final double SEARCH_RADIUS_KM = 40.2336; // 25 miles
    public static final int TOKEN_LENGTH = 71; // "rbuapp_" plus 64 hex characters

    private static final String API_BASE = "https://www.repeaterbook.com/api";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final Map<String, String> US_STATE_IDS = buildUsStateIds();

    /**
     * One repeater returned by the Export API, reduced to the fields kv4p HT needs.
     */
    public static class RepeaterInfo {
        public double freq;
        public double input;
        public double offset;
        public String tone;
        public String location;
        public String state;
        public String county;
        public String call;
        public String use;
        public double miles;
        public String bearing;
        public double degrees;

        @Override
        public String toString() {
            return String.format(Locale.US, "%s @ %s (%.5f MHz)", call, location, freq);
        }
    }

    /**
     * A failure returned by the API, a redirect that must not be followed, or a
     * transport/parsing problem. The code is suitable for choosing a user-facing message.
     */
    public static class ApiException extends Exception {
        private final String code;

        public ApiException(String code, String message) {
            super(message);
            this.code = code;
        }

        public ApiException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public static boolean looksLikeUserAppToken(String token) {
        if (token == null) {
            return false;
        }
        String trimmed = token.trim();
        return trimmed.length() == TOKEN_LENGTH
                && trimmed.startsWith(TOKEN_PREFIX)
                && trimmed.substring(TOKEN_PREFIX.length()).matches("[0-9a-fA-F]{64}");
    }

    /**
     * Fetches and filters nearby analog FM repeaters for the user's location.
     *
     * @param token      the user's RepeaterBook {@code rbuapp_} token
     * @param latitude   user latitude
     * @param longitude  user longitude
     * @param country    English country name from reverse geocoding
     * @param adminArea  state/province name from reverse geocoding
     * @param minFreqMhz lowest frequency the connected radio can use
     * @param maxFreqMhz highest frequency the connected radio can use
     */
    public List<RepeaterInfo> fetchNearbyRepeaters(String token, double latitude, double longitude,
                                                   String country, String adminArea,
                                                   float minFreqMhz, float maxFreqMhz)
            throws ApiException {
        if (!looksLikeUserAppToken(token)) {
            throw new ApiException("auth_invalid", "The RepeaterBook token is missing or malformed.");
        }
        String requestUrl = buildExportUrl(country, adminArea);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("X-RB-App-Token", token.trim());
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");

            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                // Do not follow redirects with a credential header attached.
                throw new ApiException("redirect", "RepeaterBook returned an unexpected redirect.");
            }
            String body = readBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream());

            if (status == 429) {
                throw new ApiException("rate_limited", "RepeaterBook is rate limiting requests.");
            }
            if (status < 200 || status >= 300) {
                throw apiExceptionFromBody(status, body);
            }
            return parseAndFilter(body, latitude, longitude, minFreqMhz, maxFreqMhz);
        } catch (IOException e) {
            throw new ApiException("network", "Could not reach RepeaterBook.", e);
        } catch (JSONException e) {
            throw new ApiException("parse", "RepeaterBook returned data in an unexpected format.", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String buildExportUrl(String country, String adminArea) {
        String normalizedCountry = country == null ? "" : country.trim();
        if (isUnitedStates(normalizedCountry)) {
            String stateId = stateIdForAdminArea(adminArea);
            if (stateId != null) {
                return API_BASE + "/export.php?state_id=" + stateId + "&mode=analog";
            }
            return API_BASE + "/export.php?country=" + urlEncode("United States") + "&mode=analog";
        }
        if (isNorthAmericanCountry(normalizedCountry)) {
            return API_BASE + "/export.php?country=" + urlEncode(normalizedCountry) + "&mode=analog";
        }
        return API_BASE + "/exportROW.php?country=" + urlEncode(normalizedCountry) + "&mode=analog";
    }

    static boolean isUnitedStates(String country) {
        if (country == null) {
            return false;
        }
        String normalized = country.trim().toLowerCase(Locale.US);
        return normalized.equals("united states")
                || normalized.equals("united states of america")
                || normalized.equals("the united states")
                || normalized.equals("usa")
                || normalized.equals("u.s.a.")
                || normalized.equals("us")
                || normalized.equals("u.s.");
    }

    static boolean isNorthAmericanCountry(String country) {
        if (country == null) {
            return false;
        }
        String normalized = country.trim().toLowerCase(Locale.US);
        return isUnitedStates(country)
                || normalized.equals("canada")
                || normalized.equals("mexico");
    }

    /**
     * Maps a US state/territory name or abbreviation to the FIPS state_id used by the
     * RepeaterBook Export API. Returns null for non-US locations.
     */
    static String stateIdForAdminArea(String adminArea) {
        if (adminArea == null) {
            return null;
        }
        String key = adminArea.trim().toLowerCase(Locale.US);
        if (key.isEmpty()) {
            return null;
        }
        String direct = US_STATE_IDS.get(key);
        if (direct != null) {
            return direct;
        }
        // Some geocoders add a suffix such as "Washington State" or
        // "Commonwealth of Virginia".
        String normalized = key.replace(" commonwealth of ", " ")
                .replace(" state", "")
                .trim();
        direct = US_STATE_IDS.get(normalized);
        if (direct != null) {
            return direct;
        }
        // Some geocoders return "Washington, DC" or similar.
        int comma = normalized.indexOf(',');
        if (comma > 0) {
            return US_STATE_IDS.get(normalized.substring(0, comma).trim());
        }
        return null;
    }

    static List<RepeaterInfo> parseAndFilter(String json, double latitude, double longitude,
                                             float minFreqMhz, float maxFreqMhz) throws JSONException {
        List<RepeaterInfo> repeaters = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            return repeaters;
        }

        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (!"yes".equalsIgnoreCase(item.optString("FM Analog").trim())) {
                continue;
            }
            if (!"open".equalsIgnoreCase(item.optString("Use").trim())) {
                continue;
            }
            if (!"on-air".equalsIgnoreCase(item.optString("Operational Status").trim())) {
                continue;
            }

            double freq = parseDouble(item.optString("Frequency"));
            double input = parseDouble(item.optString("Input Freq"));
            if (freq <= 0 || input <= 0) {
                continue;
            }
            if (minFreqMhz > 0 && freq < minFreqMhz) {
                continue;
            }
            if (maxFreqMhz > 0 && freq > maxFreqMhz) {
                continue;
            }

            double repeaterLat = parseDouble(item.optString("Lat"));
            double repeaterLon = parseDouble(item.optString("Long"));
            if (repeaterLat == 0 && repeaterLon == 0) {
                continue;
            }
            double distanceKm = distanceKm(latitude, longitude, repeaterLat, repeaterLon);
            if (distanceKm > SEARCH_RADIUS_KM) {
                continue;
            }

            RepeaterInfo repeater = new RepeaterInfo();
            repeater.freq = freq;
            repeater.input = input;
            repeater.offset = freq - input;
            repeater.tone = ToneHelper.normalizeTone(item.optString("PL"));
            repeater.location = cleanLocation(item.optString("Nearest City"));
            if (repeater.location.isEmpty()) {
                repeater.location = cleanLocation(item.optString("Landmark"));
            }
            repeater.state = item.optString("State");
            repeater.county = item.optString("County");
            repeater.call = item.optString("Callsign");
            repeater.use = item.optString("Use");
            repeater.miles = distanceKm / 1.609344;
            repeater.degrees = bearingDegrees(latitude, longitude, repeaterLat, repeaterLon);
            repeater.bearing = compassDirection(repeater.degrees);
            repeaters.add(repeater);
        }

        repeaters.sort(Comparator.comparingDouble(repeater -> repeater.miles));
        return repeaters;
    }

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusKm = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadiusKm * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(deltaLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    static String compassDirection(double degrees) {
        String[] directions = {
                "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
        };
        int index = (int) Math.round(degrees / 22.5) % 16;
        return directions[index];
    }

    private static String cleanLocation(String location) {
        if (location == null) {
            return "";
        }
        return location.replace("\n", " ").replace("\r", "").trim();
    }

    private static double parseDouble(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static ApiException apiExceptionFromBody(int status, String body) {
        String code = "http_" + status;
        String message = "RepeaterBook refused the request.";
        try {
            JSONObject error = new JSONObject(body);
            String errorCode = error.optString("error_code");
            if (errorCode.isEmpty()) {
                errorCode = error.optString("error");
            }
            if (!errorCode.isEmpty()) {
                code = errorCode;
            }
            String errorMessage = error.optString("message");
            if (!errorMessage.isEmpty()) {
                message = errorMessage;
            }
        } catch (JSONException ignored) {
            // Keep the generic HTTP error.
        }
        return new ApiException(code, message);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return value;
        }
    }

    private static Map<String, String> buildUsStateIds() {
        Map<String, String> states = new HashMap<>();
        addState(states, "AL", "01", "Alabama");
        addState(states, "AK", "02", "Alaska");
        addState(states, "AZ", "04", "Arizona");
        addState(states, "AR", "05", "Arkansas");
        addState(states, "CA", "06", "California");
        addState(states, "CO", "08", "Colorado");
        addState(states, "CT", "09", "Connecticut");
        addState(states, "DE", "10", "Delaware");
        addState(states, "DC", "11", "District of Columbia");
        states.put("washington, dc", "11");
        states.put("washington dc", "11");
        states.put("washington, d.c.", "11");
        states.put("united states virgin islands", "78");
        addState(states, "FL", "12", "Florida");
        addState(states, "GA", "13", "Georgia");
        addState(states, "HI", "15", "Hawaii");
        addState(states, "ID", "16", "Idaho");
        addState(states, "IL", "17", "Illinois");
        addState(states, "IN", "18", "Indiana");
        addState(states, "IA", "19", "Iowa");
        addState(states, "KS", "20", "Kansas");
        addState(states, "KY", "21", "Kentucky");
        addState(states, "LA", "22", "Louisiana");
        addState(states, "ME", "23", "Maine");
        addState(states, "MD", "24", "Maryland");
        addState(states, "MA", "25", "Massachusetts");
        addState(states, "MI", "26", "Michigan");
        addState(states, "MN", "27", "Minnesota");
        addState(states, "MS", "28", "Mississippi");
        addState(states, "MO", "29", "Missouri");
        addState(states, "MT", "30", "Montana");
        addState(states, "NE", "31", "Nebraska");
        addState(states, "NV", "32", "Nevada");
        addState(states, "NH", "33", "New Hampshire");
        addState(states, "NJ", "34", "New Jersey");
        addState(states, "NM", "35", "New Mexico");
        addState(states, "NY", "36", "New York");
        addState(states, "NC", "37", "North Carolina");
        addState(states, "ND", "38", "North Dakota");
        addState(states, "OH", "39", "Ohio");
        addState(states, "OK", "40", "Oklahoma");
        addState(states, "OR", "41", "Oregon");
        addState(states, "PA", "42", "Pennsylvania");
        addState(states, "RI", "44", "Rhode Island");
        addState(states, "SC", "45", "South Carolina");
        addState(states, "SD", "46", "South Dakota");
        addState(states, "TN", "47", "Tennessee");
        addState(states, "TX", "48", "Texas");
        addState(states, "UT", "49", "Utah");
        addState(states, "VT", "50", "Vermont");
        addState(states, "VA", "51", "Virginia");
        addState(states, "WA", "53", "Washington");
        addState(states, "WV", "54", "West Virginia");
        addState(states, "WI", "55", "Wisconsin");
        addState(states, "WY", "56", "Wyoming");
        addState(states, "AS", "60", "American Samoa");
        addState(states, "GU", "66", "Guam");
        addState(states, "MP", "69", "Northern Mariana Islands");
        addState(states, "PR", "72", "Puerto Rico");
        addState(states, "VI", "78", "U.S. Virgin Islands");
        return states;
    }

    private static void addState(Map<String, String> states, String abbreviation, String id, String name) {
        states.put(abbreviation.toLowerCase(Locale.US), id);
        states.put(name.toLowerCase(Locale.US), id);
    }
}