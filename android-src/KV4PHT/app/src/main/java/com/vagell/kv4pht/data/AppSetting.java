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

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import lombok.Getter;

@Entity(tableName = "app_settings")
public class AppSetting {
    public AppSetting() {};

    public AppSetting(@NonNull String name, String value) {
        this.name = name;
        this.value = value;
    }

    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "name")
    @Getter
    @NonNull
    public String name;

    @ColumnInfo(name = "value")
    @Getter
    public String value;
}
