package com.vagell.kv4pht.aprs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.Digipeater;
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

    private APRSPacket packetWithPath(String path) {
        return new APRSPacket("VK3ABC", "APRS", java.util.Collections.singletonList(new Digipeater(path)),
            ">test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static final class FakeDao implements AprsController.Repository {
        private final List<APRSMessage> messages = new ArrayList<>();
        private boolean duplicate;
        @Override public List<APRSMessage> loadMessages() { return messages; }
        @Override public APRSMessage findOutgoingMessage(String destination, String messageIdentifier) { return null; }
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
