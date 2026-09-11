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
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN frequency TEXT");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN source TEXT NOT NULL DEFAULT 'UNKNOWN'");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN ax25_destination TEXT");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN path TEXT");
        database.execSQL("ALTER TABLE aprs_messages ADD COLUMN raw_ax25 BLOB");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_messages_delivery_state_next_retry_at "
            + "ON aprs_messages (delivery_state, next_retry_at)");
    }
}
