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

package com.vagell.kv4pht.aprs.parser;

/**
 * A mapping of icon types to the APRS spec icon character used to represent it in a
 * position message (e.g. for a beacon). This is not the exhaustive list of icons supported
 * in the spec, only those used by the kv4p HT application.
 *
 * These icons are shown by APRS clients, typically on map-based views. For example, someone
 * with an APRS beacon in their car might want a car icon to show up representing them on other
 * people's APRS clients.
 *
 * TODO: If we ever add an APRS map, we'll need to add all the missing types and icons.
 */
public enum APRSIconType {
    T_PHONE('$'),
    T_PERSON('['),
    T_HOUSE('-'),
    T_BICYCLE('b'),
    T_CAR('>'),
    T_JEEP('j'),
    T_TRUCK('k'),
    T_MOTORCYCLE('<'),
    T_VAN('v'),
    T_RV('R'),
    T_18_WHEELER('u'),
    T_GLIDER('g'),
    T_SMALL_AIRCRAFT('\''),
    T_HELICOPTER('X'),
    T_SAILBOAT('Y'),
    T_MOTORBOAT('s');

    private final char code;

    APRSIconType(char code) {
        this.code = code;
    }

    public char getCode() {
        return this.code;
    }

    public static APRSIconType fromCode(char code) {
        for (APRSIconType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("Invalid APRSIconType code: " + code);
    }
}
