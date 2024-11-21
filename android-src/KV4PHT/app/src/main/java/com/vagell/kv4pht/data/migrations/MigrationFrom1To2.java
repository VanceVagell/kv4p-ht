package com.vagell.kv4pht.data.migrations;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public class MigrationFrom1To2 extends Migration {
    public MigrationFrom1To2() {
        super(1, 2);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS `aprs_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` INTEGER NOT NULL DEFAULT 0, " +
                "`from_callsign` TEXT, " +
                "`to_callsign` TEXT, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`position_lat` REAL NOT NULL, " +
                "`position_long` REAL NOT NULL, " +
                "`comment` TEXT, " +
                "`obj_name` TEXT, " +
                "`ack` INTEGER NOT NULL, " +
                "`message_num` INTEGER NOT NULL, " +
                "`msg_body` TEXT, " +
                "`temperature` REAL NOT NULL, " +
                "`humidity` REAL NOT NULL, " +
                "`pressure` REAL NOT NULL, " +
                "`rain` REAL NOT NULL, " +
                "`snow` REAL NOT NULL, " +
                "`wind_force` INTEGER NOT NULL, " +
                "`wind_dir` TEXT" +
                ")");
    }
}