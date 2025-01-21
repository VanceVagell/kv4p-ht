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

public class MigrationFrom2To3 extends Migration {
    public MigrationFrom2To3() {
        super(2, 3);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        // 1) Rename old table to a temporary backup
        database.execSQL("ALTER TABLE channel_memories RENAME TO channel_memories_backup");

        // 2) Create the new table with the new schema
        database.execSQL("CREATE TABLE channel_memories ("
                + "memoryId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + "name TEXT, "
                + "frequency TEXT, "
                + "`offset` INTEGER NOT NULL, "
                + "tx_tone TEXT, "          // changed name from tone to tx_tone
                + "`group` TEXT, "
                + "rx_tone TEXT DEFAULT '0', "    // newly added
                + "offset_khz INTEGER NOT NULL DEFAULT 600," // newly added
                + "skip_during_scan INTEGER NOT NULL DEFAULT 0" // newly added
                + ")");

        // 3) Copy data from the backup, mapping old "tone" to new "tx_tone"
        database.execSQL("INSERT INTO channel_memories "
                + "(memoryId, name, frequency, `offset`, tx_tone, `group`) "
                + "SELECT memoryId, name, frequency, `offset`, tone, `group` "
                + "FROM channel_memories_backup");

        // 4) Drop the backup table
        database.execSQL("DROP TABLE channel_memories_backup");
    }
}