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

        // update frequencies in channel_memories from 'xxx.xxx' to 'xxx.xxxx' (ESP32 firmware v2+ requires this new format)
        database.execSQL("UPDATE channel_memories SET frequency = frequency || '0' WHERE length(frequency) = 7");
    }
}