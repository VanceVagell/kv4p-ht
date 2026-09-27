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

/** App-owned cadence for beacons needing asynchronous GPS and RF frequency switching. */
final class AprsBeaconSchedule {
    private final long intervalMs;
    private boolean enabled;
    private long nextAtMs;

    AprsBeaconSchedule(long intervalMs) {
        if (intervalMs <= 0) throw new IllegalArgumentException("Beacon interval must be positive");
        this.intervalMs = intervalMs;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        nextAtMs = 0;
    }

    boolean isDue(long nowMs) {
        if (!enabled || nowMs < nextAtMs) return false;
        nextAtMs = nowMs + intervalMs;
        return true;
    }
}
