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

package com.vagell.kv4pht.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;
import java.util.List;

@Dao
public interface AprsEventDao {
    @Query("SELECT * FROM (SELECT f.feed_key, f.sort_time_ms, f.event_count, e.* "
        + "FROM aprs_feed f INNER JOIN aprs_events e ON e.id = f.event_id "
        + "WHERE f.sort_time_ms >= :sinceMs "
        + "ORDER BY f.sort_time_ms DESC, e.id DESC LIMIT :limit) "
        + "ORDER BY sort_time_ms, id")
    List<RoomAprsFeedRow> getFeedSince(long sinceMs, int limit);

    @Query("SELECT * FROM (SELECT f.feed_key, f.sort_time_ms, f.event_count, e.* "
        + "FROM aprs_feed f INNER JOIN aprs_events e ON e.id = f.event_id "
        + "WHERE f.sort_time_ms >= :sinceMs AND (e.type != :messageType "
        + "OR e.from_callsign = :localCallsign OR e.to_callsign = :localCallsign "
        + "OR e.to_callsign IN ('ALL', 'QST', 'CQ') "
        + "OR (e.to_callsign >= 'BLN' AND e.to_callsign < 'BLO')) "
        + "ORDER BY f.sort_time_ms DESC, e.id DESC LIMIT :limit) "
        + "ORDER BY sort_time_ms, id")
    List<RoomAprsFeedRow> getMineFeedSince(long sinceMs, int messageType,
                                            String localCallsign, int limit);

    @Query("SELECT * FROM aprs_events WHERE delivery_state = :pendingState "
        + "AND next_retry_at_ms IS NOT NULL")
    List<AprsEventEntity> getPendingReliableEvents(int pendingState);

    @Query("SELECT * FROM aprs_events WHERE from_callsign = :localCallsign "
        + "AND to_callsign = :remoteCallsign AND message_identifier = :messageIdentifier "
        + "AND delivery_state = :pendingState ORDER BY id DESC LIMIT 1")
    AprsEventEntity getPendingOutgoingEvent(String localCallsign, String remoteCallsign,
                                            String messageIdentifier, int pendingState);

    @Query("SELECT * FROM aprs_events WHERE id = :id LIMIT 1")
    AprsEventEntity getById(long id);

    @Query("SELECT * FROM aprs_events WHERE dedup_key = :dedupKey "
        + "AND last_seen_ms >= :sinceMs ORDER BY last_seen_ms DESC LIMIT 1")
    AprsEventEntity getRecentByDedupKey(String dedupKey, long sinceMs);

    @Insert
    long insert(AprsEventEntity event);

    @Query("SELECT * FROM aprs_feed WHERE feed_key = :feedKey LIMIT 1")
    AprsFeedItem getFeedItem(String feedKey);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertFeedItem(AprsFeedItem item);

    /** Advances the feed projection inside the controller's event/packet transaction. */
    @Transaction
    default void updateEventFeed(AprsEventEntity event, String feedKey) {
        String resolvedKey = feedKey == null ? "event:" + event.id : feedKey;
        AprsFeedItem current = getFeedItem(resolvedKey);
        // Updating an older event must not replace the latest station/object row.
        if (current != null && current.eventId != event.id
                && (current.sortTimeMs > event.firstSeenMs
                    || (current.sortTimeMs == event.firstSeenMs && current.eventId > event.id))) return;
        int eventCount = current == null ? 1
            : current.eventCount + (current.eventId == event.id ? 0 : 1);
        upsertFeedItem(new AprsFeedItem(
            resolvedKey, event.id, event.firstSeenMs, eventCount));
    }

    @Update
    void update(AprsEventEntity event);
}
