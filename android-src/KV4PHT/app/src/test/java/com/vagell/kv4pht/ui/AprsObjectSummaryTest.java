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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AprsObjectSummaryTest {
    @Test public void formatsDstarRepeaterObject() {
        AprsObjectSummary summary = AprsObjectSummary.from("VK3XB  B",
            "RNG0001/A=000010 70cm Voice (D-Star) 439.12500MHz +0.0000MHz, "
                + "APRS for ircDDBGateway");

        assertEquals("70cm Voice D-Star · APRS for ircDDBGateway\n"
            + "439.125 MHz · Simplex\nRange 1 mi · Altitude 10 ft", summary.cardText);
        assertEquals("VK3XB B · 439.125 MHz · D-Star", summary.mapLabel);
    }

    @Test public void formatsVoiceRepeaterWithBareFrequencyAndOffset() {
        AprsObjectSummary summary = AprsObjectSummary.from("VK3RWN C",
            "RNG0070 2m Voice 146.9125 -0.600 MHz");

        assertEquals("2m Voice\n146.9125 MHz · Offset -0.6 MHz\nRange 70 mi",
            summary.cardText);
        assertEquals("VK3RWN C · 146.9125 MHz · Voice", summary.mapLabel);
    }

    @Test public void formatsFrequencyPairAndHistoricalParserPrefix() {
        AprsObjectSummary summary = AprsObjectSummary.from("VK3FUR-4",
            "#145.175/439.100 VK3FUR-10 Winlink Packet Gateway");

        assertEquals("VK3FUR-10 Winlink Packet Gateway\n145.175 / 439.1 MHz",
            summary.cardText);
        assertEquals("VK3FUR-4 · 145.175 / 439.1 MHz · Winlink", summary.mapLabel);
    }

    @Test public void fallsBackToUnstructuredDescription() {
        AprsObjectSummary summary = AprsObjectSummary.from("MEETING", "Club picnic");

        assertEquals("Club picnic", summary.cardText);
        assertEquals("MEETING", summary.mapLabel);
    }
}
