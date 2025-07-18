/*
 * AVRS - http://avrs.sourceforge.net/
 *
 * Copyright (C) 2011 John Gorkos, AB0OO
 *
 * AVRS is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 *
 * AVRS is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AVRS; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA
 */
package com.vagell.kv4pht.aprs.parser;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author johng
 * This class represents a complete APRS AX.25 packet, as found in a TNC2-style string:
 * SOURCE>DESTIN,VIA,VIA:payload
 */
public class APRSPacket implements Serializable {

	public static final Set<String> Q_CONSTRUCTS = Set.of("qar", "qas", "qac", "qao");
	private static final long serialVersionUID = 1L;

    @Getter
    private final Date receivedTimestamp;

    @Getter
    @Setter
    private String originalString;

    private final List<Digipeater> digipeaters;

    @Getter
    private final String sourceCall;

    @Getter
    private final String destinationCall;

    @Getter
    private final char dti;

    @Getter
    private final InformationField payload;

    @Setter
    private boolean hasFault;

    private String comment;

    static final String REGEX_PATH_ALIASES = "^(WIDE|TRACE|RELAY)\\d*$";

    public APRSPacket(String source, String destination, List<Digipeater> digipeaters, byte[] payload) {
        receivedTimestamp = new Date(System.currentTimeMillis());
        this.sourceCall = source.toUpperCase();
        this.destinationCall = destination.toUpperCase();
		this.digipeaters = Optional.ofNullable(digipeaters).orElse(List.of(new Digipeater("TCPIP*")));
        this.dti = (char) payload[0];
        this.payload = new InformationField(payload);
    }

    public static String getBaseCall(String callsign) {
        int sepIdx = callsign.indexOf('-');
        if (sepIdx > -1) {
            return callsign.substring(0, sepIdx);
        } else {
            return callsign;
        }
    }

    public static String getSsid(String callsign) {
        int sepIdx = callsign.indexOf('-');
        if (sepIdx > -1) {
            return callsign.substring(sepIdx + 1);
        } else {
            return "0";
        }
    }

	public String getIgate() {
		// I'm not sure if I'm treating these correctly (poor understanding of the
		// Q-constructs on my part).  For now, I'm saying that call sign AFTER a
		// q-construct is the I-gate.
		for (int i = 0; i < digipeaters.size() - 1; i++) {
            if (Q_CONSTRUCTS.contains(digipeaters.get(i).getCallsign().toLowerCase())) {
				return digipeaters.get(i + 1).toString();
			}
		}
		return "";
	}

	/**
     * @return the last digipeater in the path marked as used (with '*') or null.
     */
    public String getLastUsedDigi() {
        return IntStream.range(0, digipeaters.size())
            .mapToObj(i -> digipeaters.get(digipeaters.size() - 1 - i))
            .filter(d -> d.isUsed() && !d.getCallsign().matches(REGEX_PATH_ALIASES))
            .map(Digipeater::getCallsign)
            .findFirst()
            .orElse(null);
    }

    public String getDigiString() {
        return digipeaters.stream()
            .map(Digipeater::toString)
            .collect(Collectors.joining(","));
    }

    public boolean isAprs() {
        return true;
    }

    /**
     * @return the hasFault
     */
    public boolean hasFault() {
        return (this.hasFault || payload.hasFault());
    }

    public final void setComment(String comment) {
        this.comment = comment;
    }

    public final String getComment() {
        return this.comment;
    }

    @Override
    public @NotNull String toString() {
        return String.format(
            "-------------------------------\n%s>%s\nVia Digis: %s\n%s",
            sourceCall,
            destinationCall,
            getDigiString(),
			payload
        );
    }

	public byte[] toAX25Frame() throws IllegalArgumentException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		// Destination address (with * to mark end of first address)
		byte[] dest = new Digipeater(destinationCall + "*").toAX25();
		byteArrayOutputStream.write(dest, 0, dest.length);
		// Source address
		byte[] src = new Digipeater(sourceCall).toAX25();
		if (digipeaters.isEmpty()) {
			src[6] |= 0x01; // Mark as last address if no digipeaters
		}
		byteArrayOutputStream.write(src, 0, src.length);
		// Digipeater path
		for (int i = 0; i < digipeaters.size(); i++) {
			byte[] digi = digipeaters.get(i).toAX25();
			if (i == digipeaters.size() - 1) {
				digi[6] |= 0x01; // Mark last digipeater
			}
			byteArrayOutputStream.write(digi, 0, 7); // AX.25 address is always 7 bytes
		}
		// Control (0x03 = UI-frame), PID (0xF0 = no layer 3 protocol)
		byteArrayOutputStream.write(0x03);
		byteArrayOutputStream.write(0xF0);
		// Payload
		byte[] payload = this.payload.getRawBytes();
		byteArrayOutputStream.write(payload, 0, payload.length);
		return byteArrayOutputStream.toByteArray();
	}
}

