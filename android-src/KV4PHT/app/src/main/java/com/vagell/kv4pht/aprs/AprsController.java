package com.vagell.kv4pht.aprs;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.APRSTypes;
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

/** Coordinates APRS message delivery, acknowledgement, de-duplication, and storage. */
public final class AprsController {
    private static final long[] RETRY_DELAYS_MS = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L};
    private static final long FINAL_ACK_GRACE_MS = 30_000L;
    private static final long BEACON_INTERVAL_MS = 5 * 60_000L;
    /** Persistence boundary for stateful APRS messaging. */
    public interface Repository {
        java.util.List<APRSMessage> loadMessages();
        void insert(APRSMessage message);
        void update(APRSMessage message);
        APRSMessage findOutgoingMessage(String destination, String messageIdentifier);
        boolean isRecentDuplicate(String fromCallsign, String messageBody, int messageNumber);
    }

    /** Room-backed repository; all Room access remains outside the controller's state machine. */
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

    public interface Callbacks {
        String getCallsign();
        void showNotification(String title, String message);
        void sendAcknowledgement(String destination, int messageNumber);
        boolean retryMessage(APRSMessage message);
        void requestPositionBeacon();
    }

    private final Repository repository;
    private final Executor executor;
    private final Callbacks callbacks;
    private final MutableLiveData<java.util.List<APRSMessage>> messages = new MutableLiveData<>();
    private boolean positionBeaconingEnabled;
    private long nextPositionBeaconAt;

    public AprsController(Repository repository, Executor executor, Callbacks callbacks) {
        this.repository = repository;
        this.executor = executor;
        this.callbacks = callbacks;
        refreshMessages();
    }

    public LiveData<java.util.List<APRSMessage>> getMessages() {
        return messages;
    }

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

    public void handle(APRSPacket rawPacket) {
        APRSMessage message = new APRSMessage();
        PacketContext context = unwrap(rawPacket);
        if (context == null) {
            storeInvalidRelay(rawPacket, message);
            return;
        }
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
            message.type = APRSMessage.WEATHER_TYPE;
            message.temperature = weather.getTemp() == null ? 0 : weather.getTemp();
            message.humidity = weather.getHumidity() == null ? 0 : weather.getHumidity();
            message.pressure = weather.getPressure() == null ? 0 : weather.getPressure();
            message.rain = weather.getRainLast24Hours() == null ? 0 : weather.getRainLast24Hours();
            message.snow = weather.getSnowfallLast24Hours() == null ? 0 : weather.getSnowfallLast24Hours();
            message.windForce = weather.getWindSpeed() == null ? 0 : weather.getWindSpeed();
            message.windDir = weather.getWindDirection() == null ? "" : Utilities.degressToCardinal(weather.getWindDirection());
            return true;
        }
        if (info.getDataTypeIdentifier() == ';') {
            message.type = APRSMessage.OBJECT_TYPE;
            if (object != null) message.objName = object.getObjectName();
            return true;
        }
        if (info.getDataTypeIdentifier() != ':') return true;
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

    /** Advances reliable-message retries. Called by RadioAudioService's handler loop. */
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

    public void setPositionBeaconingEnabled(boolean enabled, long now) {
        positionBeaconingEnabled = enabled;
        nextPositionBeaconAt = enabled ? now : 0;
    }

    public boolean isPositionBeaconingEnabled() {
        return positionBeaconingEnabled;
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
        executor.execute(() -> {
            messages.postValue(repository.loadMessages());
        });
    }

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
