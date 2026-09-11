package com.vagell.kv4pht.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.Digipeater;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.data.APRSMessage;
import org.junit.Test;
import org.junit.Rule;

import java.util.ArrayList;
import java.util.List;

public class AprsControllerTest {
    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Test
    public void recordsOutgoingMessagesThroughItsDao() {
        FakeDao dao = new FakeDao();
        AprsController controller = controller(dao);

        controller.recordOutgoingMessage("vk3abc", "vk3def", " hello ", 7);

        assertEquals(1, dao.messages.size());
        APRSMessage message = dao.messages.get(0);
        assertEquals("VK3ABC", message.fromCallsign);
        assertEquals("VK3DEF", message.toCallsign);
        assertEquals("hello", message.msgBody);
        assertEquals(7, message.msgNum);
    }

    @Test
    public void dropsDuplicateIncomingMessages() {
        FakeDao dao = new FakeDao();
        dao.duplicate = true;
        AprsController controller = controller(dao);
        APRSMessage message = new APRSMessage();
        message.type = APRSMessage.MESSAGE_TYPE;
        message.fromCallsign = "VK3ABC";
        message.msgBody = "test";
        message.msgNum = 4;

        controller.save(message);

        assertTrue(dao.messages.isEmpty());
    }

    @Test
    public void tickRetriesPendingMessageAtItsDeadline() {
        FakeDao dao = new FakeDao();
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(dao, callbacks);
        APRSMessage message = pendingMessage(100L, 5);
        dao.messages.add(message);

        controller.tick(100L);

        assertEquals(1, callbacks.retryCount);
        assertEquals(2, message.transmitAttempts);
        assertEquals(Integer.valueOf(4), message.retriesRemaining);
        assertEquals(Long.valueOf(30_100L), message.nextRetryAt);
    }

    @Test
    public void tickMarksMessageFailedAfterFinalAcknowledgementGrace() {
        FakeDao dao = new FakeDao();
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(dao, callbacks);
        APRSMessage message = pendingMessage(100L, 0);
        dao.messages.add(message);

        controller.tick(100L);

        assertEquals(0, callbacks.retryCount);
        assertEquals(APRSMessage.DELIVERY_FAILED, message.deliveryState);
        assertNull(message.nextRetryAt);
    }

    @Test
    public void tickRequestsPositionBeaconOnlyAtConfiguredCadence() {
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(new FakeDao(), callbacks);

        controller.setPositionBeaconingEnabled(true, 1_000L);
        controller.tick(1_000L);
        controller.tick(1_001L);
        controller.tick(301_000L);

        assertEquals(2, callbacks.beaconCount);
    }

    @Test
    public void tickDoesNotReloadHistoryWhenNoMessageIsDue() {
        FakeDao dao = new FakeDao();
        AprsController controller = controller(dao);
        int historyLoadsAfterStartup = dao.historyLoadCount;

        controller.tick(1_000L);

        assertEquals(historyLoadsAfterStartup, dao.historyLoadCount);
        assertEquals(1, dao.dueMessageLoadCount);
    }

    @Test
    public void recordsBulletinsWithoutReliableDeliveryState() {
        FakeDao dao = new FakeDao();
        AprsController controller = controller(dao);

        controller.recordOutgoingMessage("VK3ME", "BLN1CQ", "net starts now", 7);

        APRSMessage bulletin = dao.messages.get(0);
        assertEquals(-1, bulletin.msgNum);
        assertNull(bulletin.messageIdentifier);
        assertEquals(APRSMessage.DELIVERY_NONE, bulletin.deliveryState);
        assertNull(bulletin.nextRetryAt);
    }

    @Test
    public void bulletinNeverEntersTheRetryPath() {
        FakeDao dao = new FakeDao();
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(dao, callbacks);
        controller.recordOutgoingMessage("VK3ME", "BLN1CQ", "net starts now", 7);

        controller.tick(Long.MAX_VALUE);

        assertEquals(0, callbacks.retryCount);
    }

    @Test
    public void onlyStationDestinationsRequireAcknowledgement() {
        assertTrue(AprsController.requiresAcknowledgement("VK3ABC"));
        assertFalse(AprsController.requiresAcknowledgement("bln1cq"));
        assertFalse(AprsController.requiresAcknowledgement(null));
    }

    @Test
    public void acknowledgementStopsRetriesAndMarksOutgoingMessageDelivered() {
        FakeDao dao = new FakeDao();
        APRSMessage outgoing = pendingOutgoingMessage(7);
        dao.outgoingMessage = outgoing;
        AprsController controller = controller(dao);

        controller.handle(deliveryResponse("ack7"));

        assertTrue(outgoing.wasAcknowledged);
        assertEquals(APRSMessage.DELIVERY_DELIVERED, outgoing.deliveryState);
        assertNull(outgoing.nextRetryAt);
        assertEquals("VK3ME", dao.lastLookupDestination);
        assertEquals("7", dao.lastLookupIdentifier);
    }

    @Test
    public void rejectionStopsRetriesAndMarksOutgoingMessageRejected() {
        FakeDao dao = new FakeDao();
        APRSMessage outgoing = pendingOutgoingMessage(7);
        dao.outgoingMessage = outgoing;
        AprsController controller = controller(dao);

        controller.handle(deliveryResponse("rej7"));

        assertFalse(outgoing.wasAcknowledged);
        assertEquals(APRSMessage.DELIVERY_REJECTED, outgoing.deliveryState);
        assertNull(outgoing.nextRetryAt);
        assertEquals("VK3ME", dao.lastLookupDestination);
        assertEquals("7", dao.lastLookupIdentifier);
    }

