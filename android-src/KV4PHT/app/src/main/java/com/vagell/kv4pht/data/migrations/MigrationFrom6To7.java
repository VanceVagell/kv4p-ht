package com.vagell.kv4pht.data.migrations;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.room.migration.Migration;

public class MigrationFrom6To7 extends Migration {
    public MigrationFrom6To7() {
        super(6, 7);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN relay_callsign TEXT");
    }
}
