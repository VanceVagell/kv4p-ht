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

public class MigrationFrom5To6 extends Migration {
    public MigrationFrom5To6() {
        super(5, 6);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        // We renamed and changed capitalization on a few setting values, adjust them (we did not change DB schema).
        database.execSQL("UPDATE app_settings SET value = 'Approx' WHERE name = 'aprsPositionAccuracy' AND value = 'approx'");
        database.execSQL("UPDATE app_settings SET value = 'Exact' WHERE name = 'aprsPositionAccuracy' AND value = 'exact'");
        database.execSQL("UPDATE app_settings SET value = '12.5kHz' WHERE name = 'bandwidth' AND value = 'Narrow'");
        database.execSQL("UPDATE app_settings SET value = '25kHz' WHERE name = 'bandwidth' AND value = 'Wide'");
    }
}