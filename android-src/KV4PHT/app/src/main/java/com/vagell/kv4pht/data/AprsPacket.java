package com.vagell.kv4pht.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Immutable transport history for one received or transmitted APRS packet. */
@Entity(
    tableName = "aprs_packets",
    indices = {@Index("event_id"), @Index("timestamp_ms"), @Index("from_callsign")}
)
public class AprsPacket {
    @PrimaryKey(autoGenerate = true)
    public long id;
    @ColumnInfo(name = "event_id")
    public Long eventId;
    @ColumnInfo(name = "timestamp_ms")
    public long timestampMs;
    @ColumnInfo(name = "source", defaultValue = "'UNKNOWN'")
    public String source;
    /** RF frequency in Hz, or {@code null} for non-RF sources. */
    @ColumnInfo(name = "frequency_hz")
    public Long frequencyHz;
    @ColumnInfo(name = "from_callsign")
    public String fromCallsign;
    @ColumnInfo(name = "ax25_destination")
    public String ax25Destination;
    /** Comma-separated AX.25 digipeater path, retaining repeated-hop markers. */
    @ColumnInfo(name = "path")
    public String path;
    /** Exact AX.25 frame bytes without FCS, KISS, or serial transport framing. */
    @ColumnInfo(name = "raw_ax25")
    public byte[] rawAx25;
}
