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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import io.github.dkaukov.aprs.AprsController;
import io.github.dkaukov.aprs.BeaconData;
import io.github.dkaukov.aprs.AprsEvent;
import io.github.dkaukov.aprs.AprsSource;
import io.github.dkaukov.aprs.parser.APRSPacket;
import io.github.dkaukov.aprs.parser.Parser;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.Test;

/** JVM adapter-contract tests; these do not replace on-device Room migration tests. */
public class RoomAprsRepositoryTest {
    @Test public void incomingStorageAndCallbacksFinishOnCallingWorkerBeforeReturn() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            APRSPacket packet = Parser.parse("VK3ABC>APRS::VK3ME    :hello{17");
            fixture.controller.handle(packet, AprsSource.RX_RF, 144390000L, packet.toAX25Frame());
            assertEquals(1, fixture.events.size());
            assertEquals(1, fixture.packets.size());
            assertEquals(1, fixture.notifications);
            AprsEvent first = fixture.repository.findById(1);
            assertEquals("hello", first.getBody());
            fixture.controller.handle(packet, AprsSource.RX_RF, 144390000L, packet.toAX25Frame());
            assertEquals(1, fixture.events.size());
            assertEquals(2, fixture.packets.size());
            assertEquals(1, fixture.notifications);
            assertTrue(fixture.rfSubmissions.isEmpty());
            fixture.controller.tick(1_000_999L);
            assertTrue(fixture.rfSubmissions.isEmpty());
            fixture.controller.tick(1_001_000L);
            assertEquals(2, fixture.rfSubmissions.size());
            assertEquals(Long.valueOf(144390000L), fixture.requestedFrequencies.get(0));
            assertEquals(2, fixture.repository.findById(1).getPacketCount());
            assertEquals(1, first.getPacketCount());
        });
    }

    @Test public void retriesLoadOnceAndAckReplacesStoredEventWithoutMutatingOldValue() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            long now = 1_000_000L;
            fixture.controller.tick(now);
            fixture.controller.tick(now + 500);
            assertEquals(1, fixture.pendingLoads);
            APRSPacket outgoing = Parser.parse("VK3ME>APKVPA::VK3ABC   :hello{17");
            fixture.controller.recordOutgoingMessage("VK3ME", "VK3ABC", "hello", "17",
                144390000L, outgoing, outgoing.toAX25Frame());
            AprsEvent pending = fixture.repository.findById(1);
            fixture.controller.tick(pending.getNextRetryAtMs());
            assertEquals(1, fixture.rfSubmissions.size());
            assertEquals(1, fixture.repository.findById(1).getTransmitAttempts());
            APRSPacket ack = Parser.parse("VK3ABC>APRS::VK3ME    :ack17");
            fixture.controller.handle(ack, AprsSource.RX_RF, 144390000L, ack.toAX25Frame());
            assertEquals(AprsEvent.DELIVERY_DELIVERED,
                fixture.repository.findById(1).getDeliveryState());
            assertEquals(AprsEvent.DELIVERY_PENDING, pending.getDeliveryState());
            fixture.controller.tick(now + 600_000);
            assertEquals(1, fixture.rfSubmissions.size());
            assertEquals(1, fixture.pendingLoads);
        });
    }

    @Test public void failedPacketWriteRollsBackNewEventAndDoesNotPublishFeedOrCallbacks() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            fixture.failPacketInsert = true;
            int refreshes = fixture.feedRefreshes.size();
            APRSPacket packet = Parser.parse("VK3ABC>APRS::VK3ME    :hello{17");
            assertThrows(IllegalStateException.class, () -> fixture.controller.handle(packet));
            assertTrue(fixture.events.isEmpty());
            assertTrue(fixture.packets.isEmpty());
            assertTrue(fixture.projection.isEmpty());
            assertEquals(refreshes, fixture.feedRefreshes.size());
            assertEquals(0, fixture.notifications);
            assertTrue(fixture.rfSubmissions.isEmpty());
        });
    }

    @Test public void failedProjectionRollsBackAckAndPacketWithoutPublishingFeed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            APRSPacket outgoing = Parser.parse("VK3ME>APKVPA::VK3ABC   :hello{17");
            fixture.controller.recordOutgoingMessage("VK3ME", "VK3ABC", "hello", "17",
                144390000L, outgoing, outgoing.toAX25Frame());
            int refreshes = fixture.feedRefreshes.size();
            fixture.failProjectionWrite = true;
            APRSPacket ack = Parser.parse("VK3ABC>APRS::VK3ME    :ack17");
            assertThrows(IllegalStateException.class, () -> fixture.controller.handle(ack));
            assertEquals(AprsEvent.DELIVERY_PENDING, fixture.repository.findById(1).getDeliveryState());
            assertEquals(1, fixture.packets.size());
            assertEquals(1, fixture.projection.size());
            assertEquals(refreshes, fixture.feedRefreshes.size());
        });
    }

    @Test public void retryUsesOriginalRfFrameAfterIdentityAndPathChange() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            APRSPacket outgoing = Parser.parse("VK3ME>APKVPA,WIDE1-1::VK3ABC   :hello{17");
            fixture.controller.recordOutgoingMessage("VK3ME", "VK3ABC", "hello", "17",
                144390000L, outgoing, outgoing.toAX25Frame());
            fixture.controller.setCallsign("VK3NEW");
            fixture.controller.setTxDestination("OTHER");
            fixture.controller.tick(fixture.repository.findById(1).getNextRetryAtMs());
            assertEquals(1, fixture.rfSubmissions.size());
            assertArrayEquals(outgoing.toAX25Frame(), fixture.rfSubmissions.get(0).toAX25Frame());
            assertEquals(Long.valueOf(144390000L), fixture.requestedFrequencies.get(0));
        });
    }

    @Test public void unrelatedIncomingMessageDoesNotNotifyLocalUser() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            fixture.controller.handle(Parser.parse("VK3ABC>APRS::VK3XYZ   :hello"));
            assertEquals(1, fixture.events.size());
            assertEquals(0, fixture.notifications);
            assertTrue(fixture.rfSubmissions.isEmpty());
        });
    }

    @Test public void controllerBuildsAndPersistsAcceptedAckWithConfiguredEnvelope() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            fixture.acceptRf = true;
            fixture.controller.setTxPath(java.util.Collections.singletonList(
                new io.github.dkaukov.aprs.parser.Digipeater("WIDE1-1")));
            APRSPacket incoming = Parser.parse("VK3ABC>APRS::VK3ME    :hello{17");
            fixture.controller.handle(incoming, AprsSource.RX_RF, 144390000L,
                incoming.toAX25Frame());
            fixture.controller.tick(1_001_000L);
            assertEquals(1, fixture.rfSubmissions.size());
            APRSPacket submitted = fixture.rfSubmissions.get(0);
            APRSPacket expected = Parser.parse("VK3ME>APKVPA,WIDE1-1::VK3ABC   :ack17");
            assertArrayEquals(expected.toAX25Frame(), submitted.toAX25Frame());
            assertEquals(2, fixture.packets.size());
            assertEquals(AprsSource.TX_RF, fixture.packets.get(1).source);
            assertEquals(Long.valueOf(1), fixture.packets.get(1).eventId);
            // The local ACK is associated transport history, not another incoming observation.
            assertEquals(1, fixture.repository.findById(1).getPacketCount());
        });
    }

    @Test public void internetTransmissionIsRecordedOnlyAfterSuccessfulSocketSubmission() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            fixture.acceptIs = true;
            fixture.controller.setIgateEnabled(true);
            APRSPacket incoming = Parser.parse("VK3ABC>APRS:>ready");
            fixture.controller.handle(incoming, AprsSource.RX_RF, 144390000L,
                incoming.toAX25Frame());
            assertEquals(1, fixture.packets.size());
            assertNotNull(fixture.isSuccess);
            fixture.isSuccess.run();
            assertEquals(2, fixture.packets.size());
            assertEquals(AprsSource.TX_APRS_IS, fixture.packets.get(1).source);
        });
    }

    @Test public void appScheduledBeaconIsRecordedOnceOnlyAfterTransportAcceptance() throws Exception {
        Fixture fixture = new Fixture();
        fixture.run(() -> {
            BeaconData beacon = BeaconData.builder().latitude(-37.75).longitude(145.125)
                .symbolTable('/').symbolCode('$').messagingCapable(true).compressed(true).build();
            assertFalse(fixture.controller.submitPositionBeacon(beacon));
            assertTrue(fixture.events.isEmpty());
            assertTrue(fixture.packets.isEmpty());
            fixture.acceptRf = true;
            assertTrue(fixture.controller.submitPositionBeacon(beacon));
            assertEquals(1, fixture.events.size());
            assertEquals(1, fixture.packets.size());
            AprsEvent event = fixture.repository.findById(1);
            assertEquals(AprsEvent.POSITION_TYPE, event.getType());
            assertEquals(-37.75, event.getPositionLat(), 0.00001);
            assertEquals(145.125, event.getPositionLong(), 0.00001);
            assertEquals("VK3ME", event.getFromCallsign());
            assertEquals(AprsSource.TX_RF, fixture.packets.get(0).source);
            assertEquals("APKVPA", fixture.packets.get(0).ax25Destination);
            String payload = new String(fixture.rfSubmissions.get(1).getPayload().getRawBytes(),
                java.nio.charset.StandardCharsets.US_ASCII);
            assertEquals("=" + new io.github.dkaukov.aprs.parser.Position(
                -37.75, 145.125, 0, '/', '$').toCompressedString(), payload);
            assertEquals(null, fixture.requestedFrequencies.get(1));
            fixture.controller.setCallsign("");
            assertFalse(fixture.controller.submitPositionBeacon(beacon));
            assertEquals(1, fixture.events.size());
            assertEquals(2, fixture.rfSubmissions.size());
        });
    }

    private interface Work { void run() throws Exception; }

    private static final class Fixture implements AprsController.Callbacks {
        final Map<Long, AprsEventEntity> events = new LinkedHashMap<>();
        final List<AprsPacketEntity> packets = new ArrayList<>();
        final Map<String, AprsFeedItem> projection = new LinkedHashMap<>();
        final List<APRSPacket> rfSubmissions = new ArrayList<>();
        final List<Long> requestedFrequencies = new ArrayList<>();
        boolean inTransaction;
        boolean failPacketInsert;
        boolean failProjectionWrite;
        boolean acceptRf;
        boolean acceptIs;
        Runnable isSuccess;
        final List<Runnable> feedRefreshes = new ArrayList<>();
        final RoomAprsRepository repository;
        final AprsController controller;
        Thread worker;
        int pendingLoads;
        int notifications;

        Fixture() {
            AprsEventDao eventDao = (AprsEventDao) Proxy.newProxyInstance(
                AprsEventDao.class.getClassLoader(), new Class<?>[] {AprsEventDao.class},
                (proxy, method, args) -> {
                    assertSame(worker, Thread.currentThread());
                    switch (method.getName()) {
                        case "insert":
                            AprsEventEntity inserted = (AprsEventEntity) args[0];
                            inserted.id = events.size() + 1L;
                            events.put(inserted.id, inserted);
                            return inserted.id;
                        case "updateEventFeed":
                            assertTrue(inTransaction);
                            AprsEventEntity projected = (AprsEventEntity) args[0];
                            String key = args[1] == null ? "event:" + projected.id : (String) args[1];
                            projection.put(key, new AprsFeedItem(key, projected.id, projected.firstSeenMs, 1));
                            if (failProjectionWrite) throw new IllegalStateException("projection failed");
                            return null;
                        case "update":
                            AprsEventEntity updated = (AprsEventEntity) args[0];
                            assertTrue(events.containsKey(updated.id));
                            events.put(updated.id, updated);
                            return null;
                        case "getById": return events.get((Long) args[0]);
                        case "getPendingReliableEvents":
                            pendingLoads++;
                            List<AprsEventEntity> pending = new ArrayList<>();
                            for (AprsEventEntity e : events.values()) {
                                if (e.deliveryState == (int) args[0] && e.nextRetryAtMs != null) pending.add(e);
                            }
                            return pending;
                        case "getRecentByDedupKey":
                            for (AprsEventEntity e : events.values()) {
                                if (args[0].equals(e.dedupKey) && e.lastSeenMs >= (long) args[1]) return e;
                            }
                            return null;
                        case "getPendingOutgoingEvent":
                            for (AprsEventEntity e : events.values()) {
                                if (args[0].equals(e.fromCallsign) && args[1].equals(e.toCallsign)
                                        && args[2].equals(e.messageIdentifier)
                                        && e.deliveryState == (int) args[3]) return e;
                            }
                            return null;
                        default: throw new AssertionError("Unexpected DAO call: " + method.getName());
                    }
                });
            AprsPacketDao packetDao = (AprsPacketDao) Proxy.newProxyInstance(
                AprsPacketDao.class.getClassLoader(), new Class<?>[] {AprsPacketDao.class},
                (proxy, method, args) -> {
                    assertSame(worker, Thread.currentThread());
                    if ("getInitialRfTransmission".equals(method.getName())) {
                        for (AprsPacketEntity packet : packets) {
                            if (args[0].equals(packet.eventId) && AprsSource.TX_RF.equals(packet.source)) return packet;
                        }
                        return null;
                    }
                    assertEquals("insert", method.getName());
                    if (failPacketInsert) throw new IllegalStateException("packet failed");
                    packets.add((AprsPacketEntity) args[0]);
                    return (long) packets.size();
                });
            AprsFeedRepository feed = new AprsFeedRepository(eventDao, task -> {
                assertFalse(inTransaction);
                feedRefreshes.add(task);
            }, () -> "VK3ME");
            repository = new RoomAprsRepository(eventDao, packetDao, feed,
                new RoomAprsRepository.TransactionRunner() {
                    @Override public <T> T run(Supplier<T> operation) {
                        Map<Long, AprsEventEntity> savedEvents = new LinkedHashMap<>(events);
                        List<AprsPacketEntity> savedPackets = new ArrayList<>(packets);
                        Map<String, AprsFeedItem> savedProjection = new LinkedHashMap<>(projection);
                        inTransaction = true;
                        try {
                            return operation.get();
                        } catch (RuntimeException | Error failure) {
                            events.clear(); events.putAll(savedEvents);
                            packets.clear(); packets.addAll(savedPackets);
                            projection.clear(); projection.putAll(savedProjection);
                            throw failure;
                        } finally {
                            inTransaction = false;
                        }
                    }
                });
            controller = new AprsController(repository, this,
                Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC));
            controller.setCallsign("VK3ME");
            controller.setTxDestination("APKVPA");
        }

        void run(Work work) throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    worker = Thread.currentThread();
                    work.run();
                    return null;
                }).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }

        @Override public void onIncomingMessage(AprsEvent event, boolean forLocal) {
            assertSame(worker, Thread.currentThread());
            assertFalse(inTransaction);
            assertNotNull(repository.findById(event.getId()));
            if (forLocal) notifications++;
        }
        @Override public AprsController.RfTransmission submitRf(APRSPacket packet, Long frequencyHz) {
            assertSame(worker, Thread.currentThread());
            assertFalse(inTransaction);
            rfSubmissions.add(packet.copy());
            requestedFrequencies.add(frequencyHz);
            AprsController.Transmission transmission = acceptRf
                ? AprsController.Transmission.builder().packet(packet).frequencyHz(144390000L)
                    .rawAx25(packet.toAX25Frame()).build() : null;
            return AprsController.RfTransmission.builder().transmission(transmission).build();
        }
        @Override public BeaconData getBeaconData() { fail("Unexpected beacon"); return null; }
        @Override public boolean submitAprsIs(String tnc2, Runnable onSuccess) {
            assertSame(worker, Thread.currentThread());
            assertFalse(inTransaction);
            if (acceptIs) isSuccess = onSuccess;
            return acceptIs;
        }
    }
}
