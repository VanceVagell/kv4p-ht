package com.vagell.kv4pht.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_settings")
public class AppSetting {
    public AppSetting() {};

    public AppSetting(@NonNull String name, String value) {
        this.name = name;
        this.value = value;
    }

    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "name")
    @NonNull
    public String name;

    @ColumnInfo(name = "value")
    public String value;
}
