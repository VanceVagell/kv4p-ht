package com.vagell.kv4pht.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ChannelMemoryDao {
    @Query("SELECT * FROM channel_memories")
    List<ChannelMemory> getAll();

    @Query("SELECT DISTINCT `group` FROM channel_memories ")
    List<String> getGroups();

    @Query("SELECT * FROM channel_memories WHERE `group` LIKE :group")
    List<ChannelMemory> getByGroup(String group);

    @Query("SELECT * FROM channel_memories WHERE `memoryId` = :memoryId LIMIT 1")
    ChannelMemory getById(int memoryId);

    @Insert
    void insertAll(ChannelMemory... channelMemories);

    @Delete
    void delete(ChannelMemory channelMemory);

    @Update
    void update(ChannelMemory channelMemory);
}
