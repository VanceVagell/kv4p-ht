package com.vagell.kv4pht.data.migrations;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds persistent reliable-APRS delivery state without changing existing message history. */
public class MigrationFrom7To8 extends Migration {
    public MigrationFrom7To8() {
        super(7, 8);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN message_identifier TEXT");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN delivery_state INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN retries_remaining INTEGER");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN transmit_attempts INTEGER NOT NULL DEFAULT 0");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN next_retry_at INTEGER");
    }
}
