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

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "aprs_messages")
public class APRSMessage {
    public static final int UNKNOWN_TYPE = 0;
    public static final int MESSAGE_TYPE = 1;
    public static final int OBJECT_TYPE = 2;
    public static final int POSITION_TYPE = 3;
    public static final int WEATHER_TYPE = 4;

    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "type", defaultValue = "" + UNKNOWN_TYPE)
    public int type;

    /*
     * Fields relevant to all APRS message types.
     */
    @ColumnInfo(name = "from_callsign")
    public String fromCallsign;

    @ColumnInfo(name = "to_callsign")
    public String toCallsign;

    @ColumnInfo(name = "timestamp")
    public long timestamp; // Seconds since epoch in UTC

    @ColumnInfo(name = "position_lat")
    public double positionLat;

    @ColumnInfo(name = "position_long")
    public double positionLong;

    @ColumnInfo(name = "comment")
    public String comment;

    /*
     * Fields for APRS objects only.
     */
    @ColumnInfo(name = "obj_name")
    public String objName;

    /*
     * Fields for APRS messages (e.g. chat) only.
     */
    @ColumnInfo(name = "ack")
    public boolean wasAcknowledged;

    @ColumnInfo(name = "message_num")
    public int msgNum;

    @ColumnInfo(name = "msg_body")
    public String msgBody;

    /*
     * Fields for APRS weather only.
     */
    @ColumnInfo(name = "temperature")
    public double temperature;

    @ColumnInfo(name = "humidity")
    public double humidity;

    @ColumnInfo(name = "pressure")
    public double pressure;

    @ColumnInfo(name = "rain")
    public double rain;

    @ColumnInfo(name = "snow")
    public double snow;

    @ColumnInfo(name = "wind_force")
    public int windForce;

    @ColumnInfo(name = "wind_dir")
    public String windDir;
}
