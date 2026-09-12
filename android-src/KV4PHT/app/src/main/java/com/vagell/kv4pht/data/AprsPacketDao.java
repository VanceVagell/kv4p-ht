package com.vagell.kv4pht.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface AprsPacketDao {
    @Query("SELECT * FROM aprs_packets ORDER BY timestamp_ms")
    List<AprsPacket> getAll();

    @Insert
    long insert(AprsPacket packet);
}
