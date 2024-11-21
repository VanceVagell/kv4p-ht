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

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;

import com.vagell.kv4pht.data.migrations.MigrationFrom1To2;

@Database(
        version = 2,
        entities = {AppSetting.class, ChannelMemory.class, APRSMessage.class})
public abstract class AppDatabase extends RoomDatabase {
    public abstract AppSettingDao appSettingDao();
    public abstract ChannelMemoryDao channelMemoryDao();
    public abstract APRSMessageDao aprsMessageDao();

    public static final Migration MIGRATION_1_2 = new MigrationFrom1To2();

    public static AppDatabase getInstance(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), AppDatabase.class, "kv4pht-db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build();
    }
}
