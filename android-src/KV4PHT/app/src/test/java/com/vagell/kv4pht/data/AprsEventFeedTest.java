/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/


package com.vagell.kv4pht.data;

import static org.junit.Assert.assertEquals;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Exercises the actual DAO projection policy independently of SQLite/device infrastructure. */
public class AprsEventFeedTest {
    @Test public void repeatedUpdatesKeepOneRowPerEvent() {
        FeedDao dao = new FeedDao();
        AprsEventEntity event = event(1, 100);
        dao.updateEventFeed(event);
        dao.updateEventFeed(event);
        dao.updateEventFeed(event(2, 200));
        dao.updateEventFeed(event);
        assertEquals(2, dao.feed.size());
        assertEquals(1, dao.getFeedItem("event:1").eventCount);
        assertEquals(100, dao.getFeedItem("event:1").sortTimeMs);
        assertEquals(2, dao.getFeedItem("event:2").eventId);
    }

    @Test public void everyEventTypeRemainsSeparateEvenWithSameSourceAndTimestamp() {
        FeedDao dao = new FeedDao();
        int[] types = {0, 1, 2, 3, 4, 5, 6};
        long id = 0;
        for (int type : types) {
            for (int copy = 0; copy < 2; copy++) {
                AprsEventEntity event = event(++id, 100);
                event.type = type;
                event.fromCallsign = "VK3ME";
                event.objectName = "TEST";
                dao.updateEventFeed(event);
            }
        }
        assertEquals(14, dao.feed.size());
        for (AprsFeedItem item : dao.feed.values()) {
            assertEquals(1, item.eventCount);
            assertEquals(100, item.sortTimeMs);
        }
    }

    private static AprsEventEntity event(long id, long time) {
        AprsEventEntity event = new AprsEventEntity();
        event.id = id;
        event.firstSeenMs = time;
        return event;
    }

    private static final class FeedDao implements AprsEventDao {
        private final Map<String, AprsFeedItem> feed = new HashMap<>();
        @Override public AprsFeedItem getFeedItem(String key) { return feed.get(key); }
        @Override public void upsertFeedItem(AprsFeedItem item) { feed.put(item.feedKey, item); }
        @Override public List<RoomAprsFeedRow> getFeedSince(long since, int limit) { throw unused(); }
        @Override public List<RoomAprsFeedRow> getMineFeedSince(long since, int type, String call, int limit) { throw unused(); }
        @Override public List<AprsEventEntity> getPendingReliableEvents(int pending) { throw unused(); }
        @Override public AprsEventEntity getPendingOutgoingEvent(String local, String remote, String id, int pending) { throw unused(); }
        @Override public AprsEventEntity getById(long id) { throw unused(); }
        @Override public AprsEventEntity getRecentByDedupKey(String key, long since) { throw unused(); }
        @Override public long insert(AprsEventEntity event) { throw unused(); }
        @Override public void update(AprsEventEntity event) { throw unused(); }
        private AssertionError unused() { return new AssertionError("Unexpected non-projection operation"); }
    }
}
