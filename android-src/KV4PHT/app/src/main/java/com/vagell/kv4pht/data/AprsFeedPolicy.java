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

package com.vagell.kv4pht.data;

import io.github.dkaukov.aprs.AprsEvent;
import java.util.Locale;

/** Android feed projection policy; it deliberately does not belong to APRS protocol core. */
public final class AprsFeedPolicy {
    public static final String HISTORY_ONE_DAY = "1d";
    public static final String HISTORY_ONE_WEEK = "1w";
    public static final String HISTORY_TWO_WEEKS = "2w";
    public static final String HISTORY_ONE_MONTH = "1m";
    public static final String HISTORY_ALL = "all";
    public static final String DESTINATION_ALL = "all";
    public static final String DESTINATION_MINE = "mine";
    public static final int MAX_VISIBLE_EVENTS = 5_000;
    private static final long DAY_MS = 24 * 60 * 60_000L;

    private AprsFeedPolicy() { }

    public static String feedKey(AprsEvent event) {
        String source = normalizeCallsign(event.getFromCallsign());
        if (source.isEmpty()) return null;
        switch (event.getType()) {
            case AprsEvent.POSITION_TYPE: return "position:" + source;
            case AprsEvent.WEATHER_TYPE: return "weather:" + source;
            case AprsEvent.STATUS_TYPE: return "status:" + source;
            case AprsEvent.STATION_CAPABILITIES_TYPE: return "capabilities:" + source;
            case AprsEvent.OBJECT_TYPE:
                String objectName = event.getObjectName() == null ? ""
                    : event.getObjectName().trim().toUpperCase(Locale.ROOT);
                return objectName.isEmpty() ? null : "object:" + source + ":" + objectName;
            default: return null;
        }
    }

    public static String normalizeHistoryWindow(String value) {
        if (HISTORY_ONE_DAY.equalsIgnoreCase(value)) return HISTORY_ONE_DAY;
        if (HISTORY_ONE_WEEK.equalsIgnoreCase(value)) return HISTORY_ONE_WEEK;
        if (HISTORY_TWO_WEEKS.equalsIgnoreCase(value)) return HISTORY_TWO_WEEKS;
        if (HISTORY_ONE_MONTH.equalsIgnoreCase(value)) return HISTORY_ONE_MONTH;
        return HISTORY_ALL;
    }

    public static long historyStartMs(String window, long now) {
        switch (normalizeHistoryWindow(window)) {
            case HISTORY_ONE_DAY: return now - DAY_MS;
            case HISTORY_ONE_WEEK: return now - 7 * DAY_MS;
            case HISTORY_TWO_WEEKS: return now - 14 * DAY_MS;
            case HISTORY_ONE_MONTH: return now - 30 * DAY_MS;
            default: return 0L;
        }
    }

    public static String normalizeDestinationFilter(String value) {
        return DESTINATION_MINE.equalsIgnoreCase(value) ? DESTINATION_MINE : DESTINATION_ALL;
    }

    /** Mirrors the Room mine-filter predicate for app-side settings and unit tests. */
    public static boolean isVisibleToMine(AprsEvent event, String localCallsign) {
        if (event.getType() != AprsEvent.MESSAGE_TYPE) return true;
        String local = normalizeMessageCallsign(localCallsign);
        String from = normalizeMessageCallsign(event.getFromCallsign());
        String destination = normalizeMessageCallsign(event.getToCallsign());
        return local.equals(from) || local.equals(destination) || "ALL".equals(destination)
            || "QST".equals(destination) || "CQ".equals(destination)
            || destination.startsWith("BLN");
    }

    public static String normalizeCallsign(String callsign) {
        return callsign == null ? "" : callsign.trim().toUpperCase(Locale.ROOT);
    }

    static String normalizeMessageCallsign(String callsign) {
        String normalized = normalizeCallsign(callsign);
        return normalized.endsWith("-0")
            ? normalized.substring(0, normalized.length() - 2) : normalized;
    }
}
