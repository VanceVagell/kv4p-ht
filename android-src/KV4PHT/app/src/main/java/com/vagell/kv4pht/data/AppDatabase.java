package com.vagell.kv4pht.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {AppSetting.class, ChannelMemory.class}, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    public abstract AppSettingDao appSettingDao();
    public abstract ChannelMemoryDao channelMemoryDao();
}
