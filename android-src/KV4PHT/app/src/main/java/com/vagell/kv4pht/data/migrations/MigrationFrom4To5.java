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

public class MigrationFrom4To5 extends Migration {
    public MigrationFrom4To5() {
        super(4, 5);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        // Rename setting maxFreq to max2mTxFreq (we now have 70cm support)
        database.execSQL("UPDATE app_settings SET name = 'max2mTxFreq' WHERE name = 'maxFreq'");
    }
}