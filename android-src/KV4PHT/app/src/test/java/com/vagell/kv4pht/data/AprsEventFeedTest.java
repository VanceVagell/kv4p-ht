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
    @Test public void duplicatesAndDeliveryUpdatesDoNotCountAsNewEvents() {
        FeedDao dao = new FeedDao();
        AprsEventEntity event = event(1, 100);
        dao.updateEventFeed(event, "position:VK3ME");
        dao.updateEventFeed(event, "position:VK3ME");
        assertEquals(1, dao.getFeedItem("position:VK3ME").eventCount);
        dao.updateEventFeed(event(2, 200), "position:VK3ME");
        dao.updateEventFeed(event(1, 100), "position:VK3ME");
        assertEquals(2, dao.getFeedItem("position:VK3ME").eventId);
        assertEquals(2, dao.getFeedItem("position:VK3ME").eventCount);
        assertEquals(200, dao.getFeedItem("position:VK3ME").sortTimeMs);
    }

    @Test public void tiedTimestampsKeepNewestIdAndMessagesHaveIndependentSlots() {
        FeedDao dao = new FeedDao();
        dao.updateEventFeed(event(2, 100), "position:VK3ME");
        dao.updateEventFeed(event(1, 100), "position:VK3ME");
        assertEquals(2, dao.getFeedItem("position:VK3ME").eventId);
        dao.updateEventFeed(event(3, 300), null);
        dao.updateEventFeed(event(4, 400), null);
        dao.updateEventFeed(event(3, 300), null);
        assertEquals(3, dao.getFeedItem("event:3").eventId);
        assertEquals(1, dao.getFeedItem("event:3").eventCount);
        assertEquals(4, dao.getFeedItem("event:4").eventId);
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
