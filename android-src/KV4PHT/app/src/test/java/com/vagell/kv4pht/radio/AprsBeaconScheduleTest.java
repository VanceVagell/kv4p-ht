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
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AprsBeaconScheduleTest {
    @Test public void enablingRequestsImmediatelyThenKeepsFiveMinuteCadence() {
        AprsBeaconSchedule schedule = new AprsBeaconSchedule(300_000);
        assertFalse(schedule.isDue(1_000));
        schedule.setEnabled(true);
        assertTrue(schedule.isDue(1_000));
        assertFalse(schedule.isDue(1_500));
        assertFalse(schedule.isDue(300_999));
        assertTrue(schedule.isDue(301_000));
    }

    @Test public void enablingAgainPreservesOriginalDeadline() {
        AprsBeaconSchedule schedule = new AprsBeaconSchedule(300_000);
        schedule.setEnabled(true);
        assertTrue(schedule.isDue(1_000));

        schedule.setEnabled(true);
        assertFalse(schedule.isDue(1_500));
        assertFalse(schedule.isDue(300_999));
        assertTrue(schedule.isDue(301_000));
    }

    @Test public void disablingAndReenablingRestartsWithoutCatchUpBursts() {
        AprsBeaconSchedule schedule = new AprsBeaconSchedule(300_000);
        schedule.setEnabled(true);
        assertTrue(schedule.isDue(1_000));
        schedule.setEnabled(false);
        assertFalse(schedule.isDue(900_000));
        schedule.setEnabled(true);
        assertTrue(schedule.isDue(900_000));
        assertTrue(schedule.isDue(2_000_000));
        assertFalse(schedule.isDue(2_000_001));
    }
}
