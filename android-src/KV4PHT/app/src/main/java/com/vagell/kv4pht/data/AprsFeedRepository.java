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

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import io.github.dkaukov.aprs.AprsEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Android-owned materialized APRS feed query and its UI settings. */
public final class AprsFeedRepository {
    private final AprsEventDao dao;
    private final Executor executor;
    private final Supplier<String> callsignSupplier;
    private final MutableLiveData<List<AprsFeedRow>> feed = new MutableLiveData<>();
    private volatile String historyWindow = AprsFeedPolicy.HISTORY_ALL;
    private volatile String destinationFilter = AprsFeedPolicy.DESTINATION_ALL;

    public AprsFeedRepository(AprsEventDao dao, Executor executor,
                              Supplier<String> callsignSupplier) {
        this.dao = dao;
        this.executor = executor;
        this.callsignSupplier = callsignSupplier;
        refresh();
    }

    public LiveData<List<AprsFeedRow>> getFeed() {
        return feed;
    }

    public void setHistoryWindow(String value) {
        historyWindow = AprsFeedPolicy.normalizeHistoryWindow(value);
        refresh();
    }

    public void setDestinationFilter(String value) {
        destinationFilter = AprsFeedPolicy.normalizeDestinationFilter(value);
        refresh();
    }

    /** Reloads the Android-only UI projection after a repository mutation. */
    public void refresh() {
        long sinceMs = AprsFeedPolicy.historyStartMs(historyWindow, System.currentTimeMillis());
        boolean mineOnly = AprsFeedPolicy.DESTINATION_MINE.equals(destinationFilter);
        String callsign = AprsFeedPolicy.normalizeCallsign(callsignSupplier.get());
        try {
            executor.execute(() -> {
                List<RoomAprsFeedRow> rows = mineOnly
                    ? dao.getMineFeedSince(sinceMs, AprsEvent.MESSAGE_TYPE, callsign,
                        AprsFeedPolicy.MAX_VISIBLE_EVENTS)
                    : dao.getFeedSince(sinceMs, AprsFeedPolicy.MAX_VISIBLE_EVENTS);
                List<AprsFeedRow> mappedRows = new ArrayList<>();
                for (RoomAprsFeedRow row : rows) mappedRows.add(AprsFeedRow.fromRoom(row));
                feed.postValue(mappedRows);
            });
        } catch (RejectedExecutionException ignored) {
            // Service shutdown must not turn a committed database write into a reported failure.
        }
    }
}
