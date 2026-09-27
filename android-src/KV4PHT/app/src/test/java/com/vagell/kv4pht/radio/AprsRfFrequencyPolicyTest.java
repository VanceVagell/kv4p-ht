/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/


package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AprsRfFrequencyPolicyTest {
    @Test public void offsetMemoryUsesTxFrequencyForGuardAndHistory() {
        Long txHz = AprsRfFrequencyPolicy.toHz(146.340f);
        assertEquals(Long.valueOf(146340000L), txHz);
        assertFalse(AprsRfFrequencyPolicy.matches(146940000L, txHz));
        assertTrue(AprsRfFrequencyPolicy.matches(146340000L, txHz));
        assertTrue(AprsRfFrequencyPolicy.matches(null, txHz));
    }

    @Test public void unknownTxFrequencyIsNotReportedAsZero() {
        assertNull(AprsRfFrequencyPolicy.toHz(0));
        assertNull(AprsRfFrequencyPolicy.toHz(Float.NaN));
        assertNull(AprsRfFrequencyPolicy.toHz(Float.POSITIVE_INFINITY));
    }
    @Test public void explicitRequestRequiresMatchingKnownFrequency() {
        assertTrue(AprsRfFrequencyPolicy.matches(144390000L, 144390000L));
        assertFalse(AprsRfFrequencyPolicy.matches(144390000L, 145175000L));
        assertFalse(AprsRfFrequencyPolicy.matches(144390000L, null));
    }

    @Test public void unspecifiedRequestUsesCurrentFrequency() {
        assertTrue(AprsRfFrequencyPolicy.matches(null, 145175000L));
        assertTrue(AprsRfFrequencyPolicy.matches(null, null));
    }
}
