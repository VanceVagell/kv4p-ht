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
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "channel_memories")
public class ChannelMemory {
    public static final int OFFSET_NONE = 0;
    public static final int OFFSET_DOWN = 1;
    public static final int OFFSET_UP = 2;

    @PrimaryKey(autoGenerate = true)
    public int memoryId;

    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "frequency")
    public String frequency; // in format "xxx.xxxx"

    @ColumnInfo(name = "offset")
    public int offset; // 0 = none, 1 = down, 2 = up

    @ColumnInfo(name = "tx_tone")
    public String txTone; // Float as a string (e.g. "82.5"), or "None"

    @ColumnInfo(name = "group")
    public String group; // Optional

    @ColumnInfo(name = "rx_tone", defaultValue = "None")
    public String rxTone; // Optional. Float as a string (e.g. "82.5"), or "None"

    @ColumnInfo(name = "offset_khz", defaultValue = "600")
    public int offsetKhz; // Optional. If not specified, defaults to 600 (kHz).

    @ColumnInfo(name = "skip_during_scan", defaultValue = "0")
    public boolean skipDuringScan; // Optional. If not specified, treated as false.

    @Ignore
    private boolean highlighted = false;

    public void setHighlighted(boolean highlighted) {
        this.highlighted = highlighted;
    }

    public boolean isHighlighted() { return highlighted; }
}