    @Test
    public void digipeatsWideOneOneUsingOurCallsign() {
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(new FakeDao(), callbacks);
        controller.setDigipeatingEnabled(true);

        controller.handle(packetWithPath("WIDE1-1"));

        APRSPacket retransmitted = callbacks.lastDigipeatedPacket;
        assertNotNull(retransmitted);
        assertEquals("VK3ME", retransmitted.getDigipeaters().get(0).getCallsign());
        assertTrue(retransmitted.getDigipeaters().get(0).isUsed());
    }

    @Test
    public void digipeatsWideOneTwoAndLeavesOneHopAvailable() {
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(new FakeDao(), callbacks);
        controller.setDigipeatingEnabled(true);

        controller.handle(packetWithPath("WIDE1-2"));

        APRSPacket retransmitted = callbacks.lastDigipeatedPacket;
        assertNotNull(retransmitted);
        assertEquals("VK3ME", retransmitted.getDigipeaters().get(0).getCallsign());
        assertTrue(retransmitted.getDigipeaters().get(0).isUsed());
        assertEquals("WIDE1", retransmitted.getDigipeaters().get(1).getCallsign());
        assertEquals("1", retransmitted.getDigipeaters().get(1).getSsid());
        assertFalse(retransmitted.getDigipeaters().get(1).isUsed());
    }

    @Test
    public void doesNotDigipeatUntilEnabledAndSuppressesRepeatedPackets() {
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(new FakeDao(), callbacks);
        APRSPacket packet = packetWithPath("WIDE1-1");

        controller.handle(packet);
        controller.setDigipeatingEnabled(true);
        controller.handle(packet);
        controller.handle(packet);

        assertEquals(1, callbacks.digipeatCount);
    }

    @Test
    public void doesNotStoreAnEchoOfOurRecentDigipeat() {
        FakeDao dao = new FakeDao();
        AprsController controller = controller(dao, new FakeCallbacks());
        controller.setDigipeatingEnabled(true);
        APRSPacket packet = packetWithPath("WIDE1-1");

        controller.handle(packet);
        controller.handle(packet);

        assertEquals(1, dao.messages.size());
    }

    private AprsController controller(FakeDao dao) {
        return controller(dao, new FakeCallbacks());
    }

    private AprsController controller(FakeDao dao, FakeCallbacks callbacks) {
        return new AprsController(dao, Runnable::run, callbacks);
    }

    private APRSMessage pendingMessage(long nextRetryAt, int retriesRemaining) {
        APRSMessage message = new APRSMessage();
        message.type = APRSMessage.MESSAGE_TYPE;
        message.deliveryState = APRSMessage.DELIVERY_PENDING;
        message.nextRetryAt = nextRetryAt;
        message.retriesRemaining = retriesRemaining;
        message.transmitAttempts = 1;
        return message;
    }

    private APRSMessage pendingOutgoingMessage(int messageNumber) {
        APRSMessage message = pendingMessage(1_000L, 5);
        message.fromCallsign = "VK3ME";
        message.toCallsign = "VK3ABC";
        message.msgNum = messageNumber;
        message.messageIdentifier = String.valueOf(messageNumber);
        return message;
    }

    private APRSPacket packetWithPath(String path) {
        return new APRSPacket("VK3ABC", "APRS", java.util.Collections.singletonList(new Digipeater(path)),
            ">test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private APRSPacket deliveryResponse(String response) {
        return new APRSPacket("VK3ABC", "APRS", java.util.Collections.emptyList(),
            MessagePacket.createMessagePayload("VK3ME", response, null));
    }

    private static final class FakeDao implements AprsController.Repository {
        private final List<APRSMessage> messages = new ArrayList<>();
        private boolean duplicate;
        private int historyLoadCount;
        private int dueMessageLoadCount;
        private APRSMessage outgoingMessage;
        private String lastLookupDestination;
        private String lastLookupIdentifier;
        @Override public List<APRSMessage> loadMessages() { historyLoadCount++; return messages; }
        @Override public List<APRSMessage> loadDueReliableMessages(long now) {
            dueMessageLoadCount++;
            List<APRSMessage> dueMessages = new ArrayList<>();
            for (APRSMessage message : messages) {
                if (message.deliveryState == APRSMessage.DELIVERY_PENDING && message.nextRetryAt != null
                        && message.nextRetryAt <= now) dueMessages.add(message);
            }
            return dueMessages;
        }
        @Override public APRSMessage findOutgoingMessage(String destination, String messageIdentifier) {
            lastLookupDestination = destination;
            lastLookupIdentifier = messageIdentifier;
            return outgoingMessage;
        }
        @Override public void insert(APRSMessage message) { messages.add(message); }
        @Override public void update(APRSMessage message) {
            // Tests inspect the mutable in-memory message directly after an update.
        }
        @Override public boolean isRecentDuplicate(String fromCallsign, String msgBody, int msgNum) { return duplicate; }
    }

    private static final class FakeCallbacks implements AprsController.Callbacks {
        private int retryCount;
        private int beaconCount;
        private int digipeatCount;
        private APRSPacket lastDigipeatedPacket;
        @Override public String getCallsign() { return "VK3ME"; }
        @Override public void showNotification(String title, String message) {
            // Notification presentation is outside this controller test double's scope.
        }
        @Override public void sendAcknowledgement(String destination, int messageNumber) {
            // Acknowledgement transmission is outside this controller test double's scope.
        }
        @Override public boolean retryMessage(APRSMessage message) { retryCount++; return true; }
        @Override public void requestPositionBeacon() { beaconCount++; }
        @Override public boolean transmitDigipeatedPacket(APRSPacket packet) {
            digipeatCount++;
            lastDigipeatedPacket = packet;
            return true;
        }
    }
}
