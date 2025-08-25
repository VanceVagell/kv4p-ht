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

import com.vagell.kv4pht.data.migrations.*;

/**
 * Singleton Room database for kv4p HT application.
 */
@Database(
    version = 6,
    entities = {AppSetting.class, ChannelMemory.class, APRSMessage.class}
)
@SuppressWarnings("java:S6548")
public abstract class AppDatabase extends RoomDatabase {

    public abstract AppSettingDao appSettingDao();
    public abstract ChannelMemoryDao channelMemoryDao();
    public abstract APRSMessageDao aprsMessageDao();

    // Migrations
    public static final Migration MIGRATION_1_2 = new MigrationFrom1To2();
    public static final Migration MIGRATION_2_3 = new MigrationFrom2To3();
    public static final Migration MIGRATION_3_4 = new MigrationFrom3To4();
    public static final Migration MIGRATION_4_5 = new MigrationFrom4To5();
    public static final Migration MIGRATION_5_6 = new MigrationFrom5To6();

    @SuppressWarnings({"java:S3077", "java:S3008"})
    private static volatile AppDatabase INSTANCE;

    /**
     * Returns the singleton instance of the database.
     * Thread-safe and scoped to the application context.
     */
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = buildDatabase(context);
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Internal builder for the Room database.
     * Add or remove `fallbackToDestructiveMigration()` depending on build type or app policy.
     */
    private static AppDatabase buildDatabase(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, "kv4pht-db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6
            )
            // WARNING: This will delete all user data if migration is missing.
            // Remove or guard this call in production.
            .fallbackToDestructiveMigration(true)
            .build();
    }

    public void saveAppSetting(String key, String value) {
        AppSettingDao dao = appSettingDao();
        AppSetting setting = dao.getByName(key);
        if (setting == null) {
            setting = new AppSetting(key, value);
            dao.insertAll(setting);
        } else {
            setting.value = value;
            dao.update(setting);
        }
    }
}
