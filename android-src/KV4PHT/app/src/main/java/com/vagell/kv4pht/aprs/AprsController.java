package com.vagell.kv4pht.aprs;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.APRSTypes;
import com.vagell.kv4pht.aprs.parser.Digipeater;
import com.vagell.kv4pht.aprs.parser.InformationField;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.ObjectField;
import com.vagell.kv4pht.aprs.parser.PositionField;
import com.vagell.kv4pht.aprs.parser.ThirdPartyField;
import com.vagell.kv4pht.aprs.parser.Utilities;
import com.vagell.kv4pht.aprs.parser.WeatherField;
import com.vagell.kv4pht.data.APRSMessage;
import com.vagell.kv4pht.data.APRSMessageDao;
import java.util.concurrent.Executor;
import java.time.Instant;

/**
 * Owns APRS application policy and persistent message state.
 *
 * <p>{@code RadioAudioService} supplies radio, notification, and location capabilities through
 * {@link Callbacks}; it does not parse or persist APRS messages. The controller parses received
 * packets, de-duplicates and stores them, manages reliable-message retries, schedules position
 * beacons, and decides whether a received packet is eligible for fill-in digipeating. UI code
 * observes {@link #getMessages()} and never accesses the APRS message DAO directly.</p>
 *
 * <p>Call {@link #tick(long)} from the service's existing serialized polling loop. Persistence
 * work is dispatched to the executor supplied at construction.</p>
 */
