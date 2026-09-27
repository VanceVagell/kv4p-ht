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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.github.dkaukov.aprs.AprsEvent;
import io.github.dkaukov.aprs.AprsPacket;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Test;

/** Checks the Android persistence boundary against the snapshot-based library API. */
public class AprsPersistenceMapperTest {
    @Test public void everyEventColumnSurvivesAccessorRoundTrip() throws Exception {
        AprsEventEntity original = new AprsEventEntity();
        // Exercise every persisted column with a non-default value.
        for (Field field : AprsEventEntity.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (type == String.class) field.set(original, field.getName());
            else if (type == boolean.class) field.setBoolean(original, true);
            else if (type == int.class) field.setInt(original, 7);
            else if (type == long.class) field.setLong(original, 12345L);
            else if (type == double.class) field.setDouble(original, 12.5);
            else if (type == Long.class) field.set(original, 67890L);
            else throw new AssertionError("Unhandled column type: " + field);
        }
        AprsEvent domain = AprsPersistenceMapper.toDomain(original);
        AprsEventEntity restored = AprsPersistenceMapper.toEntity(domain);
        for (Field field : AprsEventEntity.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertEquals(field.getName(), field.get(original), field.get(restored));
            }
        }
        domain = domain.toBuilder().deliveryState(AprsEvent.DELIVERY_DELIVERED).build();
        assertEquals(AprsEvent.DELIVERY_DELIVERED,
            AprsPersistenceMapper.toEntity(domain).deliveryState);
        assertEquals(7, original.deliveryState);
        domain = domain.toBuilder().nextRetryAtMs(null).build();
        assertNull(AprsPersistenceMapper.toEntity(domain).nextRetryAtMs);
        assertNull(AprsPersistenceMapper.toDomain((AprsEventEntity) null));
    }

    @Test public void packetMappingKeepsMetadataAndIndependentWireBytes() {
        AprsPacket packet = AprsPacket.builder()
            .id(1L)
            .eventId(2L)
            .timestampMs(3L)
            .source("RX_RF")
            .frequencyHz(144390000L)
            .fromCallsign("VK3ABC")
            .ax25Destination("APRS")
            .path("WIDE1-1")
            .rawTnc2("VK3ABC>APRS:>test")
            .rawAx25(new byte[] {1, 2, 3})
            .build();
        AprsPacketEntity entity = AprsPersistenceMapper.toEntity(packet);
        assertEquals(1L, entity.id);
        assertEquals(Long.valueOf(2L), entity.eventId);
        assertEquals(3L, entity.timestampMs);
        assertEquals("RX_RF", entity.source);
        assertEquals(Long.valueOf(144390000L), entity.frequencyHz);
        assertEquals("VK3ABC", entity.fromCallsign);
        assertEquals("APRS", entity.ax25Destination);
        assertEquals("WIDE1-1", entity.path);
        assertEquals("VK3ABC>APRS:>test", entity.rawTnc2);
        AprsPacket restored = AprsPersistenceMapper.toDomain(entity);
        assertEquals(packet, restored);
        entity.rawAx25[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, packet.getRawAx25());
        assertArrayEquals(new byte[] {1, 2, 3}, restored.getRawAx25());
        AprsPacket replacement = packet.toBuilder().rawAx25(new byte[] {4}).build();
        assertArrayEquals(new byte[] {1, 2, 3}, packet.getRawAx25());
        assertArrayEquals(new byte[] {4}, replacement.getRawAx25());
        assertArrayEquals(new byte[] {9, 2, 3}, entity.rawAx25);
        assertNull(AprsPersistenceMapper.toDomain((AprsPacketEntity) null));
    }
}
