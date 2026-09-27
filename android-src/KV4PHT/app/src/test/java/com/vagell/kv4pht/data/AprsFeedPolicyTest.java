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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.dkaukov.aprs.AprsEvent;
import org.junit.Test;

/** Covers Android-only APRS feed projection policy separately from protocol core tests. */
public class AprsFeedPolicyTest {
    @Test public void latestOnlyTypesUseStableSlots() {
        assertEquals("position:VK3ABC", key(AprsEvent.POSITION_TYPE, "vk3abc", null));
        assertEquals("weather:VK3ABC", key(AprsEvent.WEATHER_TYPE, "VK3ABC", null));
        assertEquals("status:VK3ABC", key(AprsEvent.STATUS_TYPE, "VK3ABC", null));
        assertEquals("capabilities:VK3ABC",
            key(AprsEvent.STATION_CAPABILITIES_TYPE, "VK3ABC", null));
        assertEquals("object:VK3ABC:TEST", key(AprsEvent.OBJECT_TYPE, "VK3ABC", "test"));
    }

    @Test public void messagesUnknownAndUnnamedObjectsRemainIndividualRows() {
        assertNull(key(AprsEvent.MESSAGE_TYPE, "VK3ABC", null));
        assertNull(key(AprsEvent.UNKNOWN_TYPE, "VK3ABC", null));
        assertNull(key(AprsEvent.OBJECT_TYPE, "VK3ABC", "  "));
    }

    @Test public void historyWindowsUseFirstSeenTimeAndKeepAllUnbounded() {
        long now = 50L * 24 * 60 * 60_000L;
        long day = 24 * 60 * 60_000L;
        assertEquals(now - day, AprsFeedPolicy.historyStartMs(AprsFeedPolicy.HISTORY_ONE_DAY, now));
        assertEquals(now - 7 * day,
            AprsFeedPolicy.historyStartMs(AprsFeedPolicy.HISTORY_ONE_WEEK, now));
        assertEquals(now - 14 * day,
            AprsFeedPolicy.historyStartMs(AprsFeedPolicy.HISTORY_TWO_WEEKS, now));
        assertEquals(now - 30 * day,
            AprsFeedPolicy.historyStartMs(AprsFeedPolicy.HISTORY_ONE_MONTH, now));
        assertEquals(0L, AprsFeedPolicy.historyStartMs(AprsFeedPolicy.HISTORY_ALL, now));
    }

    @Test public void mineFilterIncludesOwnAndBroadcastMessagesButNotOtherDirectMessages() {
        assertTrue(visible("VK3ME", "VK3OTHER"));
        assertTrue(visible("VK3OTHER", "VK3ME"));
        assertTrue(visible("VK3ME-0", "VK3OTHER"));
        assertTrue(visible("VK3OTHER", "VK3ME-0"));
        assertFalse(visible("VK3OTHER", "VK3ME-1"));
        assertTrue(visible("VK3OTHER", "BLN1CQ"));
        assertTrue(visible("VK3OTHER", "QST"));
        assertFalse(visible("VK3OTHER", "VK3ELSE"));
        AprsEvent position = AprsEvent.builder()
            .type(AprsEvent.POSITION_TYPE)
            .build();
        assertTrue(AprsFeedPolicy.isVisibleToMine(position, "VK3ME"));
    }

    private static String key(int type, String source, String objectName) {
        AprsEvent event = AprsEvent.builder()
            .type(type)
            .fromCallsign(source)
            .objectName(objectName)
            .build();
        return AprsFeedPolicy.feedKey(event);
    }

    private static boolean visible(String from, String to) {
        AprsEvent event = AprsEvent.builder()
            .type(AprsEvent.MESSAGE_TYPE)
            .fromCallsign(from)
            .toCallsign(to)
            .build();
        return AprsFeedPolicy.isVisibleToMine(event, "VK3ME");
    }
}