public final class AprsController {
    private static final long[] RETRY_DELAYS_MS = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L};
    private static final long FINAL_ACK_GRACE_MS = 30_000L;
    private static final long BEACON_INTERVAL_MS = 5 * 60_000L;
    /** Persistence boundary for stateful APRS messaging, allowing the policy to be unit tested. */
    public interface Repository {
        /** Returns every retained APRS record, including messages awaiting acknowledgement. */
        java.util.List<APRSMessage> loadMessages();
        /** Persists a newly received, sent, or beacon record. */
        void insert(APRSMessage message);
        /** Persists a changed delivery state or retry deadline. */
        void update(APRSMessage message);
        /** Finds the locally sent message identified by a received APRS acknowledgement. */
        APRSMessage findOutgoingMessage(String destination, String messageIdentifier);
        /** Reports whether this received numbered message was retained recently. */
        boolean isRecentDuplicate(String fromCallsign, String messageBody, int messageNumber);
    }

    /** Room-backed repository adapter; Room access remains outside the controller's state machine. */
    public static final class RoomRepository implements Repository {
        private final APRSMessageDao dao;

        public RoomRepository(APRSMessageDao dao) {
            this.dao = dao;
        }

        @Override public java.util.List<APRSMessage> loadMessages() { return dao.getAll(); }
        @Override public void insert(APRSMessage message) { dao.insertAll(message); }
        @Override public void update(APRSMessage message) { dao.update(message); }
        @Override public APRSMessage findOutgoingMessage(String destination, String messageIdentifier) {
            return dao.getMsgToAck(destination, Integer.parseInt(messageIdentifier));
        }
        @Override public boolean isRecentDuplicate(String fromCallsign, String messageBody, int messageNumber) {
            return dao.isRecentDuplicate(fromCallsign, messageBody, messageNumber);
        }
    }

    /**
     * Capabilities provided by the service host.
     *
     * <p>Callbacks may interact with Android and radio hardware; the controller itself remains
     * independent of either.</p>
     */
    public interface Callbacks {
        /** Returns the configured local callsign, or {@code null} when it is not configured. */
        String getCallsign();
        /** Presents a notification for an incoming direct message. */
        void showNotification(String title, String message);
        /** Transmits an APRS acknowledgement after the service's acknowledgement delay. */
        void sendAcknowledgement(String destination, int messageNumber);
        /** Attempts one retransmission and returns whether it was accepted for RF transmission. */
        boolean retryMessage(APRSMessage message);
        /** Requests that the service acquire a position and transmit a position beacon. */
        void requestPositionBeacon();
        /** Attempts RF transmission of the controller's rewritten digipeated packet. */
        boolean transmitDigipeatedPacket(APRSPacket packet);
    }

    private final Repository repository;
    private final Executor executor;
    private final Callbacks callbacks;
    private final MutableLiveData<java.util.List<APRSMessage>> messages = new MutableLiveData<>();
    private volatile boolean positionBeaconingEnabled;
    private volatile long nextPositionBeaconAt;
    private volatile boolean digipeatingEnabled;
    private final java.util.Map<String, Long> digipeatDedupCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Creates and asynchronously publishes the persisted APRS message history.
     *
     * @param repository durable APRS-message storage
     * @param executor serialized executor used for storage and retry work
     * @param callbacks service-provided radio, notification, and location capabilities
     */
    public AprsController(Repository repository, Executor executor, Callbacks callbacks) {
        this.repository = repository;
        this.executor = executor;
        this.callbacks = callbacks;
        refreshMessages();
    }

    /** Returns the APRS history for the UI to observe. */
    public LiveData<java.util.List<APRSMessage>> getMessages() {
        return messages;
    }

    /** Returns whether periodic position beaconing is currently enabled. */
    public boolean isPositionBeaconingEnabled() {
        return positionBeaconingEnabled;
    }

    /** Enables or disables fill-in digipeating for subsequently received APRS packets. */
    public void setDigipeatingEnabled(boolean enabled) {
        digipeatingEnabled = enabled;
    }

    /**
     * Notifies and acknowledges a numbered message addressed to the local callsign.
     *
     * <p>Messages for other stations remain in history but must not produce local notifications
     * or acknowledgements.</p>
     */
    public void notifyAndAcknowledgeDirectMessage(APRSMessage message, APRSPacket packet) {
        String callsign = callbacks.getCallsign();
        if (callsign == null || message.toCallsign == null
                || !message.toCallsign.trim().equalsIgnoreCase(callsign.trim())) {
            return;
        }
        callbacks.showNotification(packet.getSourceCall() + " messaged you", message.msgBody);
        if (message.msgNum != -1) {
            callbacks.sendAcknowledgement(packet.getSourceCall().toUpperCase(), message.msgNum);
        }
    }

    /**
     * Processes one decoded RF APRS packet.
     *
     * <p>Digipeating is evaluated before local storage. Third-party packets are unwrapped for
     * display while preserving their relaying station.</p>
     */
    public void handle(APRSPacket rawPacket) {
        maybeDigipeat(rawPacket);
        PacketContext context = unwrap(rawPacket);
        if (context == null) {
            storeInvalidRelay(rawPacket, new APRSMessage());
            return;
        }
        handleDecodedPacket(context);
    }

    /** Converts a parser-validated packet into its durable APRS history representation. */
    private void handleDecodedPacket(PacketContext context) {
        APRSMessage message = new APRSMessage();
        InformationField info = context.info;
        WeatherField weather = (WeatherField) info.getAprsData(APRSTypes.T_WX);
        PositionField position = (PositionField) info.getAprsData(APRSTypes.T_POSITION);
        ObjectField object = (ObjectField) info.getAprsData(APRSTypes.T_OBJECT);
        message.timestamp = Instant.now().getEpochSecond();
        message.fromCallsign = context.packet.getSourceCall();
        applyPosition(message, position);
        applyComment(message, context.packet, info, position, object, weather);
        if (!applyPayload(message, context.packet, info, object, weather)) return;
        if (context.packet.hasFault() || message.type == APRSMessage.UNKNOWN_TYPE
                && (message.comment == null || message.comment.trim().isEmpty())) {
            message.comment = "Raw: " + new String(info.getRawBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        message.relayCallsign = context.relayCallsign;
        save(message);
    }

    /** Applies supported fill-in digipeater rules and suppresses a repeated packet for 28 seconds. */
    private void maybeDigipeat(APRSPacket packet) {
        String localCallsign = callbacks.getCallsign();
        if (!digipeatingEnabled || localCallsign == null || localCallsign.trim().isEmpty()) return;
        String key = packet.getSourceCall() + "|" + packet.getDestinationCall() + "|"
            + java.util.Base64.getEncoder().encodeToString(packet.getPayload().getRawBytes());
        long now = System.currentTimeMillis();
        Long previous = digipeatDedupCache.get(key);
        digipeatDedupCache.entrySet().removeIf(entry -> now - entry.getValue() >= 28_000L);
        if (previous != null && now - previous < 28_000L) return;

        java.util.List<Digipeater> digis = packet.getDigipeaters();
        if (digis == null || digis.isEmpty()) return;
        int index = firstUnusedDigipeater(digis);
        if (index < 0) return;
        Digipeater next = digis.get(index);
        String baseCall = APRSPacket.getBaseCall(next.getCallsign());
        int ssid = parseSsid(next);
        boolean ours = baseCall.equalsIgnoreCase(APRSPacket.getBaseCall(localCallsign));
        boolean wide1 = baseCall.equalsIgnoreCase("WIDE1") && ssid >= 1 && ssid <= 2;
        if (!ours && !wide1) return;

        java.util.List<Digipeater> replacement = new java.util.ArrayList<>(digis);
        if (ours) {
            replacement.set(index, usedDigipeater(next.getCallsign()));
        } else if (ssid == 1) {
            replacement.set(index, usedDigipeater(localCallsign));
        } else {
            Digipeater decremented = new Digipeater(baseCall + "-1");
            replacement.set(index, decremented);
            replacement.add(index, usedDigipeater(localCallsign));
        }
        APRSPacket retransmit = new APRSPacket(packet.getSourceCall(), packet.getDestinationCall(),
            replacement, packet.getPayload().getRawBytes());
        retransmit.setComment(packet.getComment());
        if (callbacks.transmitDigipeatedPacket(retransmit)) {
            digipeatDedupCache.put(key, now);
        }
    }

    private int firstUnusedDigipeater(java.util.List<Digipeater> digis) {
        for (int i = 0; i < digis.size(); i++) if (!digis.get(i).isUsed()) return i;
        return -1;
    }

    private int parseSsid(Digipeater digipeater) {
        try { return Integer.parseInt(APRSPacket.getSsid(digipeater.toString())); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private Digipeater usedDigipeater(String callsign) {
        Digipeater digipeater = new Digipeater(callsign);
        digipeater.setUsed(true);
        return digipeater;
    }

    private PacketContext unwrap(APRSPacket raw) {
        InformationField info = raw.getPayload();
        ThirdPartyField thirdParty = (ThirdPartyField) info.getAprsData(APRSTypes.T_THIRDPARTY);
        if (thirdParty == null) return new PacketContext(raw, info, null);
        APRSPacket inner = thirdParty.getInnerPacket();
        return inner == null || inner.hasFault() ? null
            : new PacketContext(inner, inner.getPayload(), raw.getSourceCall());
    }

    private void storeInvalidRelay(APRSPacket raw, APRSMessage message) {
        message.type = APRSMessage.UNKNOWN_TYPE;
        message.fromCallsign = raw.getSourceCall();
        message.timestamp = Instant.now().getEpochSecond();
        message.relayCallsign = raw.getSourceCall();
        message.comment = "Raw: " + new String(raw.getPayload().getRawBytes(), java.nio.charset.StandardCharsets.UTF_8);
        save(message);
    }

    private void applyPosition(APRSMessage message, PositionField position) {
        if (position == null) return;
        message.type = APRSMessage.POSITION_TYPE;
        message.positionLat = position.getPosition().getLatitude();
        message.positionLong = position.getPosition().getLongitude();
    }

    private void applyComment(APRSMessage message, APRSPacket packet, InformationField info,
                              PositionField position, ObjectField object, WeatherField weather) {
        String comment = firstComment(packet.getComment(), info.getComment());
        comment = firstComment(comment, position == null ? null : position.getComment());
        comment = firstComment(comment, object == null ? null : object.getComment());
        comment = firstComment(comment, weather == null ? null : weather.getComment());
        if (comment != null) message.comment = comment;
    }

    private String firstComment(String preferred, String fallback) {
        return preferred == null || preferred.trim().isEmpty() ? fallback : preferred;
    }

    private boolean applyPayload(APRSMessage message, APRSPacket packet, InformationField info,
                                 ObjectField object, WeatherField weather) {
        if (weather != null) {
            applyWeather(message, weather);
            return true;
        }
        if (info.getDataTypeIdentifier() == ';') {
            applyObject(message, object);
            return true;
        }
        if (info.getDataTypeIdentifier() != ':') return true;
        return applyMessage(message, packet, info);
    }

    private void applyWeather(APRSMessage message, WeatherField weather) {
        message.type = APRSMessage.WEATHER_TYPE;
        message.temperature = weather.getTemp() == null ? 0 : weather.getTemp();
        message.humidity = weather.getHumidity() == null ? 0 : weather.getHumidity();
        message.pressure = weather.getPressure() == null ? 0 : weather.getPressure();
        message.rain = weather.getRainLast24Hours() == null ? 0 : weather.getRainLast24Hours();
        message.snow = weather.getSnowfallLast24Hours() == null ? 0 : weather.getSnowfallLast24Hours();
        message.windForce = weather.getWindSpeed() == null ? 0 : weather.getWindSpeed();
        message.windDir = weather.getWindDirection() == null ? "" : Utilities.degressToCardinal(weather.getWindDirection());
    }

    private void applyObject(APRSMessage message, ObjectField object) {
        message.type = APRSMessage.OBJECT_TYPE;
        if (object != null) message.objName = object.getObjectName();
    }

    private boolean applyMessage(APRSMessage message, APRSPacket packet, InformationField info) {
        message.type = APRSMessage.MESSAGE_TYPE;
        MessagePacket packetMessage = new MessagePacket(info.getRawBytes(), packet.getDestinationCall());
        message.toCallsign = packetMessage.getTargetCallsign();
        message.msgNum = parseNumber(packetMessage.getMessageNumber());
        if (packetMessage.isAck()) {
            message.wasAcknowledged = true;
            return message.msgNum != -1;
        }
        message.msgBody = packetMessage.getMessageBody();
        notifyAndAcknowledgeDirectMessage(message, packet);
        return true;
    }

    private int parseNumber(String number) {
        if (number == null || number.trim().isEmpty()) return -1;
        try { return Integer.parseInt(number.trim()); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Stores a record unless it is a duplicate; acknowledgement records instead update their
     * corresponding outgoing message.
     */
    public void save(APRSMessage message) {
        executor.execute(() -> {
            if (message.wasAcknowledged) {
                if (!markAcknowledged(message)) {
                    return;
                }
            } else if (isRecentDuplicate(message)) {
                return;
            } else {
                repository.insert(message);
            }
            refreshMessages();
        });
    }

    /**
     * Advances due retry deadlines and periodic position beacon scheduling.
     *
     * <p>Called by {@code RadioAudioService}'s serialized handler loop. A failed RF attempt is
     * retried at the initial retry delay without consuming an attempt.</p>
     *
     * @param now current wall-clock time in milliseconds
     */
    public void tick(long now) {
        executor.execute(() -> {
            for (APRSMessage message : repository.loadMessages()) {
                if (message.deliveryState != APRSMessage.DELIVERY_PENDING || message.nextRetryAt == null
                        || message.nextRetryAt > now) {
                    continue;
                }
                retryOrFail(message, now);
            }
            if (positionBeaconingEnabled && now >= nextPositionBeaconAt) {
                nextPositionBeaconAt = now + BEACON_INTERVAL_MS;
                callbacks.requestPositionBeacon();
            }
            refreshMessages();
        });
    }

    /**
     * Enables or disables periodic position beacons. Enabling schedules the first beacon now.
     *
     * @param enabled whether periodic beaconing is enabled
     * @param now current wall-clock time in milliseconds
     */
    public void setPositionBeaconingEnabled(boolean enabled, long now) {
        positionBeaconingEnabled = enabled;
        nextPositionBeaconAt = enabled ? now : 0;
    }

    private void retryOrFail(APRSMessage message, long now) {
        int remaining = message.retriesRemaining == null ? 0 : message.retriesRemaining;
        if (remaining == 0) {
            message.deliveryState = APRSMessage.DELIVERY_FAILED;
            message.nextRetryAt = null;
            repository.update(message);
            return;
        }
        if (!callbacks.retryMessage(message)) {
            message.nextRetryAt = now + RETRY_DELAYS_MS[0];
            repository.update(message);
            return;
        }
        message.transmitAttempts++;
        message.retriesRemaining = remaining - 1;
        message.nextRetryAt = remaining == 1
            ? now + FINAL_ACK_GRACE_MS
            : now + RETRY_DELAYS_MS[RETRY_DELAYS_MS.length - remaining + 1];
        repository.update(message);
    }

    private void refreshMessages() {
        executor.execute(() -> messages.postValue(repository.loadMessages()));
    }

    /**
     * Records a transmitted numbered message and schedules its acknowledgement retry sequence.
     */
    public void recordOutgoingMessage(String from, String to, String text, int messageNumber) {
        APRSMessage message = new APRSMessage();
        message.type = APRSMessage.MESSAGE_TYPE;
        message.fromCallsign = from.toUpperCase().trim();
        message.toCallsign = to.toUpperCase().trim();
        message.msgBody = text.trim();
        message.timestamp = Instant.now().getEpochSecond();
        message.msgNum = messageNumber;
        message.messageIdentifier = String.valueOf(messageNumber);
        message.deliveryState = APRSMessage.DELIVERY_PENDING;
        message.retriesRemaining = RETRY_DELAYS_MS.length;
        message.transmitAttempts = 1;
        message.nextRetryAt = System.currentTimeMillis() + RETRY_DELAYS_MS[0];
        save(message);
    }

    /** Records a successfully transmitted position beacon in APRS history. */
    public void recordPositionBeacon(String callsign, double latitude, double longitude) {
        APRSMessage message = new APRSMessage();
        message.type = APRSMessage.POSITION_TYPE;
        message.fromCallsign = callsign;
        message.positionLat = latitude;
        message.positionLong = longitude;
        message.timestamp = Instant.now().getEpochSecond();
        save(message);
    }

    private boolean markAcknowledged(APRSMessage message) {
        APRSMessage previous = repository.findOutgoingMessage(message.toCallsign, String.valueOf(message.msgNum));
        if (previous == null) {
            return false;
        }
        previous.wasAcknowledged = true;
        previous.deliveryState = APRSMessage.DELIVERY_DELIVERED;
        previous.retriesRemaining = null;
        previous.nextRetryAt = null;
        repository.update(previous);
        return true;
    }

    private boolean isRecentDuplicate(APRSMessage message) {
        return message.type == APRSMessage.MESSAGE_TYPE && message.msgNum != -1
                && repository.isRecentDuplicate(message.fromCallsign, message.msgBody, message.msgNum);
    }

    private static final class PacketContext {
        private final APRSPacket packet;
        private final InformationField info;
        private final String relayCallsign;
        private PacketContext(APRSPacket packet, InformationField info, String relayCallsign) {
            this.packet = packet;
            this.info = info;
            this.relayCallsign = relayCallsign;
        }
    }
}
