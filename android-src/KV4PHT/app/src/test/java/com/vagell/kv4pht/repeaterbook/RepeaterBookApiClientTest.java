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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RepeaterBookApiClientTest {
    private static final String VALID_TOKEN =
            "rbuapp_0000000000000000000000000000000000000000000000000000000000000000";

    @Test
    public void acceptsOnlyUserAppTokens() {
        assertTrue(RepeaterBookApiClient.looksLikeUserAppToken(VALID_TOKEN));
        assertTrue(RepeaterBookApiClient.looksLikeUserAppToken("  " + VALID_TOKEN + "  "));
        assertFalse(RepeaterBookApiClient.looksLikeUserAppToken(null));
        assertFalse(RepeaterBookApiClient.looksLikeUserAppToken(""));
        assertFalse(RepeaterBookApiClient.looksLikeUserAppToken("app_0000000000000000000000000000000000000000000000000000000000000000"));
        assertFalse(RepeaterBookApiClient.looksLikeUserAppToken("rbuapp_1234"));
        assertFalse(RepeaterBookApiClient.looksLikeUserAppToken("rbuapp_" + repeat("z", 64)));
    }

    @Test
    public void mapsUsStatesAndTerritoriesToFipsIds() {
        assertEquals("53", RepeaterBookApiClient.stateIdForAdminArea("Washington"));
        assertEquals("53", RepeaterBookApiClient.stateIdForAdminArea("WA"));
        assertEquals("06", RepeaterBookApiClient.stateIdForAdminArea("California"));
        assertEquals("11", RepeaterBookApiClient.stateIdForAdminArea("District of Columbia"));
        assertEquals("72", RepeaterBookApiClient.stateIdForAdminArea("Puerto Rico"));
        assertEquals("11", RepeaterBookApiClient.stateIdForAdminArea("Washington, DC"));
        assertEquals(null, RepeaterBookApiClient.stateIdForAdminArea("Ontario"));
    }

    @Test
    public void buildsNorthAmericaAndRestOfWorldUrls() {
        String usUrl = RepeaterBookApiClient.buildExportUrl("United States", "Washington");
        assertTrue(usUrl.contains("/export.php?state_id=53"));
        assertTrue(usUrl.contains("mode=analog"));

        String canadaUrl = RepeaterBookApiClient.buildExportUrl("Canada", "Ontario");
        assertTrue(canadaUrl.contains("/export.php?country=Canada"));

        String switzerlandUrl = RepeaterBookApiClient.buildExportUrl("Switzerland", "Zurich");
        assertTrue(switzerlandUrl.contains("/exportROW.php?country=Switzerland"));
    }

    @Test
    public void calculatesDistanceAndBearing() {
        assertEquals(0.0, RepeaterBookApiClient.distanceKm(47.6, -122.3, 47.6, -122.3), 0.0001);
        assertEquals(111.2, RepeaterBookApiClient.distanceKm(0.0, 0.0, 1.0, 0.0), 0.5);
        assertEquals("N", RepeaterBookApiClient.compassDirection(0));
        assertEquals("E", RepeaterBookApiClient.compassDirection(90));
        assertEquals("S", RepeaterBookApiClient.compassDirection(180));
        assertEquals("W", RepeaterBookApiClient.compassDirection(270));
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}