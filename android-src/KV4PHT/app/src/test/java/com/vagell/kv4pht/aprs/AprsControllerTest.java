package com.vagell.kv4pht.aprs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.Digipeater;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.Parser;
import com.vagell.kv4pht.data.AprsEvent;
import com.vagell.kv4pht.data.AprsPacket;
import com.vagell.kv4pht.data.AprsSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class AprsControllerTest {
    @Rule public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Test public void incomingMessageCreatesOneEventAndLinkedPacket() {
        Fixture f = fixture();
        APRSPacket frame = directMessage("VK3ABC", "VK3ME", "hello", "A7");
        byte[] raw = frame.toAX25Frame();

        f.controller.handle(frame, AprsSource.RX_RF, 145_175_000L, raw);

        assertEquals(1, f.events.records.size());
        assertEquals(1, f.packets.records.size());
        AprsEvent event = f.events.records.get(0);
        AprsPacket packet = f.packets.records.get(0);
        assertEquals(AprsEvent.MESSAGE_TYPE, event.type);
        assertEquals("A7", event.messageIdentifier);
        assertEquals("hello", event.body);
        assertEquals(1, event.packetCount);
        assertEquals(Long.valueOf(event.id), packet.eventId);
        assertEquals(AprsSource.RX_RF, packet.source);
        assertEquals(Long.valueOf(145_175_000L), packet.frequencyHz);
        assertEquals("APRS", packet.ax25Destination);
        assertNull(packet.path);
        assertArrayEquals(raw, packet.rawAx25);
    }

    @Test public void duplicateMessageCreatesPacketsButOnlyOneEventAndNotification() {
        Fixture f = fixture();
        APRSPacket frame = directMessage("VK3ABC", "VK3ME", "hello", "A7");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(2, f.packets.records.size());
        assertEquals(1, f.events.records.size());
        assertEquals(2, f.events.records.get(0).packetCount);
        assertEquals(1, f.callbacks.notificationCount);
        assertEquals(2, f.callbacks.acknowledgementCount);
    }

    @Test public void copiesOfPositionViaDifferentPathsCollapseIntoOneEvent() throws Exception {
        Fixture f = fixture();
        APRSPacket direct = Parser.parse("VK3ABC>APRS,WIDE1-1:!3751.65S/14458.20E-Test");
        APRSPacket relayed = Parser.parse("VK3ABC>APRS,VK3DIG*:!3751.65S/14458.20E-Test");

        f.controller.handle(direct, AprsSource.RX_RF, 144_390_000L, direct.toAX25Frame());
        f.controller.handle(relayed, AprsSource.RX_RF, 144_390_000L, relayed.toAX25Frame());

        assertEquals(2, f.packets.records.size());
        assertEquals(1, f.events.records.size());
        assertEquals(AprsEvent.POSITION_TYPE, f.events.records.get(0).type);
        assertEquals(2, f.events.records.get(0).packetCount);
    }

    @Test public void weatherAndObjectEachCreateEvents() throws Exception {
        Fixture f = fixture();
        APRSPacket weather = Parser.parse("VK3WX>APRS:_10000000c090s010g015t070h50b10130");
        APRSPacket object = Parser.parse("VK3ABC>APRS:;TESTOBJ  *111111z3751.65S/14458.20E-Test");

        f.controller.handle(weather, AprsSource.RX_RF, 144_390_000L, weather.toAX25Frame());
        f.controller.handle(object, AprsSource.RX_RF, 144_390_000L, object.toAX25Frame());

        assertEquals(2, f.events.records.size());
        assertEquals(AprsEvent.WEATHER_TYPE, f.events.records.get(0).type);
        assertEquals(AprsEvent.OBJECT_TYPE, f.events.records.get(1).type);
    }

    @Test public void unsupportedPacketIsStoredWithoutEvent() {
        Fixture f = fixture();
        APRSPacket frame = packetWithPath("WIDE2-1");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(1, f.packets.records.size());
        assertNull(f.packets.records.get(0).eventId);
        assertTrue(f.events.records.isEmpty());
    }

    @Test public void thirdPartyPreservesOuterPacketAndInnerEvent() throws Exception {
        Fixture f = fixture();
        APRSPacket outer = Parser.parse(
            "RELAY1>APKVPA,WIDE1-1:}VK3ABC>APRS::VK3ME    :hello{7");
        byte[] raw = outer.toAX25Frame();

        f.controller.handle(outer, AprsSource.RX_RF, 145_175_000L, raw);

        AprsPacket packet = f.packets.records.get(0);
        AprsEvent event = f.events.records.get(0);
        assertEquals("RELAY1", packet.fromCallsign);
        assertEquals("APKVPA", packet.ax25Destination);
        assertEquals("WIDE1-1", packet.path);
        assertArrayEquals(raw, packet.rawAx25);
        assertEquals("VK3ABC", event.fromCallsign);
        assertEquals("RELAY1", event.relayCallsign);
        assertEquals("VK3ME", event.toCallsign);
        assertEquals("hello", event.body);
    }

    @Test public void physicalPacketPathPreservesUsedMarker() {
        Fixture f = fixture();
        Digipeater used = new Digipeater("VK3DIG");
        used.setUsed(true);
        APRSPacket frame = new APRSPacket("VK3ABC", "APRS", Collections.singletonList(used),
            MessagePacket.createMessagePayload("VK3ME", "hello", "7"));

        f.controller.handle(frame, AprsSource.RX_RF, 145_175_000L, frame.toAX25Frame());

        assertEquals("VK3DIG*", f.packets.records.get(0).path);
    }

    @Test public void outgoingChatCreatesEventAndPacket() {
        Fixture f = fixture();
        APRSPacket frame = outgoingMessage("VK3ME", "VK3ABC", "hello", "7");

        f.controller.recordOutgoingMessage("VK3ME", "VK3ABC", "hello", "7",
            144_390_000L, frame, frame.toAX25Frame());

        AprsEvent event = f.events.records.get(0);
        AprsPacket packet = f.packets.records.get(0);
        assertEquals(AprsEvent.DELIVERY_PENDING, event.deliveryState);
        assertEquals(1, event.transmitAttempts);
        assertEquals(1, event.packetCount);
        assertEquals(Long.valueOf(event.id), packet.eventId);
        assertEquals(AprsSource.TX_RF, packet.source);
    }

    @Test public void outgoingPositionCreatesEventAndPacket() {
        Fixture f = fixture();
        APRSPacket frame = new APRSPacket("VK3ME",
            Collections.singletonList(new Digipeater("WIDE1-1")),
            "!3751.65S/14458.20E-Test".getBytes(StandardCharsets.US_ASCII));

        f.controller.recordPositionBeacon("VK3ME", -37.8608, 144.9700,
            144_390_000L, frame, frame.toAX25Frame());

        assertEquals(1, f.events.records.size());
        assertEquals(AprsEvent.POSITION_TYPE, f.events.records.get(0).type);
        assertEquals(1, f.packets.records.size());
    }

    @Test public void acknowledgementLinksPacketAndUpdatesOutgoingEvent() {
        Fixture f = fixture();
        AprsEvent pending = pendingEvent("VK3ABC", "7", 0L, 1);
        pending.id = 1;
        pending.packetCount = 1;
        f.events.records.add(pending);

        f.controller.handle(deliveryResponse("VK3ABC", "ack7"), AprsSource.RX_RF,
            144_390_000L, null);

        assertEquals(AprsEvent.DELIVERY_DELIVERED, pending.deliveryState);
        assertNull(pending.nextRetryAtMs);
        assertEquals(2, pending.packetCount);
        assertEquals(Long.valueOf(pending.id), f.packets.records.get(0).eventId);
    }

    @Test public void rejectionLinksPacketAndStopsRetries() {
        Fixture f = fixture();
        AprsEvent pending = pendingEvent("VK3ABC", "7", 0L, 1);
        pending.id = 1;
        f.events.records.add(pending);

        f.controller.handle(deliveryResponse("VK3ABC", "rej7"), AprsSource.RX_RF,
            144_390_000L, null);

        assertEquals(AprsEvent.DELIVERY_REJECTED, pending.deliveryState);
        assertNull(pending.nextRetryAtMs);
    }

    @Test public void successfulRetriesAddPacketsToSameEvent() {
        Fixture f = fixture();
        AprsEvent event = pendingEvent("VK3ABC", "7", 0L, 1);
        event.id = 1;
        event.packetCount = 1;
        f.events.records.add(event);

        f.controller.tick(0L);

        assertEquals(2, event.transmitAttempts);
        assertEquals(2, event.packetCount);
        assertEquals(1, f.packets.records.size());
        assertEquals(Long.valueOf(event.id), f.packets.records.get(0).eventId);
        assertEquals(Long.valueOf(30_000L), event.nextRetryAtMs);
    }

    @Test public void retrySequenceEndsAfterFinalGracePeriod() {
        Fixture f = fixture();
        AprsEvent event = pendingEvent("VK3ABC", "7", 0L, 1);
        event.id = 1;
        f.events.records.add(event);

        f.controller.tick(0L); assertRetry(event, 2, 30_000L);
        f.controller.tick(30_000L); assertRetry(event, 3, 90_000L);
        f.controller.tick(90_000L); assertRetry(event, 4, 210_000L);
        f.controller.tick(210_000L); assertRetry(event, 5, 450_000L);
        f.controller.tick(450_000L); assertRetry(event, 6, 480_000L);
        f.controller.tick(480_000L);

        assertEquals(AprsEvent.DELIVERY_FAILED, event.deliveryState);
        assertNull(event.nextRetryAtMs);
        assertEquals(5, f.callbacks.retryCount);
        assertEquals(5, f.packets.records.size());
    }

    @Test public void failedRfRetryAddsNoPacketOrAttempt() {
        Fixture f = fixture();
        f.callbacks.retrySucceeds = false;
        AprsEvent event = pendingEvent("VK3ABC", "7", 100L, 1);
        event.id = 1;
        f.events.records.add(event);

        f.controller.tick(100L);

        assertEquals(1, event.transmitAttempts);
        assertEquals(0, f.packets.records.size());
        assertEquals(Long.valueOf(15_100L), event.nextRetryAtMs);
    }

    @Test public void restartRetriesOnlyPersistedPendingEvents() {
        FakePacketRepository packets = new FakePacketRepository();
        FakeEventRepository events = new FakeEventRepository();
        AprsEvent pending = pendingEvent("VK3ABC", "7", 0L, 1);
        pending.id = 1;
        events.records.add(pending);
        events.records.add(terminalEvent(AprsEvent.DELIVERY_DELIVERED));
        events.records.add(terminalEvent(AprsEvent.DELIVERY_REJECTED));
        events.records.add(terminalEvent(AprsEvent.DELIVERY_FAILED));
        FakeCallbacks callbacks = new FakeCallbacks();

        new AprsController(packets, events, Runnable::run, callbacks).tick(0L);

        assertEquals(1, callbacks.retryCount);
        assertEquals(2, pending.transmitAttempts);
    }

    @Test public void bulletinIsFireAndForget() {
        Fixture f = fixture();
        APRSPacket frame = outgoingMessage("VK3ME", "BLN1CQ", "net starts", null);

        f.controller.recordOutgoingMessage("VK3ME", "BLN1CQ", "net starts", null,
            144_390_000L, frame, frame.toAX25Frame());
        f.controller.tick(Long.MAX_VALUE);

        AprsEvent event = f.events.records.get(0);
        assertEquals(AprsEvent.DELIVERY_NONE, event.deliveryState);
        assertNull(event.messageIdentifier);
        assertNull(event.nextRetryAtMs);
        assertEquals(0, f.callbacks.retryCount);
    }

    @Test public void acknowledgementTransmissionAddsPacketWithoutAnotherEvent() {
        Fixture f = fixture();
        AprsEvent event = new AprsEvent();
        event.id = 1;
        event.packetCount = 1;
        f.events.records.add(event);
        APRSPacket ack = outgoingMessage("VK3ME", "VK3ABC", "ack7", null);

        f.controller.recordTransmission(event.id, ack, 144_390_000L, ack.toAX25Frame());

        assertEquals(1, f.events.records.size());
        assertEquals(2, event.packetCount);
        assertEquals(Long.valueOf(event.id), f.packets.records.get(0).eventId);
    }

    @Test public void beaconCadenceUsesControllerTick() {
        Fixture f = fixture();
        f.controller.setPositionBeaconingEnabled(true, 1_000L);
        f.controller.tick(1_000L);
        f.controller.tick(1_001L);
        f.controller.tick(301_000L);
        assertEquals(2, f.callbacks.beaconCount);
    }

    @Test public void idleTickDoesNotReloadEventFeed() {
        Fixture f = fixture();
        int loadsAfterStartup = f.events.loadCount;
        f.controller.tick(1_000L);
        assertEquals(loadsAfterStartup, f.events.loadCount);
        assertEquals(1, f.events.dueLoadCount);
    }

    @Test public void finiteHistoryWindowRefreshesAsEventsAgeOut() {
        Fixture f = fixture();
        f.controller.setHistoryWindow(AprsController.HISTORY_ONE_DAY);
        int loadsAfterSelection = f.events.loadCount;

        f.controller.tick(Long.MAX_VALUE);

        assertEquals(loadsAfterSelection + 1, f.events.loadCount);
    }

    @Test public void everyHistoryWindowLimitsEventsByLastSeenTime() {
        Fixture f = fixture();
        long now = System.currentTimeMillis();
        long day = 24 * 60 * 60_000L;
        f.events.records.add(eventAt("today", now - day / 2));
        f.events.records.add(eventAt("this week", now - 2 * day));
        f.events.records.add(eventAt("this fortnight", now - 10 * day));
        f.events.records.add(eventAt("this month", now - 20 * day));
        f.events.records.add(eventAt("older", now - 40 * day));

        f.controller.setHistoryWindow(AprsController.HISTORY_ONE_DAY);
        assertEquals(1, f.controller.getEvents().getValue().size());
        f.controller.setHistoryWindow(AprsController.HISTORY_ONE_WEEK);
        assertEquals(2, f.controller.getEvents().getValue().size());
        f.controller.setHistoryWindow(AprsController.HISTORY_TWO_WEEKS);
        assertEquals(3, f.controller.getEvents().getValue().size());
        f.controller.setHistoryWindow(AprsController.HISTORY_ONE_MONTH);
        assertEquals(4, f.controller.getEvents().getValue().size());
        f.controller.setHistoryWindow(AprsController.HISTORY_ALL);
        assertEquals(5, f.controller.getEvents().getValue().size());
    }

    @Test public void mineFilterKeepsBroadcastsMessagesToMeAndNonMessageEvents() {
        Fixture f = fixture();
        long now = System.currentTimeMillis();
        f.events.records.add(messageEvent("VK3ME", "mine", now));
        f.events.records.add(messageEvent("VK3OTHER", "other", now));
        f.events.records.add(messageEvent("BLN1CQ", "bulletin", now));
        f.events.records.add(messageEvent("QST", "qst", now));
        f.events.records.add(messageEvent("ALL", "all", now));
        f.events.records.add(messageEvent("CQ", "cq", now));
        f.events.records.add(eventAt("position", now));

        f.controller.setDestinationFilter(AprsController.DESTINATION_MINE);

        List<AprsEvent> visible = f.controller.getEvents().getValue();
        assertEquals(6, visible.size());
        assertFalse(visible.stream().anyMatch(event -> "other".equals(event.body)));
    }

    @Test public void digipeatAddsTxPacketToSameEventAndSuppressesSecondTransmission() {
        Fixture f = fixture();
        f.controller.setDigipeatingEnabled(true);
        APRSPacket frame = directMessageWithPath("VK3ABC", "VK3ME", "hello", "7", "WIDE1-1");

        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());
        f.controller.handle(frame, AprsSource.RX_RF, 144_390_000L, frame.toAX25Frame());

        assertEquals(1, f.callbacks.digipeatCount);
        assertEquals(3, f.packets.records.size());
        assertEquals(1, f.events.records.size());
        assertEquals(3, f.events.records.get(0).packetCount);
    }

    @Test public void stationAddressRulesRemainCompatible() {
        assertTrue(AprsController.requiresAcknowledgement("VK3ABC-7"));
        assertFalse(AprsController.requiresAcknowledgement("BLN1CQ"));
        assertFalse(AprsController.requiresAcknowledgement("QST"));
        assertFalse(AprsController.requiresAcknowledgement("ALL"));
        assertFalse(AprsController.requiresAcknowledgement("CQ"));
        assertFalse(AprsController.requiresAcknowledgement(null));
    }

    private Fixture fixture() {
        FakePacketRepository packets = new FakePacketRepository();
        FakeEventRepository events = new FakeEventRepository();
        FakeCallbacks callbacks = new FakeCallbacks();
        return new Fixture(packets, events, callbacks,
            new AprsController(packets, events, Runnable::run, callbacks));
    }

    private APRSPacket directMessage(String from, String to, String body, String identifier) {
        return new APRSPacket(from, "APRS", Collections.emptyList(),
            MessagePacket.createMessagePayload(to, body, identifier));
    }

    private APRSPacket directMessageWithPath(String from, String to, String body,
                                              String identifier, String path) {
        return new APRSPacket(from, "APRS", Collections.singletonList(new Digipeater(path)),
            MessagePacket.createMessagePayload(to, body, identifier));
    }

    private APRSPacket outgoingMessage(String from, String to, String body, String identifier) {
        return new APRSPacket(from, Collections.singletonList(new Digipeater("WIDE1-1")),
            MessagePacket.createMessagePayload(to, body, identifier));
    }

    private APRSPacket deliveryResponse(String from, String response) {
        return directMessage(from, "VK3ME", response, null);
    }

    private APRSPacket packetWithPath(String path) {
        return new APRSPacket("VK3ABC", "APRS", Collections.singletonList(new Digipeater(path)),
            ">test".getBytes(StandardCharsets.US_ASCII));
    }

    private static AprsEvent pendingEvent(String destination, String identifier,
                                          long nextRetryAt, int attempts) {
        AprsEvent event = new AprsEvent();
        event.type = AprsEvent.MESSAGE_TYPE;
        event.fromCallsign = "VK3ME";
        event.toCallsign = destination;
        event.messageIdentifier = identifier;
        event.body = "hello";
        event.deliveryState = AprsEvent.DELIVERY_PENDING;
        event.nextRetryAtMs = nextRetryAt;
        event.transmitAttempts = attempts;
        return event;
    }

    private static AprsEvent eventAt(String comment, long lastSeenMs) {
        AprsEvent event = new AprsEvent();
        event.type = AprsEvent.POSITION_TYPE;
        event.firstSeenMs = lastSeenMs;
        event.lastSeenMs = lastSeenMs;
        event.comment = comment;
        return event;
    }

    private static AprsEvent messageEvent(String destination, String body, long lastSeenMs) {
        AprsEvent event = eventAt(null, lastSeenMs);
        event.type = AprsEvent.MESSAGE_TYPE;
        event.toCallsign = destination;
        event.body = body;
        return event;
    }

    private static AprsEvent terminalEvent(int state) {
        AprsEvent event = pendingEvent("VK3XYZ", "9", 0L, 1);
        event.deliveryState = state;
        return event;
    }

    private void assertRetry(AprsEvent event, int attempts, long nextRetryAt) {
        assertEquals(attempts, event.transmitAttempts);
        assertEquals(Long.valueOf(nextRetryAt), event.nextRetryAtMs);
    }

    private static final class Fixture {
        final FakePacketRepository packets;
        final FakeEventRepository events;
        final FakeCallbacks callbacks;
        final AprsController controller;

        Fixture(FakePacketRepository packets, FakeEventRepository events,
                FakeCallbacks callbacks, AprsController controller) {
            this.packets = packets;
            this.events = events;
            this.callbacks = callbacks;
            this.controller = controller;
        }
    }

    private static final class FakePacketRepository implements AprsController.PacketRepository {
        final List<AprsPacket> records = new ArrayList<>();

        @Override public long insert(AprsPacket packet) {
            packet.id = records.size() + 1L;
            records.add(packet);
            return packet.id;
        }
    }

    private static final class FakeEventRepository implements AprsController.EventRepository {
        final List<AprsEvent> records = new ArrayList<>();
        int loadCount;
        int dueLoadCount;

        @Override public List<AprsEvent> loadEvents(long sinceMs, String localCallsign,
                                                    boolean mineOnly) {
            loadCount++;
            List<AprsEvent> visible = new ArrayList<>();
            for (AprsEvent event : records) {
                if (event.lastSeenMs < sinceMs) continue;
                String destination = event.toCallsign;
                boolean broadcast = destination != null && (destination.startsWith("BLN")
                    || destination.equals("ALL") || destination.equals("QST")
                    || destination.equals("CQ"));
                if (!mineOnly || event.type != AprsEvent.MESSAGE_TYPE
                        || localCallsign.equals(destination) || broadcast) visible.add(event);
            }
            return visible;
        }

        @Override public List<AprsEvent> loadDueReliableEvents(long now) {
            dueLoadCount++;
            List<AprsEvent> due = new ArrayList<>();
            for (AprsEvent event : records) {
                if (event.deliveryState == AprsEvent.DELIVERY_PENDING
                        && event.nextRetryAtMs != null && event.nextRetryAtMs <= now) due.add(event);
            }
            return due;
        }

        @Override public long insert(AprsEvent event) {
            event.id = records.size() + 1L;
            records.add(event);
            return event.id;
        }

        @Override public void update(AprsEvent event) {
            // Mutable in-memory records already contain the update.
        }

        @Override public AprsEvent findById(long id) {
            for (AprsEvent event : records) {
                if (event.id == id) return event;
            }
            return null;
        }

        @Override public AprsEvent findRecentByDedupKey(String dedupKey, long sinceMs) {
            for (int i = records.size() - 1; i >= 0; i--) {
                AprsEvent event = records.get(i);
                if (dedupKey.equals(event.dedupKey) && event.lastSeenMs >= sinceMs) return event;
            }
            return null;
        }

        @Override public AprsEvent findPendingOutgoingEvent(String local, String remote,
                                                             String identifier) {
            for (int i = records.size() - 1; i >= 0; i--) {
                AprsEvent event = records.get(i);
                if (event.deliveryState == AprsEvent.DELIVERY_PENDING
                        && local.equals(event.fromCallsign) && remote.equals(event.toCallsign)
                        && identifier.equals(event.messageIdentifier)) return event;
            }
            return null;
        }
    }

    private static final class FakeCallbacks implements AprsController.Callbacks {
        int retryCount;
        int beaconCount;
        int digipeatCount;
        int notificationCount;
        int acknowledgementCount;
        boolean retrySucceeds = true;

        @Override public String getCallsign() {
            return "VK3ME";
        }

        @Override public void showNotification(String title, String message) {
            notificationCount++;
        }

        @Override public void sendAcknowledgement(String destination, String identifier,
                                                  long eventId) {
            acknowledgementCount++;
        }

        @Override public AprsController.Transmission retryMessage(AprsEvent event) {
            retryCount++;
            if (!retrySucceeds) return null;
            APRSPacket packet = new APRSPacket(event.fromCallsign,
                Collections.singletonList(new Digipeater("WIDE1-1")),
                MessagePacket.createMessagePayload(event.toCallsign, event.body,
                    event.messageIdentifier));
            return new AprsController.Transmission(packet, 144_390_000L, packet.toAX25Frame());
        }

        @Override public void requestPositionBeacon() {
            beaconCount++;
        }

        @Override public AprsController.Transmission transmitDigipeatedPacket(APRSPacket packet) {
            digipeatCount++;
            return new AprsController.Transmission(packet, 144_390_000L, packet.toAX25Frame());
        }
    }
}
