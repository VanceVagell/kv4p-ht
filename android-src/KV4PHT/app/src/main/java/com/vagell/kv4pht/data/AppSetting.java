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
    public static final String SETTING_LAST_GROUP = "lastGroup";
    public static final String SETTING_LAST_MEMORY_ID = "lastMemoryId";
    public static final String SETTING_LAST_FREQ = "lastFreq";
    public static final String SETTING_MIN_2_M_TX_FREQ = "min2mTxFreq";
    public static final String SETTING_MAX_2_M_TX_FREQ = "max2mTxFreq";
    public static final String SETTING_MIN_70_CM_TX_FREQ = "min70cmTxFreq";
    public static final String SETTING_MAX_70_CM_TX_FREQ = "max70cmTxFreq";
    public static final String SETTING_RF_POWER = "rfPower";
    public static final String SETTING_BANDWIDTH = "bandwidth";
    public static final String SETTING_MIC_GAIN_BOOST = "micGainBoost";
    public static final String SETTING_SQUELCH = "squelch";
    public static final String SETTING_EMPHASIS = "emphasis";
    public static final String SETTING_HIGHPASS = "highpass";
    public static final String SETTING_LOWPASS = "lowpass";
    public static final String SETTING_DISABLE_ANIMATIONS = "disableAnimations";
    public static final String SETTING_APRS_POSITION_ACCURACY = "aprsPositionAccuracy";
    public static final String SETTING_APRS_BEACON_POSITION = "aprsBeaconPosition";
    public static final String SETTING_CALLSIGN = "callsign";
    public static final String SETTING_STICKY_PTT = "stickyPTT";

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
