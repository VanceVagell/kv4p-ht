package com.vagell.kv4pht.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AppSettingDao {
    @Query("SELECT * FROM app_settings")
    List<AppSetting> getAll();

    @Query("SELECT * FROM app_settings WHERE `name` LIKE :name LIMIT 1")
    AppSetting getByName(String name);

    @Insert
    void insertAll(AppSetting... appSettings);

    @Delete
    void delete(AppSetting appSettings);
}
