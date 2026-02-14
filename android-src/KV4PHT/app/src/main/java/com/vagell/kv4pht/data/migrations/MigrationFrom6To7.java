/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2026 Vance Vagell

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

public class MigrationFrom6To7 extends Migration {
    public MigrationFrom6To7() {
        super(6, 7);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN decoder_source INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN packet_hash TEXT DEFAULT ''");
    }
}
