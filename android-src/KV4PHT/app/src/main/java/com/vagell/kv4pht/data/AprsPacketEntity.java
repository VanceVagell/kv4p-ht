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

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Room representation of immutable APRS packet transport history. */
@Entity(tableName = "aprs_packets", indices = {
    @Index("event_id"), @Index("timestamp_ms"), @Index("from_callsign")
})
public class AprsPacketEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @ColumnInfo(name = "event_id") public Long eventId;
    @ColumnInfo(name = "timestamp_ms") public long timestampMs;
    @ColumnInfo(name = "source", defaultValue = "'UNKNOWN'") public String source;
    @ColumnInfo(name = "frequency_hz") public Long frequencyHz;
    @ColumnInfo(name = "from_callsign") public String fromCallsign;
    @ColumnInfo(name = "ax25_destination") public String ax25Destination;
    @ColumnInfo(name = "path") public String path;
    @ColumnInfo(name = "raw_ax25") public byte[] rawAx25;
    @ColumnInfo(name = "raw_tnc2") public String rawTnc2;
}
