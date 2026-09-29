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

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Materialized row identity for the user-visible APRS feed. */
@Entity(
    tableName = "aprs_feed",
    foreignKeys = @ForeignKey(
        entity = AprsEventEntity.class,
        parentColumns = "id",
        childColumns = "event_id",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {
        @Index(value = "event_id", unique = true),
        @Index("sort_time_ms")
    }
)
public class AprsFeedItem {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "feed_key")
    public String feedKey;

    @ColumnInfo(name = "event_id")
    public long eventId;

    @ColumnInfo(name = "sort_time_ms")
    public long sortTimeMs;

    @ColumnInfo(name = "event_count", defaultValue = "1")
    public int eventCount;

    public AprsFeedItem(@NonNull String feedKey, long eventId, long sortTimeMs, int eventCount) {
        this.feedKey = feedKey;
        this.eventId = eventId;
        this.sortTimeMs = sortTimeMs;
        this.eventCount = eventCount;
    }
}
