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

        controller.recordOutgoingMessage("vk3abc", "vk3def", " hello ", 7, "144.3900");

        assertEquals(1, dao.messages.size());
        APRSMessage message = dao.messages.get(0);
        assertEquals("VK3ABC", message.fromCallsign);
        assertEquals("VK3DEF", message.toCallsign);
        assertEquals("hello", message.msgBody);
        assertEquals(7, message.msgNum);
        assertEquals(APRSMessage.SOURCE_TX_RF, message.source);
        assertEquals("144.3900", message.frequency);
    }

    @Test
    public void recordsReceivedMessagesWithExplicitRfSourceAndFrequency() {
        FakeDao dao = new FakeDao();
        AprsController controller = controller(dao);

        controller.handle(directMessage("VK3ABC", "VK3ME", "hello", "7"),
            APRSMessage.SOURCE_RX_RF, "145.1750");

        APRSMessage message = dao.messages.get(0);
        assertEquals(APRSMessage.SOURCE_RX_RF, message.source);
        assertEquals("145.1750", message.frequency);
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
    public void tickUsesEveryRetryDelayThenFinalAcknowledgementGrace() {
        FakeDao dao = new FakeDao();
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(dao, callbacks);
        APRSMessage message = pendingMessage(0L, 5);
        dao.messages.add(message);

        controller.tick(0L);
        assertRetryState(callbacks, message, 1, 2, 4, 30_000L);
        controller.tick(30_000L);
        assertRetryState(callbacks, message, 2, 3, 3, 90_000L);
        controller.tick(90_000L);
        assertRetryState(callbacks, message, 3, 4, 2, 210_000L);
        controller.tick(210_000L);
        assertRetryState(callbacks, message, 4, 5, 1, 450_000L);
        controller.tick(450_000L);
        assertRetryState(callbacks, message, 5, 6, 0, 480_000L);
        controller.tick(480_000L);

        assertEquals(5, callbacks.retryCount);
        assertEquals(APRSMessage.DELIVERY_FAILED, message.deliveryState);
        assertNull(message.nextRetryAt);
    }

    @Test
    public void failedRetryDoesNotConsumeAnAttempt() {
        FakeDao dao = new FakeDao();
        FakeCallbacks callbacks = new FakeCallbacks();
        callbacks.retrySucceeds = false;
        AprsController controller = controller(dao, callbacks);
        APRSMessage message = pendingMessage(100L, 5);
        dao.messages.add(message);

        controller.tick(100L);

        assertEquals(1, callbacks.retryCount);
        assertEquals(1, message.transmitAttempts);
        assertEquals(Integer.valueOf(5), message.retriesRemaining);
        assertEquals(Long.valueOf(15_100L), message.nextRetryAt);
    }

    @Test
    public void restartedControllerRetriesOnlyPersistedPendingMessages() {
        FakeDao dao = new FakeDao();
        APRSMessage pending = pendingMessage(0L, 5);
        APRSMessage delivered = pendingMessage(0L, 5);
        delivered.deliveryState = APRSMessage.DELIVERY_DELIVERED;
        APRSMessage rejected = pendingMessage(0L, 5);
        rejected.deliveryState = APRSMessage.DELIVERY_REJECTED;
        APRSMessage failed = pendingMessage(0L, 5);
        failed.deliveryState = APRSMessage.DELIVERY_FAILED;
        dao.messages.add(pending);
        dao.messages.add(delivered);
        dao.messages.add(rejected);
        dao.messages.add(failed);
        FakeCallbacks callbacks = new FakeCallbacks();

        controller(dao, callbacks).tick(0L);

        assertEquals(1, callbacks.retryCount);
        assertEquals(2, pending.transmitAttempts);
        assertEquals(1, delivered.transmitAttempts);
        assertEquals(1, rejected.transmitAttempts);
        assertEquals(1, failed.transmitAttempts);
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

        controller.recordOutgoingMessage("VK3ME", "BLN1CQ", "net starts now", 7, "144.3900");

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
        controller.recordOutgoingMessage("VK3ME", "BLN1CQ", "net starts now", 7, "144.3900");

        controller.tick(Long.MAX_VALUE);

        assertEquals(0, callbacks.retryCount);
    }

    @Test
    public void onlyStationDestinationsRequireAcknowledgement() {
        assertTrue(AprsController.requiresAcknowledgement("VK3ABC"));
        assertTrue(AprsController.requiresAcknowledgement("VK3ABC-7"));
        assertFalse(AprsController.requiresAcknowledgement("BLN1CQ"));
        assertFalse(AprsController.requiresAcknowledgement("bln1cq"));
        assertFalse(AprsController.requiresAcknowledgement("BLN0"));
        assertFalse(AprsController.requiresAcknowledgement("ALL"));
        assertFalse(AprsController.requiresAcknowledgement("all"));
        assertFalse(AprsController.requiresAcknowledgement("QST"));
        assertFalse(AprsController.requiresAcknowledgement("qst"));
        assertFalse(AprsController.requiresAcknowledgement("CQ"));
        assertFalse(AprsController.requiresAcknowledgement("cq"));
        assertFalse(AprsController.requiresAcknowledgement(null));
    }

    @Test
    public void groupAddressIsRecordedWithoutReliableDeliveryState() {
        FakeDao dao = new FakeDao();
        AprsController controller = controller(dao);

        controller.recordOutgoingMessage("VK3ME", "QST", "net starts now", 7, "144.3900");

        APRSMessage groupMessage = dao.messages.get(0);
        assertEquals(-1, groupMessage.msgNum);
        assertNull(groupMessage.messageIdentifier);
        assertEquals(APRSMessage.DELIVERY_NONE, groupMessage.deliveryState);
        assertNull(groupMessage.nextRetryAt);
    }

    @Test
    public void acknowledgementOnlyResolvesTheMatchingRemoteStation() {
        FakeDao dao = new FakeDao();
        APRSMessage toAbc = pendingOutgoingMessage("VK3ABC", 7);
        APRSMessage toXyz = pendingOutgoingMessage("VK3XYZ", 7);
        dao.pendingOutgoingMessages.add(toAbc);
        dao.pendingOutgoingMessages.add(toXyz);
        AprsController controller = controller(dao);

        controller.handle(deliveryResponse("VK3ABC", "ack7"));

        assertTrue(toAbc.wasAcknowledged);
        assertEquals(APRSMessage.DELIVERY_DELIVERED, toAbc.deliveryState);
        assertNull(toAbc.nextRetryAt);
        assertEquals(APRSMessage.DELIVERY_PENDING, toXyz.deliveryState);
        assertEquals("VK3ME", dao.lastLookupLocalCallsign);
        assertEquals("VK3ABC", dao.lastLookupRemoteCallsign);
        assertEquals("7", dao.lastLookupIdentifier);
    }

    @Test
    public void rejectionOnlyResolvesTheMatchingRemoteStation() {
        FakeDao dao = new FakeDao();
        APRSMessage toAbc = pendingOutgoingMessage("VK3ABC", 7);
        APRSMessage toXyz = pendingOutgoingMessage("VK3XYZ", 7);
        dao.pendingOutgoingMessages.add(toAbc);
        dao.pendingOutgoingMessages.add(toXyz);
        AprsController controller = controller(dao);

        controller.handle(deliveryResponse("VK3ABC", "rej7"));

        assertFalse(toAbc.wasAcknowledged);
        assertEquals(APRSMessage.DELIVERY_REJECTED, toAbc.deliveryState);
        assertNull(toAbc.nextRetryAt);
        assertEquals(APRSMessage.DELIVERY_PENDING, toXyz.deliveryState);
        assertEquals("VK3ME", dao.lastLookupLocalCallsign);
        assertEquals("VK3ABC", dao.lastLookupRemoteCallsign);
        assertEquals("7", dao.lastLookupIdentifier);
    }

    @Test
    public void acknowledgementFromUnknownStationDoesNothing() {
        FakeDao dao = new FakeDao();
        APRSMessage outgoing = pendingOutgoingMessage("VK3ABC", 7);
        dao.pendingOutgoingMessages.add(outgoing);
        AprsController controller = controller(dao);

        controller.handle(deliveryResponse("VK3XYZ", "ack7"));

        assertEquals(APRSMessage.DELIVERY_PENDING, outgoing.deliveryState);
        assertFalse(outgoing.wasAcknowledged);
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

    @Test
    public void duplicateDirectMessageIsAcknowledgedWithoutAnotherNotification() {
        FakeDao dao = new FakeDao();
        FakeCallbacks callbacks = new FakeCallbacks();
        AprsController controller = controller(dao, callbacks);
        APRSPacket message = directMessage("VK3ABC", "VK3ME", "hello", "7");

        controller.handle(message);
        dao.duplicate = true;
        controller.handle(message);

        assertEquals(1, dao.messages.size());
        assertEquals(1, callbacks.notificationCount);
        assertEquals(2, callbacks.acknowledgementCount);
        assertEquals("VK3ABC", callbacks.lastAcknowledgementDestination);
        assertEquals(7, callbacks.lastAcknowledgementNumber);
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

    private APRSMessage pendingOutgoingMessage(String destination, int messageNumber) {
        APRSMessage message = pendingMessage(1_000L, 5);
        message.fromCallsign = "VK3ME";
        message.toCallsign = destination;
        message.msgNum = messageNumber;
        message.messageIdentifier = String.valueOf(messageNumber);
        return message;
    }

    private APRSPacket packetWithPath(String path) {
        return new APRSPacket("VK3ABC", "APRS", java.util.Collections.singletonList(new Digipeater(path)),
            ">test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private APRSPacket deliveryResponse(String source, String response) {
        return new APRSPacket(source, "APRS", java.util.Collections.emptyList(),
            MessagePacket.createMessagePayload("VK3ME", response, null));
    }

    private APRSPacket directMessage(String source, String destination, String body, String identifier) {
        return new APRSPacket(source, "APRS", java.util.Collections.emptyList(),
            MessagePacket.createMessagePayload(destination, body, identifier));
    }

    private void assertRetryState(FakeCallbacks callbacks, APRSMessage message, int retries,
                                  int attempts, int remaining, long nextRetryAt) {
        assertEquals(retries, callbacks.retryCount);
        assertEquals(attempts, message.transmitAttempts);
        assertEquals(Integer.valueOf(remaining), message.retriesRemaining);
        assertEquals(Long.valueOf(nextRetryAt), message.nextRetryAt);
    }

    private static final class FakeDao implements AprsController.Repository {
        private final List<APRSMessage> messages = new ArrayList<>();
        private boolean duplicate;
        private int historyLoadCount;
        private int dueMessageLoadCount;
        private final List<APRSMessage> pendingOutgoingMessages = new ArrayList<>();
        private String lastLookupLocalCallsign;
        private String lastLookupRemoteCallsign;
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
        @Override public APRSMessage findPendingOutgoingMessage(String localCallsign,
                                                                 String remoteCallsign,
                                                                 String messageIdentifier) {
            lastLookupLocalCallsign = localCallsign;
            lastLookupRemoteCallsign = remoteCallsign;
            lastLookupIdentifier = messageIdentifier;
            for (APRSMessage message : pendingOutgoingMessages) {
                if (message.deliveryState == APRSMessage.DELIVERY_PENDING
                        && localCallsign.equals(message.fromCallsign)
                        && remoteCallsign.equals(message.toCallsign)
                        && messageIdentifier.equals(message.messageIdentifier)) {
                    return message;
                }
            }
            return null;
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
        private int notificationCount;
        private int acknowledgementCount;
        private boolean retrySucceeds = true;
        private String lastAcknowledgementDestination;
        private int lastAcknowledgementNumber;
        private APRSPacket lastDigipeatedPacket;
        @Override public String getCallsign() { return "VK3ME"; }
        @Override public void showNotification(String title, String message) {
            notificationCount++;
        }
        @Override public void sendAcknowledgement(String destination, int messageNumber) {
            acknowledgementCount++;
            lastAcknowledgementDestination = destination;
            lastAcknowledgementNumber = messageNumber;
        }
        @Override public boolean retryMessage(APRSMessage message) { retryCount++; return retrySucceeds; }
        @Override public void requestPositionBeacon() { beaconCount++; }
        @Override public boolean transmitDigipeatedPacket(APRSPacket packet) {
            digipeatCount++;
            lastDigipeatedPacket = packet;
            return true;
        }
    }
}
