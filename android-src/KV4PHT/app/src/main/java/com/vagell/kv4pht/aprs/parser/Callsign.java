/*
 * AVRS - http://avrs.sourceforge.net/
 *
 * Copyright (C) 2012 Georg Lukas, DO1GL
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

import java.io.Serializable;

/**
 * 
 * @author do1gl
 * This class represents a callsign with (optional) ssid
 */
public class Callsign implements Serializable {
	private static final long serialVersionUID = 1L;
	protected String callsign;
	protected String ssid;

	public Callsign(String call) {
		String[] callssid = call.split("-");
		this.callsign = callssid[0].toUpperCase();
		if (callssid.length > 1) {
			this.ssid = callssid[1];
		} else {
			this.ssid="";
		}
	}

	public Callsign(byte[] data, int offset) {
		byte[] shifted = new byte[6];
		byte ssidbyte = data[offset + 6];
		for (int i = 0; i < 6; i++)
			shifted[i] = (byte)((data[offset + i]&0xff) >> 1);
		this.callsign = new String(shifted, 0, 6).trim();
		int ssidval = (ssidbyte & 0x1e) >> 1;
		if (ssidval != 0)
			this.ssid = "" + ssidval;
		else this.ssid = "";
	}

    /**
     * @return the callsign
     */
    public String getCallsign() {
        return callsign;
    }

    /**
     * @param callsign the callsign to set
     */
    public void setCallsign(String callsign) {
        this.callsign = callsign.toUpperCase();
    }

    /**
     * @return the ssid
     */
    public String getSsid() {
        return ssid;
    }

    /**
     * @param ssid the ssid to set
     */
    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    
	/** 
	 * @return String
	 */
	@Override
    public String toString() {
        return callsign + (ssid == "" ? "" : "-" + ssid);
    }

    public byte[] toAX25() throws IllegalArgumentException {
        byte[] callbytes = callsign.getBytes();
        byte[] ax25 = new byte[7];
	// shift " " by one
	java.util.Arrays.fill(ax25, (byte)0x40);
	if (callbytes.length > 6) {
		throw new IllegalArgumentException("Callsign " + callsign + " is too long for AX.25!");
	}
	for (int i = 0; i < callbytes.length; i++) {
		ax25[i] = (byte)(callbytes[i] << 1);
	}
	int ssidval = 0;
	try {
		ssidval = Integer.parseInt(ssid);
	} catch (NumberFormatException e) {
		// we ignore that for now.
	}
	// ssid byte: u11ssss0
	ax25[6] = (byte) (0x60 | ((ssidval*2) & 0x1e));
	return ax25;
    }
}
