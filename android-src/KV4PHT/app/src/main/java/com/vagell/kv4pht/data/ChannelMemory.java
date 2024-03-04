package com.vagell.kv4pht.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
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
    public String frequency; // in format "xxx.xxx"

    @ColumnInfo(name = "offset")
    public int offset; // 0 = none, 1 = down, 2 = up

    @ColumnInfo(name = "tone")
    public String tone; // Float as a string (e.g. "82.5"), where "0" = none

    @ColumnInfo(name = "group")
    public String group; // Optional
}
