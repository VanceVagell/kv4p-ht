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

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 
 * @author johng
 *  This class represents the "payload" of a TNC2 style AX25 packet, stripped of the source call,
 *  destination call, and digi VIAs.  Note this class is abstract:  only subclasses of it may be 
 *  instantiated.  Per the APRS spec, these classes include Position, Direction Finding, Objects
 *  and Items, Weather, Telemetry, Messages, Bulletins, Annoucements, Queries, Responses, Statuses,
 *  and User-defined Others.
 */
public class InformationField implements Serializable {
	private static final long serialVersionUID = 1L;
    private final long createTimestamp = System.currentTimeMillis();
	private char dataTypeIdentifier;
    protected byte[] rawBytes;
	protected boolean hasFault = false;
    protected boolean canMessage = false;
    Map<APRSTypes,APRSData> dataFields;
    DataExtension extension = null;
	protected String comment = "";

    public InformationField() {
    }
    
    public InformationField( byte[] rawBytes ) {
        if ( rawBytes.length < 1 ) {
            System.err.println("Parse error:  zero length information field");
        }
        this.rawBytes = rawBytes;
        this.dataTypeIdentifier = (char)rawBytes[0];
        this.dataFields = new HashMap<>();
        switch ( dataTypeIdentifier ) {
       	case '@' :
        	case '=' :
        	case '\'':
        	case ':' : this.canMessage = true;
            default: this.canMessage = false;
        }
    }
    
    
    /** 
     * @return char
     */
    public char getDataTypeIdentifier() {
        return dataTypeIdentifier;
    }

    
    /** 
     * @param dti
     */
    public void setDataTypeIdentifier(char dti) {
        this.dataTypeIdentifier = dti;
    }

    /**
     * @return the rawBytes
     */
    public byte[] getRawBytes() {
	    if (rawBytes != null)
		    return rawBytes;
	    else
		    return toString().getBytes();
    }
    
    
    /** 
     * @param start
     * @param end
     * @return byte[]
     */
    public byte[] getBytes(int start, int end) {
        byte[] returnArray = new byte[end-start];
        System.arraycopy(getRawBytes(), start, returnArray, 0, end-start);
        return returnArray;
    }
    
	/**
	 * @return the comment string which was embedded in the packet
	 */
    public String getComment() {
        return comment;
    }
    
    
    /** 
     * @return String
     */
    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("Raw Bytes:\t"+new String(rawBytes)+"\n");
        sb.append("Data Type Identifier: "+dataTypeIdentifier+"\n");
        sb.append("Create Timestamp:\t"+ (new java.util.Date(createTimestamp).toString() )+"\n");
        sb.append("Comment:  "+this.comment+"\n");
        for ( APRSData df : dataFields.values() ) {
            sb.append("Class "+df.getClass().getName()+"\n");
            sb.append(df.toString());
        }

        return sb.toString();
    }
	/**
	 * @return the hasFault
	 */
	public boolean hasFault() {
        boolean faultFound = this.hasFault;
        for ( APRSData data : dataFields.values() )  {
            faultFound = faultFound | data.hasFault();
        }
		return faultFound;
	}

	/**
	 * @return the extension
	 */
	public final DataExtension getExtension() {
		return extension;
	}

    
    /** 
     * @return long
     */
    public final long getCreateTimestamp() {
        return this.createTimestamp;
    }

	
    /** 
     * @return Mapping of APRSTypes to APRSData
     */
    public Map<APRSTypes,APRSData> getAprsData() {
		return this.dataFields;
	}

    
    /** 
     * @param t
     * @return APRSData
     */
    public APRSData getAprsData(APRSTypes t) {
        if ( dataFields.containsKey(t)) {
            return dataFields.get(t);
        }
        return null;
    }

	
    /** 
     * @param type
     * @param data
     */
    public void addAprsData(APRSTypes type, APRSData data) {
		dataFields.put(type, data);
	}

    
    /** 
     * @param t
     * @return boolean
     */
    public boolean containsType(APRSTypes t) {
        if ( dataFields.containsKey(t) ) return true;
        return false;
    }

    
    /** 
     * @return Set of APRSTypes
     */
    public Set<APRSTypes> getTypes() {
        return dataFields.keySet();
    }

}
