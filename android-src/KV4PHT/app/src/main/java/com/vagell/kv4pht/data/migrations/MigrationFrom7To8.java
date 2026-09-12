package com.vagell.kv4pht.data.migrations;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Splits legacy APRS events from the physical packet history introduced in version 8. */
public class MigrationFrom7To8 extends Migration {
    public MigrationFrom7To8() {
        super(7, 8);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE aprs_messages RENAME TO legacy_aprs_messages");
        database.execSQL("DROP TABLE IF EXISTS aprs_packets");
        database.execSQL("CREATE TABLE IF NOT EXISTS aprs_packets ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event_id INTEGER, "
            + "timestamp_ms INTEGER NOT NULL, source TEXT DEFAULT 'UNKNOWN', frequency_hz INTEGER, "
            + "from_callsign TEXT, ax25_destination TEXT, path TEXT, raw_ax25 BLOB)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_packets_event_id "
            + "ON aprs_packets (event_id)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_packets_timestamp_ms "
            + "ON aprs_packets (timestamp_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_packets_from_callsign "
            + "ON aprs_packets (from_callsign)");

        database.execSQL("CREATE TABLE IF NOT EXISTS aprs_events ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type INTEGER NOT NULL DEFAULT 0, "
            + "first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, "
            + "packet_count INTEGER NOT NULL DEFAULT 0, dedup_key TEXT, from_callsign TEXT, "
            + "to_callsign TEXT, message_identifier TEXT, body TEXT, position_lat REAL NOT NULL, "
            + "position_long REAL NOT NULL, comment TEXT, object_name TEXT, temperature REAL NOT NULL, "
            + "humidity REAL NOT NULL, pressure REAL NOT NULL, rain REAL NOT NULL, snow REAL NOT NULL, "
            + "wind_force INTEGER NOT NULL, wind_direction TEXT, relay_callsign TEXT, "
            + "delivery_state INTEGER NOT NULL DEFAULT 0, "
            + "transmit_attempts INTEGER NOT NULL DEFAULT 0, next_retry_at_ms INTEGER)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_events_dedup_key_last_seen_ms "
            + "ON aprs_events (dedup_key, last_seen_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_events_last_seen_ms "
            + "ON aprs_events (last_seen_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS "
            + "index_aprs_events_type_to_callsign_last_seen_ms "
            + "ON aprs_events (type, to_callsign, last_seen_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_events_delivery_state_next_retry_at_ms "
            + "ON aprs_events (delivery_state, next_retry_at_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS "
            + "index_aprs_events_from_callsign_to_callsign_message_identifier "
            + "ON aprs_events (from_callsign, to_callsign, message_identifier)");

        // v7 retained parsed events but no physical packet bytes. Preserve every event while
        // leaving packet_count at zero to make that missing packet history explicit.
        database.execSQL("INSERT INTO aprs_events (id, type, first_seen_ms, last_seen_ms, "
            + "packet_count, dedup_key, from_callsign, to_callsign, message_identifier, body, "
            + "position_lat, position_long, comment, object_name, temperature, humidity, pressure, "
            + "rain, snow, wind_force, wind_direction, relay_callsign, delivery_state, "
            + "transmit_attempts, next_retry_at_ms) SELECT id, type, timestamp * 1000, "
            + "timestamp * 1000, 0, NULL, from_callsign, to_callsign, "
            + "CASE WHEN message_num >= 0 THEN CAST(message_num AS TEXT) ELSE NULL END, msg_body, "
            + "position_lat, position_long, comment, obj_name, temperature, humidity, pressure, "
            + "rain, snow, wind_force, wind_dir, relay_callsign, "
            + "CASE WHEN ack != 0 THEN 2 ELSE 0 END, 0, NULL FROM legacy_aprs_messages");
        database.execSQL("DROP TABLE legacy_aprs_messages");
    }
}
