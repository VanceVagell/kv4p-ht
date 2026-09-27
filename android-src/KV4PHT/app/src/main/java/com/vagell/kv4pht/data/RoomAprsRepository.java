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

import io.github.dkaukov.aprs.AprsRepository;
import io.github.dkaukov.aprs.AprsPacket;
import io.github.dkaukov.aprs.AprsEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Room implementation of canonical event storage and its Android feed projection update. */
public final class RoomAprsRepository implements AprsRepository {
    private final AprsEventDao dao;
    private final AprsPacketDao packetDao;
    private final AprsFeedRepository feedRepository;
    private final TransactionRunner transactions;

    public RoomAprsRepository(AppDatabase database, AprsFeedRepository feedRepository) {
        this(database.aprsEventDao(), database.aprsPacketDao(), feedRepository,
            new TransactionRunner() {
                @Override public <T> T run(Supplier<T> operation) {
                    return database.runInTransaction(operation::get);
                }
            });
    }

    interface TransactionRunner {
        <T> T run(Supplier<T> operation);
    }

    RoomAprsRepository(AprsEventDao dao, AprsPacketDao packetDao,
                      AprsFeedRepository feedRepository, TransactionRunner transactions) {
        this.dao = dao;
        this.packetDao = packetDao;
        this.feedRepository = feedRepository;
        this.transactions = transactions;
    }

    @Override public <T> T inTransaction(Supplier<T> operation) {
        T result = transactions.run(operation);
        // No UI work or queued feed read is published for a rolled-back operation.
        feedRepository.refresh();
        return result;
    }

    @Override public void onEventPersisted(AprsEvent event) {
        dao.updateEventFeed(AprsPersistenceMapper.toEntity(event), AprsFeedPolicy.feedKey(event));
    }

    @Override public List<AprsEvent> loadPendingReliableEvents() {
        List<AprsEvent> events = new ArrayList<>();
        for (AprsEventEntity entity : dao.getPendingReliableEvents(AprsEvent.DELIVERY_PENDING)) {
            events.add(AprsPersistenceMapper.toDomain(entity));
        }
        return events;
    }

    @Override public long insert(AprsPacket packet) {
        return packetDao.insert(AprsPersistenceMapper.toEntity(packet));
    }

    @Override public long insert(AprsEvent event) {
        return dao.insert(AprsPersistenceMapper.toEntity(event));
    }

    @Override public void update(AprsEvent event) {
        dao.update(AprsPersistenceMapper.toEntity(event));
    }

    @Override public AprsEvent findById(long id) {
        return AprsPersistenceMapper.toDomain(dao.getById(id));
    }

    @Override public AprsPacket findInitialRfTransmission(long eventId) {
        return AprsPersistenceMapper.toDomain(packetDao.getInitialRfTransmission(eventId));
    }

    @Override public AprsEvent findRecentByDedupKey(String dedupKey, long sinceMs) {
        return AprsPersistenceMapper.toDomain(dao.getRecentByDedupKey(dedupKey, sinceMs));
    }

    @Override public AprsEvent findPendingOutgoingEvent(String localCallsign, String remoteCallsign,
                                                         String messageIdentifier) {
        return AprsPersistenceMapper.toDomain(dao.getPendingOutgoingEvent(localCallsign,
            remoteCallsign, messageIdentifier, AprsEvent.DELIVERY_PENDING));
    }
}
