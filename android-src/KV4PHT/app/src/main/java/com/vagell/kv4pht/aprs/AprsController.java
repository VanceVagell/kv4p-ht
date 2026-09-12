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
import com.vagell.kv4pht.data.AprsEvent;
import com.vagell.kv4pht.data.AprsEventDao;
import com.vagell.kv4pht.data.AprsPacket;
import com.vagell.kv4pht.data.AprsPacketDao;
import com.vagell.kv4pht.data.AprsSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Owns APRS parsing, event aggregation, packet history, retries, beacon cadence, and digipeating.
 *
 * <p>{@link AprsPacket} records transport facts and is normally immutable. {@link AprsEvent}
 * records one user-visible occurrence and may aggregate multiple received copies, retries, and a
 * delivery response. The normal UI observes events; packet history remains available for future
 * diagnostics and iGate work.</p>
 */
public final class AprsController {
    public static final String HISTORY_ONE_DAY = "1d";
    public static final String HISTORY_ONE_WEEK = "1w";
    public static final String HISTORY_TWO_WEEKS = "2w";
    public static final String HISTORY_ONE_MONTH = "1m";
    public static final String HISTORY_ALL = "all";
    public static final String DESTINATION_ALL = "all";
    public static final String DESTINATION_MINE = "mine";

