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

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface AprsPacketDao {
    @Query("SELECT * FROM aprs_packets ORDER BY timestamp_ms")
    List<AprsPacketEntity> getAll();

    @Query("SELECT * FROM aprs_packets WHERE event_id = :eventId AND source = 'TX_RF' "
        + "ORDER BY id LIMIT 1")
    AprsPacketEntity getInitialRfTransmission(long eventId);

    @Insert
    long insert(AprsPacketEntity packet);
}
