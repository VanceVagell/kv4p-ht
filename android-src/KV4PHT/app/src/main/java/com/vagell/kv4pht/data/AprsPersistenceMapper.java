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

import io.github.dkaukov.aprs.AprsEvent;
import io.github.dkaukov.aprs.AprsPacket;

/** Maps pure APRS domain objects to the schema-compatible Room entities. */
final class AprsPersistenceMapper {
    private AprsPersistenceMapper() { }

    static AprsEventEntity toEntity(AprsEvent source) {
        AprsEventEntity target = new AprsEventEntity();
        copy(source, target);
        return target;
    }

    static AprsEvent toDomain(AprsEventEntity source) {
        if (source == null) return null;
        AprsEvent.AprsEventBuilder target = AprsEvent.builder();
        copy(source, target);
        return target.build();
    }

    static AprsPacketEntity toEntity(AprsPacket source) {
        AprsPacketEntity target = new AprsPacketEntity();
        target.id = source.getId(); target.eventId = source.getEventId(); target.timestampMs = source.getTimestampMs();
        target.source = source.getSource(); target.frequencyHz = source.getFrequencyHz();
        target.fromCallsign = source.getFromCallsign(); target.ax25Destination = source.getAx25Destination();
        target.path = source.getPath(); target.rawAx25 = source.getRawAx25(); target.rawTnc2 = source.getRawTnc2();
        return target;
    }

    static AprsPacket toDomain(AprsPacketEntity source) {
        if (source == null) return null;
        return AprsPacket.builder().id(source.id).eventId(source.eventId)
            .timestampMs(source.timestampMs).source(source.source).frequencyHz(source.frequencyHz)
            .fromCallsign(source.fromCallsign).ax25Destination(source.ax25Destination)
            .path(source.path).rawAx25(source.rawAx25).rawTnc2(source.rawTnc2).build();
    }

    static void copy(AprsEvent source, AprsEventEntity target) {
        target.id = source.getId(); target.type = source.getType(); target.firstSeenMs = source.getFirstSeenMs();
        target.lastSeenMs = source.getLastSeenMs(); target.packetCount = source.getPacketCount();
        target.digipeated = source.isDigipeated(); target.internetOnly = source.isInternetOnly();
        target.dedupKey = source.getDedupKey(); target.fromCallsign = source.getFromCallsign();
        target.toCallsign = source.getToCallsign(); target.messageIdentifier = source.getMessageIdentifier();
        target.body = source.getBody(); target.positionLat = source.getPositionLat();
        target.positionLong = source.getPositionLong(); target.comment = source.getComment();
        target.objectName = source.getObjectName(); target.temperature = source.getTemperature();
        target.humidity = source.getHumidity(); target.pressure = source.getPressure(); target.rain = source.getRain();
        target.snow = source.getSnow(); target.windForce = source.getWindForce();
        target.windDirection = source.getWindDirection(); target.relayCallsign = source.getRelayCallsign();
        target.deliveryState = source.getDeliveryState(); target.transmitAttempts = source.getTransmitAttempts();
        target.nextRetryAtMs = source.getNextRetryAtMs();
    }

    static void copy(AprsEventEntity source, AprsEvent.AprsEventBuilder target) {
        target.id(source.id); target.type(source.type); target.firstSeenMs(source.firstSeenMs);
        target.lastSeenMs(source.lastSeenMs); target.packetCount(source.packetCount);
        target.digipeated(source.digipeated); target.internetOnly(source.internetOnly);
        target.dedupKey(source.dedupKey); target.fromCallsign(source.fromCallsign);
        target.toCallsign(source.toCallsign); target.messageIdentifier(source.messageIdentifier);
        target.body(source.body); target.positionLat(source.positionLat);
        target.positionLong(source.positionLong); target.comment(source.comment);
        target.objectName(source.objectName); target.temperature(source.temperature);
        target.humidity(source.humidity); target.pressure(source.pressure); target.rain(source.rain);
        target.snow(source.snow); target.windForce(source.windForce);
        target.windDirection(source.windDirection); target.relayCallsign(source.relayCallsign);
        target.deliveryState(source.deliveryState); target.transmitAttempts(source.transmitAttempts);
        target.nextRetryAtMs(source.nextRetryAtMs);
    }
}