    private static final long DAY_MS = 24 * 60 * 60_000L;
    private static final long[] RETRY_DELAYS_MS = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L};
    private static final long FINAL_ACK_GRACE_MS = 30_000L;
    private static final long BEACON_INTERVAL_MS = 5 * 60_000L;
    private static final long HISTORY_REFRESH_INTERVAL_MS = 60_000L;
    private static final long DUPLICATE_WINDOW_MS = 30 * 60_000L;
    private static final long DIGIPEAT_DEDUP_MS = 28_000L;

    /** Persistence boundary for immutable packet history. */
    public interface PacketRepository {
        long insert(AprsPacket packet);
    }

    /** Persistence boundary for user-visible APRS events and delivery state. */
    public interface EventRepository {
        List<AprsEvent> loadEvents(long sinceMs, String localCallsign, boolean mineOnly);
        List<AprsEvent> loadDueReliableEvents(long now);
        long insert(AprsEvent event);
        void update(AprsEvent event);
        AprsEvent findById(long id);
        AprsEvent findRecentByDedupKey(String dedupKey, long sinceMs);
        AprsEvent findPendingOutgoingEvent(String localCallsign, String remoteCallsign,
                                           String messageIdentifier);
    }

    /** A packet accepted by the radio callback, ready to record as transmitted. */
    public static final class Transmission {
        public final APRSPacket packet;
        public final Long frequencyHz;
        public final byte[] rawAx25;

        public Transmission(APRSPacket packet, Long frequencyHz, byte[] rawAx25) {
            this.packet = packet;
            this.frequencyHz = frequencyHz;
            this.rawAx25 = rawAx25 == null ? null : Arrays.copyOf(rawAx25, rawAx25.length);
        }
    }

    /** Android, radio, and notification capabilities supplied by the service. */
    public interface Callbacks {
        String getCallsign();
        void showNotification(String title, String message);
        void sendAcknowledgement(String destination, String messageIdentifier, long eventId);
        Transmission retryMessage(AprsEvent event);
        void requestPositionBeacon();
        Transmission transmitDigipeatedPacket(APRSPacket packet);
    }

    public static final class RoomPacketRepository implements PacketRepository {
        private final AprsPacketDao dao;

        public RoomPacketRepository(AprsPacketDao dao) {
            this.dao = dao;
        }

        @Override public long insert(AprsPacket packet) {
            return dao.insert(packet);
        }
    }

    public static final class RoomEventRepository implements EventRepository {
        private final AprsEventDao dao;

        public RoomEventRepository(AprsEventDao dao) {
            this.dao = dao;
        }

        @Override public List<AprsEvent> loadEvents(long sinceMs, String localCallsign,
                                                    boolean mineOnly) {
            return mineOnly
                ? dao.getMineSince(sinceMs, AprsEvent.MESSAGE_TYPE, localCallsign)
                : dao.getSince(sinceMs);
        }

        @Override public List<AprsEvent> loadDueReliableEvents(long now) {
            return dao.getDueReliableEvents(AprsEvent.DELIVERY_PENDING, now);
        }

        @Override public long insert(AprsEvent event) {
            return dao.insert(event);
        }

        @Override public void update(AprsEvent event) {
            dao.update(event);
        }

        @Override public AprsEvent findById(long id) {
            return dao.getById(id);
        }

        @Override public AprsEvent findRecentByDedupKey(String dedupKey, long sinceMs) {
            return dao.getRecentByDedupKey(dedupKey, sinceMs);
        }

        @Override public AprsEvent findPendingOutgoingEvent(String localCallsign,
                                                            String remoteCallsign,
                                                            String messageIdentifier) {
            return dao.getPendingOutgoingEvent(localCallsign, remoteCallsign, messageIdentifier,
                AprsEvent.DELIVERY_PENDING);
        }
    }

    private final PacketRepository packetRepository;
    private final EventRepository eventRepository;
    private final Executor executor;
    private final Callbacks callbacks;
    private final MutableLiveData<List<AprsEvent>> events = new MutableLiveData<>();
    private final Map<String, Long> digipeatInputCache = new ConcurrentHashMap<>();
    private final Map<String, Long> digipeatOutputCache = new ConcurrentHashMap<>();
    private volatile boolean positionBeaconingEnabled;
    private volatile long nextPositionBeaconAt;
    private volatile boolean digipeatingEnabled;
    private volatile String historyWindow = HISTORY_ALL;
    private volatile String destinationFilter = DESTINATION_ALL;
    private volatile long nextHistoryRefreshAt = Long.MAX_VALUE;

    public AprsController(PacketRepository packetRepository, EventRepository eventRepository,
                          Executor executor, Callbacks callbacks) {
        this.packetRepository = packetRepository;
        this.eventRepository = eventRepository;
        this.executor = executor;
        this.callbacks = callbacks;
        refreshEvents();
    }

    public LiveData<List<AprsEvent>> getEvents() {
        return events;
    }

    public boolean isPositionBeaconingEnabled() {
        return positionBeaconingEnabled;
    }

    public void setDigipeatingEnabled(boolean enabled) {
        digipeatingEnabled = enabled;
    }

    /** Selects how much event history is exposed to the normal APRS UI. */
    public void setHistoryWindow(String value) {
        historyWindow = normalizeHistoryWindow(value);
        long now = System.currentTimeMillis();
        nextHistoryRefreshAt = HISTORY_ALL.equals(historyWindow)
            ? Long.MAX_VALUE : now + HISTORY_REFRESH_INTERVAL_MS;
        refreshEvents(now);
    }

    /** Selects whether the UI shows every message destination or only local/broadcast traffic. */
    public void setDestinationFilter(String value) {
        destinationFilter = DESTINATION_MINE.equalsIgnoreCase(value)
            ? DESTINATION_MINE : DESTINATION_ALL;
        refreshEvents();
    }

    /** Processes one decoded packet and associates it with a user event when possible. */
    public void handle(APRSPacket packet) {
        handle(packet, AprsSource.UNKNOWN, null, null);
    }

    /** Processes one decoded packet together with its transport metadata. */
    public void handle(APRSPacket packet, String source, Long frequencyHz, byte[] rawAx25) {
        if (isRecentlyDigipeated(packet)) return;
        Transmission digipeated = maybeDigipeat(packet);
        AprsPacket packetRecord = physicalPacket(packet, source, frequencyHz, rawAx25);
        PacketContext context = unwrap(packet);
        ParsedEvent parsed = context == null ? null : parseEvent(context);
        executor.execute(() -> persistIncoming(packetRecord, parsed, digipeated));
    }

    private void persistIncoming(AprsPacket packet, ParsedEvent parsed, Transmission digipeated) {
        AprsEvent event = null;
        boolean created = false;
        if (parsed != null && (parsed.acknowledgement || parsed.rejection)) {
            event = eventRepository.findPendingOutgoingEvent(parsed.targetCallsign,
                parsed.fromCallsign, parsed.messageIdentifier);
            if (event != null) {
                event.deliveryState = parsed.acknowledgement
                    ? AprsEvent.DELIVERY_DELIVERED : AprsEvent.DELIVERY_REJECTED;
                event.nextRetryAtMs = null;
                associatePacket(event, packet);
            } else {
                packetRepository.insert(packet);
            }
        } else if (parsed != null && parsed.event != null) {
            AprsEvent candidate = parsed.event;
            event = eventRepository.findRecentByDedupKey(candidate.dedupKey,
                candidate.lastSeenMs - DUPLICATE_WINDOW_MS);
            if (event == null) {
                candidate.packetCount = 1;
                candidate.id = eventRepository.insert(candidate);
                event = candidate;
                created = true;
                packet.eventId = event.id;
                packetRepository.insert(packet);
            } else {
                mergeObservation(event, candidate);
                associatePacket(event, packet);
            }
            if (event.type == AprsEvent.MESSAGE_TYPE) notifyAndAcknowledge(event, created);
        } else {
            packetRepository.insert(packet);
        }

        if (digipeated != null) {
            recordTransmissionNow(event == null ? null : event.id, digipeated);
        }
        refreshEvents();
    }

    private void mergeObservation(AprsEvent event, AprsEvent observation) {
        event.lastSeenMs = observation.lastSeenMs;
        event.relayCallsign = observation.relayCallsign;
    }

    private void associatePacket(AprsEvent event, AprsPacket packet) {
        packet.eventId = event.id;
        packetRepository.insert(packet);
        event.packetCount++;
        event.lastSeenMs = Math.max(event.lastSeenMs, packet.timestampMs);
        eventRepository.update(event);
    }

    private void notifyAndAcknowledge(AprsEvent event, boolean notifyUser) {
        String callsign = callbacks.getCallsign();
        if (callsign == null || event.toCallsign == null
                || !event.toCallsign.trim().equalsIgnoreCase(callsign.trim())) return;
        if (notifyUser) callbacks.showNotification(event.fromCallsign + " messaged you", event.body);
        if (event.messageIdentifier != null && !event.messageIdentifier.trim().isEmpty()) {
            callbacks.sendAcknowledgement(event.fromCallsign.toUpperCase(Locale.ROOT),
                event.messageIdentifier, event.id);
        }
    }

    /** Records a transmitted packet under an existing event, or as unassociated transport data. */
    public void recordTransmission(Long eventId, APRSPacket packet, Long frequencyHz, byte[] rawAx25) {
        Transmission transmission = new Transmission(packet, frequencyHz, rawAx25);
        executor.execute(() -> {
            recordTransmissionNow(eventId, transmission);
            refreshEvents();
        });
    }

    private void recordTransmissionNow(Long eventId, Transmission transmission) {
        AprsPacket packet = physicalPacket(transmission.packet, AprsSource.TX_RF,
            transmission.frequencyHz, transmission.rawAx25);
        if (eventId == null) {
            packetRepository.insert(packet);
            return;
        }
        AprsEvent event = eventRepository.findById(eventId);
        if (event == null) {
            packetRepository.insert(packet);
        } else {
            associatePacket(event, packet);
        }
    }

    /** Runs due reliable-message retries and periodic beacon scheduling. */
    public void tick(long now) {
        executor.execute(() -> {
            boolean changed = false;
            for (AprsEvent event : eventRepository.loadDueReliableEvents(now)) {
                retryOrFail(event, now);
                changed = true;
            }
            if (positionBeaconingEnabled && now >= nextPositionBeaconAt) {
                nextPositionBeaconAt = now + BEACON_INTERVAL_MS;
                callbacks.requestPositionBeacon();
            }
            if (!HISTORY_ALL.equals(historyWindow) && now >= nextHistoryRefreshAt) {
                nextHistoryRefreshAt = now + HISTORY_REFRESH_INTERVAL_MS;
                changed = true;
            }
            if (changed) refreshEvents(now);
        });
    }

    public void setPositionBeaconingEnabled(boolean enabled, long now) {
        positionBeaconingEnabled = enabled;
        nextPositionBeaconAt = enabled ? now : 0;
    }

    private void retryOrFail(AprsEvent event, long now) {
        if (event.transmitAttempts >= RETRY_DELAYS_MS.length + 1) {
            event.deliveryState = AprsEvent.DELIVERY_FAILED;
            event.nextRetryAtMs = null;
        } else {
            Transmission transmission = callbacks.retryMessage(event);
            if (transmission == null) {
                event.nextRetryAtMs = now + RETRY_DELAYS_MS[0];
            } else {
                AprsPacket packet = physicalPacket(transmission.packet, AprsSource.TX_RF,
                    transmission.frequencyHz, transmission.rawAx25);
                packet.eventId = event.id;
                packetRepository.insert(packet);
                event.packetCount++;
                event.lastSeenMs = Math.max(event.lastSeenMs, packet.timestampMs);
                event.transmitAttempts++;
                event.nextRetryAtMs = event.transmitAttempts >= RETRY_DELAYS_MS.length + 1
                    ? now + FINAL_ACK_GRACE_MS
                    : now + RETRY_DELAYS_MS[event.transmitAttempts - 1];
            }
        }
        eventRepository.update(event);
    }

    /** Records a new outgoing chat event and its first transmitted packet. */
    public void recordOutgoingMessage(String from, String to, String text, String messageIdentifier,
                                      Long frequencyHz, APRSPacket packet, byte[] rawAx25) {
        long now = System.currentTimeMillis();
        AprsEvent event = new AprsEvent();
        event.type = AprsEvent.MESSAGE_TYPE;
        event.firstSeenMs = now;
        event.lastSeenMs = now;
        event.packetCount = 1;
        event.fromCallsign = from.toUpperCase(Locale.ROOT).trim();
        event.toCallsign = to.toUpperCase(Locale.ROOT).trim();
        event.body = text.trim();
        if (requiresAcknowledgement(to)) {
            event.messageIdentifier = messageIdentifier;
            event.deliveryState = AprsEvent.DELIVERY_PENDING;
            event.transmitAttempts = 1;
            event.nextRetryAtMs = now + RETRY_DELAYS_MS[0];
        }
        persistOutgoingEvent(event, packet, frequencyHz, rawAx25);
    }

    public static boolean requiresAcknowledgement(String destination) {
        if (destination == null) return false;
        String normalized = destination.trim().toUpperCase(Locale.ROOT);
        return !normalized.startsWith("BLN") && !normalized.equals("ALL")
            && !normalized.equals("QST") && !normalized.equals("CQ");
    }

    /** Records a new outgoing position event and its transmitted packet. */
    public void recordPositionBeacon(String callsign, double latitude, double longitude,
                                     Long frequencyHz, APRSPacket packet, byte[] rawAx25) {
        long now = System.currentTimeMillis();
        AprsEvent event = new AprsEvent();
        event.type = AprsEvent.POSITION_TYPE;
        event.firstSeenMs = now;
        event.lastSeenMs = now;
        event.packetCount = 1;
        event.fromCallsign = callsign;
        event.positionLat = latitude;
        event.positionLong = longitude;
        persistOutgoingEvent(event, packet, frequencyHz, rawAx25);
    }

    private void persistOutgoingEvent(AprsEvent event, APRSPacket frame, Long frequencyHz,
                                      byte[] rawAx25) {
        AprsPacket packet = physicalPacket(frame, AprsSource.TX_RF, frequencyHz, rawAx25);
        executor.execute(() -> {
            event.id = eventRepository.insert(event);
            packet.eventId = event.id;
            packetRepository.insert(packet);
            refreshEvents();
        });
    }

    private AprsPacket physicalPacket(APRSPacket frame, String source, Long frequencyHz,
                                      byte[] rawAx25) {
        AprsPacket packet = new AprsPacket();
        packet.timestampMs = System.currentTimeMillis();
        packet.source = source == null ? AprsSource.UNKNOWN : source;
        packet.frequencyHz = frequencyHz;
        packet.fromCallsign = frame.getSourceCall();
        packet.ax25Destination = frame.getDestinationCall();
        List<Digipeater> digipeaters = frame.getDigipeaters();
        packet.path = digipeaters == null || digipeaters.isEmpty() ? null
            : digipeaters.stream().map(Digipeater::toString).collect(Collectors.joining(","));
        packet.rawAx25 = rawAx25 == null ? null : Arrays.copyOf(rawAx25, rawAx25.length);
        return packet;
    }

    private ParsedEvent parseEvent(PacketContext context) {
        APRSPacket packet = context.packet;
        InformationField info = context.info;
        if (info.getDataTypeIdentifier() == ':') {
            MessagePacket message = new MessagePacket(info.getRawBytes(), packet.getDestinationCall());
            if (message.isAck() || message.isRej()) {
                return ParsedEvent.delivery(message.isAck(), message.isRej(), packet.getSourceCall(),
                    message.getTargetCallsign(), message.getMessageNumber());
            }
        }

        AprsEvent event = new AprsEvent();
        event.firstSeenMs = System.currentTimeMillis();
        event.lastSeenMs = event.firstSeenMs;
        event.fromCallsign = packet.getSourceCall();
        event.relayCallsign = context.relayCallsign;
        WeatherField weather = (WeatherField) info.getAprsData(APRSTypes.T_WX);
        PositionField position = (PositionField) info.getAprsData(APRSTypes.T_POSITION);
        ObjectField object = (ObjectField) info.getAprsData(APRSTypes.T_OBJECT);
        applyPosition(event, position);
        applyComment(event, packet, info, position, object, weather);
        applyPayload(event, packet, info, object, weather);
        if (packet.hasFault() || event.type == AprsEvent.UNKNOWN_TYPE) return null;
        event.dedupKey = dedupKey(event);
        return ParsedEvent.event(event);
    }

    private void applyPosition(AprsEvent event, PositionField position) {
        if (position == null) return;
        event.type = AprsEvent.POSITION_TYPE;
        event.positionLat = position.getPosition().getLatitude();
        event.positionLong = position.getPosition().getLongitude();
    }

    private void applyComment(AprsEvent event, APRSPacket packet, InformationField info,
                              PositionField position, ObjectField object, WeatherField weather) {
        String comment = firstComment(packet.getComment(), info.getComment());
        comment = firstComment(comment, position == null ? null : position.getComment());
        comment = firstComment(comment, object == null ? null : object.getComment());
        event.comment = firstComment(comment, weather == null ? null : weather.getComment());
    }

    private String firstComment(String preferred, String fallback) {
        return preferred == null || preferred.trim().isEmpty() ? fallback : preferred;
    }

    private void applyPayload(AprsEvent event, APRSPacket packet, InformationField info,
                              ObjectField object, WeatherField weather) {
        if (weather != null) {
            event.type = AprsEvent.WEATHER_TYPE;
            event.temperature = weather.getTemp() == null ? 0 : weather.getTemp();
            event.humidity = weather.getHumidity() == null ? 0 : weather.getHumidity();
            event.pressure = weather.getPressure() == null ? 0 : weather.getPressure();
            event.rain = weather.getRainLast24Hours() == null ? 0 : weather.getRainLast24Hours();
            event.snow = weather.getSnowfallLast24Hours() == null ? 0 : weather.getSnowfallLast24Hours();
            event.windForce = weather.getWindSpeed() == null ? 0 : weather.getWindSpeed();
            event.windDirection = weather.getWindDirection() == null ? ""
                : Utilities.degressToCardinal(weather.getWindDirection());
        } else if (info.getDataTypeIdentifier() == ';') {
            event.type = AprsEvent.OBJECT_TYPE;
            if (object != null) event.objectName = object.getObjectName();
        } else if (info.getDataTypeIdentifier() == ':') {
            event.type = AprsEvent.MESSAGE_TYPE;
            MessagePacket message = new MessagePacket(info.getRawBytes(), packet.getDestinationCall());
            event.toCallsign = message.getTargetCallsign();
            event.messageIdentifier = message.getMessageNumber();
            event.body = message.getMessageBody();
        }
    }

    private String dedupKey(AprsEvent event) {
        StringBuilder key = new StringBuilder();
        appendKey(key, String.valueOf(event.type));
        appendKey(key, event.fromCallsign);
        appendKey(key, event.toCallsign);
        appendKey(key, event.messageIdentifier);
        appendKey(key, event.body);
        appendKey(key, String.valueOf(event.positionLat));
        appendKey(key, String.valueOf(event.positionLong));
        appendKey(key, event.comment);
        appendKey(key, event.objectName);
        appendKey(key, String.valueOf(event.temperature));
        appendKey(key, String.valueOf(event.humidity));
        appendKey(key, String.valueOf(event.pressure));
        appendKey(key, String.valueOf(event.rain));
        appendKey(key, String.valueOf(event.snow));
        appendKey(key, String.valueOf(event.windForce));
        appendKey(key, event.windDirection);
        return key.toString();
    }

    private void appendKey(StringBuilder destination, String value) {
        if (value == null) {
            destination.append("-:");
        } else {
            destination.append(value.length()).append(':').append(value);
        }
    }

    private void refreshEvents() {
        refreshEvents(System.currentTimeMillis());
    }

    private void refreshEvents(long now) {
        long sinceMs = historyStartMs(historyWindow, now);
        String selectedDestination = destinationFilter;
        String localCallsign = callbacks.getCallsign();
        executor.execute(() -> {
            boolean mineOnly = DESTINATION_MINE.equals(selectedDestination);
            events.postValue(new ArrayList<>(eventRepository.loadEvents(
                sinceMs, normalizeCallsign(localCallsign), mineOnly)));
        });
    }

    private String normalizeHistoryWindow(String value) {
        if (HISTORY_ONE_DAY.equalsIgnoreCase(value)) return HISTORY_ONE_DAY;
        if (HISTORY_ONE_WEEK.equalsIgnoreCase(value)) return HISTORY_ONE_WEEK;
        if (HISTORY_TWO_WEEKS.equalsIgnoreCase(value)) return HISTORY_TWO_WEEKS;
        if (HISTORY_ONE_MONTH.equalsIgnoreCase(value)) return HISTORY_ONE_MONTH;
        return HISTORY_ALL;
    }

    private long historyStartMs(String window, long now) {
        switch (window) {
            case HISTORY_ONE_DAY:
                return now - DAY_MS;
            case HISTORY_ONE_WEEK:
                return now - 7 * DAY_MS;
            case HISTORY_TWO_WEEKS:
                return now - 14 * DAY_MS;
            case HISTORY_ONE_MONTH:
                return now - 30 * DAY_MS;
            case HISTORY_ALL:
            default:
                return 0L;
        }
    }

    private String normalizeCallsign(String callsign) {
        return callsign == null ? "" : callsign.trim().toUpperCase(Locale.ROOT);
    }

    private Transmission maybeDigipeat(APRSPacket packet) {
        String localCallsign = callbacks.getCallsign();
        if (!digipeatingEnabled || localCallsign == null || localCallsign.trim().isEmpty()) return null;
        String key = digipeatKey(packet);
        long now = System.currentTimeMillis();
        pruneDigipeatCache(digipeatInputCache, now);
        if (digipeatInputCache.containsKey(key)) return null;
        List<Digipeater> digis = packet.getDigipeaters();
        if (digis == null || digis.isEmpty()) return null;
        int index = firstUnusedDigipeater(digis);
        if (index < 0) return null;
        Digipeater next = digis.get(index);
        String baseCall = APRSPacket.getBaseCall(next.getCallsign());
        int ssid = parseSsid(next);
        boolean ours = baseCall.equalsIgnoreCase(APRSPacket.getBaseCall(localCallsign));
        boolean wide1 = baseCall.equalsIgnoreCase("WIDE1") && ssid >= 1 && ssid <= 2;
        if (!ours && !wide1) return null;
        List<Digipeater> replacement = new ArrayList<>(digis);
        if (ours) {
            replacement.set(index, usedDigipeater(next.getCallsign()));
        } else if (ssid == 1) {
            replacement.set(index, usedDigipeater(localCallsign));
        } else {
            replacement.set(index, new Digipeater(baseCall + "-1"));
            replacement.add(index, usedDigipeater(localCallsign));
        }
        APRSPacket retransmit = new APRSPacket(packet.getSourceCall(), packet.getDestinationCall(),
            replacement, packet.getPayload().getRawBytes());
        retransmit.setComment(packet.getComment());
        Transmission transmission = callbacks.transmitDigipeatedPacket(retransmit);
        if (transmission != null) {
            digipeatInputCache.put(key, now);
            digipeatOutputCache.put(digipeatKey(retransmit), now);
        }
        return transmission;
    }

    private boolean isRecentlyDigipeated(APRSPacket packet) {
        long now = System.currentTimeMillis();
        pruneDigipeatCache(digipeatOutputCache, now);
        Long previous = digipeatOutputCache.get(digipeatKey(packet));
        return previous != null && now - previous < DIGIPEAT_DEDUP_MS;
    }

    private void pruneDigipeatCache(Map<String, Long> cache, long now) {
        cache.entrySet().removeIf(entry -> now - entry.getValue() >= DIGIPEAT_DEDUP_MS);
    }

    private String digipeatKey(APRSPacket packet) {
        String path = packet.getDigipeaters() == null ? "" : packet.getDigipeaters().stream()
            .map(Digipeater::toString).collect(Collectors.joining(","));
        return packet.getSourceCall() + "|" + packet.getDestinationCall() + "|" + path + "|"
            + Base64.getEncoder().encodeToString(packet.getPayload().getRawBytes());
    }

    private int firstUnusedDigipeater(List<Digipeater> digis) {
        for (int i = 0; i < digis.size(); i++) {
            if (!digis.get(i).isUsed()) return i;
        }
        return -1;
    }

    private int parseSsid(Digipeater digipeater) {
        try {
            return Integer.parseInt(APRSPacket.getSsid(digipeater.toString()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
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

    private static final class ParsedEvent {
        private final AprsEvent event;
        private final boolean acknowledgement;
        private final boolean rejection;
        private final String fromCallsign;
        private final String targetCallsign;
        private final String messageIdentifier;

        private ParsedEvent(AprsEvent event, boolean acknowledgement, boolean rejection,
                            String fromCallsign, String targetCallsign, String messageIdentifier) {
            this.event = event;
            this.acknowledgement = acknowledgement;
            this.rejection = rejection;
            this.fromCallsign = fromCallsign;
            this.targetCallsign = targetCallsign;
            this.messageIdentifier = messageIdentifier;
        }

        private static ParsedEvent event(AprsEvent event) {
            return new ParsedEvent(event, false, false, null, null, null);
        }

        private static ParsedEvent delivery(boolean acknowledgement, boolean rejection,
                                            String from, String target, String identifier) {
            return new ParsedEvent(null, acknowledgement, rejection, from, target, identifier);
        }
    }
}
