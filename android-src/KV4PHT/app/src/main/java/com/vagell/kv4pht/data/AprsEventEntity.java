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

/** Room representation of the canonical APRS event history. */
@Entity(tableName = "aprs_events", indices = {
    @Index("first_seen_ms"), @Index(value = {"internet_only", "first_seen_ms"}),
    @Index(value = {"type", "to_callsign", "first_seen_ms"}),
    @Index(value = {"dedup_key", "last_seen_ms"}),
    @Index(value = {"delivery_state", "next_retry_at_ms"}),
    @Index(value = {"from_callsign", "to_callsign", "message_identifier"})
})
public class AprsEventEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @ColumnInfo(name = "type", defaultValue = "0") public int type;
    @ColumnInfo(name = "first_seen_ms") public long firstSeenMs;
    @ColumnInfo(name = "last_seen_ms") public long lastSeenMs;
    @ColumnInfo(name = "packet_count", defaultValue = "0") public int packetCount;
    @ColumnInfo(name = "digipeated", defaultValue = "0") public boolean digipeated;
    @ColumnInfo(name = "internet_only", defaultValue = "0") public boolean internetOnly;
    @ColumnInfo(name = "dedup_key") public String dedupKey;
    @ColumnInfo(name = "from_callsign") public String fromCallsign;
    @ColumnInfo(name = "to_callsign") public String toCallsign;
    @ColumnInfo(name = "message_identifier") public String messageIdentifier;
    @ColumnInfo(name = "body") public String body;
    @ColumnInfo(name = "position_lat") public double positionLat;
    @ColumnInfo(name = "position_long") public double positionLong;
    @ColumnInfo(name = "comment") public String comment;
    @ColumnInfo(name = "object_name") public String objectName;
    @ColumnInfo(name = "temperature") public double temperature;
    @ColumnInfo(name = "humidity") public double humidity;
    @ColumnInfo(name = "pressure") public double pressure;
    @ColumnInfo(name = "rain") public double rain;
    @ColumnInfo(name = "snow") public double snow;
    @ColumnInfo(name = "wind_force") public int windForce;
    @ColumnInfo(name = "wind_direction") public String windDirection;
    @ColumnInfo(name = "relay_callsign") public String relayCallsign;
    @ColumnInfo(name = "delivery_state", defaultValue = "0") public int deliveryState;
    @ColumnInfo(name = "transmit_attempts", defaultValue = "0") public int transmitAttempts;
    @ColumnInfo(name = "next_retry_at_ms") public Long nextRetryAtMs;
}
