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

import android.database.Cursor;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import io.github.dkaukov.aprs.parser.StationCapabilitiesField;

/** Splits legacy APRS events from the physical packet history introduced in version 8. */
public class MigrationFrom7To8 extends Migration {
    public MigrationFrom7To8() {
        super(7, 8);
    }

    @Override
    public void migrate(SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE aprs_messages RENAME TO legacy_aprs_messages");
        normalizeLegacyCapabilityRows(database);
        database.execSQL("DROP TABLE IF EXISTS aprs_packets");
        database.execSQL("CREATE TABLE IF NOT EXISTS aprs_packets ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event_id INTEGER, "
            + "timestamp_ms INTEGER NOT NULL, source TEXT DEFAULT 'UNKNOWN', frequency_hz INTEGER, "
            + "from_callsign TEXT, ax25_destination TEXT, path TEXT, raw_ax25 BLOB, raw_tnc2 TEXT)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_packets_event_id "
            + "ON aprs_packets (event_id)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_packets_timestamp_ms "
            + "ON aprs_packets (timestamp_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_packets_from_callsign "
            + "ON aprs_packets (from_callsign)");

        database.execSQL("CREATE TABLE IF NOT EXISTS aprs_events ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type INTEGER NOT NULL DEFAULT 0, "
            + "first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, "
            + "packet_count INTEGER NOT NULL DEFAULT 0, digipeated INTEGER NOT NULL DEFAULT 0, "
            + "internet_only INTEGER NOT NULL DEFAULT 0, dedup_key TEXT, from_callsign TEXT, "
            + "to_callsign TEXT, message_identifier TEXT, body TEXT, position_lat REAL NOT NULL, "
            + "position_long REAL NOT NULL, comment TEXT, object_name TEXT, temperature REAL NOT NULL, "
            + "humidity REAL NOT NULL, pressure REAL NOT NULL, rain REAL NOT NULL, snow REAL NOT NULL, "
            + "wind_force INTEGER NOT NULL, wind_direction TEXT, relay_callsign TEXT, "
            + "delivery_state INTEGER NOT NULL DEFAULT 0, "
            + "transmit_attempts INTEGER NOT NULL DEFAULT 0, next_retry_at_ms INTEGER)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_events_dedup_key_last_seen_ms "
            + "ON aprs_events (dedup_key, last_seen_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_events_first_seen_ms "
            + "ON aprs_events (first_seen_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_events_internet_only_first_seen_ms "
            + "ON aprs_events (internet_only, first_seen_ms)");
        database.execSQL("CREATE INDEX IF NOT EXISTS "
            + "index_aprs_events_type_to_callsign_first_seen_ms "
            + "ON aprs_events (type, to_callsign, first_seen_ms)");
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
            + "transmit_attempts, next_retry_at_ms) SELECT id, "
            + "CASE WHEN type = 0 AND comment LIKE 'Raw: >%' THEN 5 "
            + "WHEN type = 0 AND comment LIKE 'Raw: <%' THEN 6 ELSE type END, "
            + "timestamp * 1000, "
            + "timestamp * 1000, 0, NULL, from_callsign, to_callsign, "
            + "CASE WHEN message_num >= 0 THEN CAST(message_num AS TEXT) ELSE NULL END, msg_body, "
            + "position_lat, position_long, "
            + "CASE WHEN type = 0 AND comment "
            + "GLOB 'Raw: >[0-9][0-9][0-9][0-9][0-9][0-9][zZ]*' "
            + "THEN SUBSTR(comment, 14) "
            + "WHEN type = 0 AND comment LIKE 'Raw: >%' THEN SUBSTR(comment, 7) "
            + "ELSE comment END, obj_name, temperature, humidity, pressure, "
            + "rain, snow, wind_force, wind_dir, relay_callsign, "
            + "CASE WHEN ack != 0 THEN 2 ELSE 0 END, 0, NULL FROM legacy_aprs_messages");

        database.execSQL("CREATE TABLE IF NOT EXISTS aprs_feed ("
            + "feed_key TEXT NOT NULL, event_id INTEGER NOT NULL, sort_time_ms INTEGER NOT NULL, "
            + "event_count INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(feed_key), "
            + "FOREIGN KEY(event_id) REFERENCES aprs_events(id) "
            + "ON UPDATE NO ACTION ON DELETE CASCADE)");
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_aprs_feed_event_id "
            + "ON aprs_feed (event_id)");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_aprs_feed_sort_time_ms "
            + "ON aprs_feed (sort_time_ms)");

        // Preserve every retained logical event as an individual feed row.
        database.execSQL("INSERT INTO aprs_feed (feed_key, event_id, sort_time_ms, event_count) "
            + "SELECT 'event:' || id, id, first_seen_ms, 1 FROM aprs_events");
        database.execSQL("DROP TABLE legacy_aprs_messages");
    }

    /** Converts legacy raw capability rows before copying them into the v8 event/feed model. */
    private void normalizeLegacyCapabilityRows(SupportSQLiteDatabase database) {
        try (Cursor cursor = database.query("SELECT id, comment FROM legacy_aprs_messages "
                + "WHERE type = 0 AND comment LIKE 'Raw: <%'")) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String comment = cursor.getString(1);
                String capabilityText = comment == null ? "" : comment.substring("Raw: <".length());
                database.execSQL("UPDATE legacy_aprs_messages SET type = 6, comment = ? "
                        + "WHERE id = ?", new Object[] {
                            StationCapabilitiesField.formatDisplayText(capabilityText), id});
            }
        }
    }
}
