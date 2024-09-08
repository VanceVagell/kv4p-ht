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

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
/**
 * 
 * @author johng
 *  This class represents a complete APRS AX.25 packet, as found in a TNC2-style string:
 *  SOURCE>DESTIN,VIA,VIA:payload
 */
public class APRSPacket implements Serializable {
    private static final long serialVersionUID = 1L;
	private Date receivedTimestamp = null;
    private String originalString;
	private String sourceCall;
    private String destinationCall;
    private ArrayList<Digipeater> digipeaters;
    private char dti;
    private InformationField aprsInformation;
    private boolean hasFault;
	private String comment;

    static final String REGEX_PATH_ALIASES = "^(WIDE|TRACE|RELAY)\\d*$";
    
    public APRSPacket( String source, String destination, ArrayList<Digipeater> digipeaters, byte[] body) {
		receivedTimestamp = new Date(System.currentTimeMillis());
        this.sourceCall=source.toUpperCase();
        this.destinationCall=destination.toUpperCase();
        if ( digipeaters == null ) {
        	Digipeater aprsIs = new Digipeater("TCPIP*");
        	this.digipeaters = new ArrayList<Digipeater>();
        	this.digipeaters.add(aprsIs);
        } else {
        	this.digipeaters = digipeaters;
        }
		this.dti = (char)body[0];
        this.aprsInformation = new InformationField(body);
    }
    
    
	/** 
	 * @param callsign
	 * @return String
	 */
	public static final String getBaseCall(String callsign) {
    	int sepIdx = callsign.indexOf('-');
    	if ( sepIdx > -1 ) {
    		return callsign.substring(0,sepIdx);
    	} else {
    		return callsign;
    	}
    }
    
    
	/** 
	 * @param callsign
	 * @return String
	 */
	public static final String getSsid(String callsign) {
    	int sepIdx = callsign.indexOf('-');
    	if ( sepIdx > -1 ) {
    		return callsign.substring(sepIdx+1);
    	} else {
    		return "0";
    	}
    }
    
    public String getIgate() {
    	for ( int i=0; i<digipeaters.size(); i++) {
    		Digipeater d = digipeaters.get(i);
    		// I'm not sure I'm treating these correctly (poor understanding of the
    		// Q-constructs on my part).  For now, I'm saying that call sign AFTER a 
    		// q-construct is the I-gate.
    		if ( d.getCallsign().equalsIgnoreCase("qar") && i<digipeaters.size()-1 ) {
    			return digipeaters.get(i+1).toString();
    		}
    		if ( d.getCallsign().equalsIgnoreCase("qas") && i<digipeaters.size()-1 ) {
    			return digipeaters.get(i+1).toString();
    		}
    		if ( d.getCallsign().equalsIgnoreCase("qac") && i<digipeaters.size()-1 ) {
    			return digipeaters.get(i+1).toString();
    		}
    		if ( d.getCallsign().equalsIgnoreCase("qao") && i<digipeaters.size()-1 ) {
    			return digipeaters.get(i+1).toString();
    		}
    	}
    	return "";
    }

    /**
     * @return the source
     */
    public String getSourceCall() {
        return sourceCall;
    }

    /**
     * @return the destination
     */
    public String getDestinationCall() {
        return destinationCall;
    }

    /**
     * @return the digipeaters
     */
    public ArrayList<Digipeater> getDigipeaters() {
        return digipeaters;
    }
    
    public void setDigipeaters(ArrayList<Digipeater> newDigis) {
	    digipeaters = newDigis;
    }

    /**
     * @return the last digipeater in the path marked as used (with '*') or null.
     */
    public String getLastUsedDigi() {
        for (int i=digipeaters.size()-1; i>=0; i--) {
            Digipeater d = digipeaters.get(i);
	    String call = d.getCallsign();
            if (d.isUsed() && !call.matches(REGEX_PATH_ALIASES))
                return call;
        }
        return null;
    }

    public String getDigiString() {
        StringBuilder sb = new StringBuilder();
		boolean first=true;
        for ( Digipeater digi : digipeaters ) {
			if ( first ) {
            	sb.append(digi.toString());
				first=false;
			} else 
				sb.append(","+digi.toString());
        }
        return sb.toString();
    }

    /**
     * @return the dti
     */
    public char getDti() {
        return dti;
    }

    /**
     * @return the aprsInformation
     */
    public InformationField getAprsInformation() {
        return aprsInformation;
    }
    public boolean isAprs() {
    	return true;
    }

	/**
	 * @return the hasFault
	 */
	public boolean hasFault() {
		return ( this.hasFault | aprsInformation.hasFault() );
	}

	/**
	 * @param hasFault the hasFault to set
	 */
	public void setHasFault(boolean hasFault) {
		this.hasFault = hasFault;
	}

	/**
	 * @return the originalString
	 */
	public final String getOriginalString() {
		return originalString;
	}

	/**
	 * @param originalString the originalString to set
	 */
	public final void setOriginalString(String originalString) {
		this.originalString = originalString;
	}

		public void setInfoField(InformationField infoField) {
		this.aprsInformation = infoField;
	}

	public final void setComment(String comment) {
		this.comment = comment;
	}

	public final String getComment() {
		return this.comment;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer("-------------------------------\n");
		sb.append(sourceCall+">"+destinationCall+"\n");
		sb.append("Via Digis: "+getDigiString()+"\n");
		sb.append(aprsInformation.toString());
		return sb.toString();
	}

	public byte[] toAX25Frame() throws IllegalArgumentException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		// write AX.25 header
		// dest
		byte[] dest = new Digipeater(destinationCall + "*").toAX25();
		baos.write(dest, 0, dest.length);
		// src
		byte[] src = new Digipeater(sourceCall).toAX25();
		// last byte of last address is |=1
		if (digipeaters.size() == 0)
			src[6] |= 1;
		baos.write(src, 0, src.length);
		// digipeater list
		for (int i = 0; i < digipeaters.size(); i++) {
			byte[] d = digipeaters.get(i).toAX25();
			// last byte of last digi is |=1
			if (i == digipeaters.size() - 1)
				d[6] |= 1;
			baos.write(d, 0, 7);
		}
		// control: UI-frame, poll-bit set
		baos.write(0x03);
		// pid: 0xF0 - no layer 3 protocol
		baos.write(0xF0);
		// write content
		byte[] content = aprsInformation.getRawBytes();
		baos.write(content, 0, content.length);
		return baos.toByteArray();
	}

	public Date getRecevedTimestamp() {
		return this.receivedTimestamp;
	}
}

