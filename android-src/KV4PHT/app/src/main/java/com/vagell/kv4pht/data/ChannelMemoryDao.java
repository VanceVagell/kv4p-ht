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
