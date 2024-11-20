package com.vagell.kv4pht.aprs.parser;
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

public abstract class APRSData implements java.io.Serializable, java.lang.Comparable<APRSData> {
    private static final long serialVersionUID = 1L;
    protected APRSTypes type;
    protected boolean hasFault;
    private int lastCursorPosition = 0;
    protected byte[] rawBytes;
    protected boolean canMessage = false;
    protected String comment;

    public APRSData() {}

    public APRSData(byte[] msgBody) {
        rawBytes = new byte[msgBody.length];
        System.arraycopy(msgBody, 0, rawBytes, 0, msgBody.length);
    }

    
    /** 
     * @return int
     */
    public int getLastCursorPosition() {
        return lastCursorPosition;
    }

    
    /** 
     * @param cp
     */
    public void setLastCursorPosition(int cp) {
        this.lastCursorPosition = cp;
    }


    /**
     * @return String representation of this object:
     */
    @Override
    public abstract String toString();

    
    /** 
     * @return boolean
     */
    public abstract boolean hasFault();

    
    /** 
     * @param type
     */
    public void setType( APRSTypes type ) {
        this.type = type;
    }

    
    /** 
     * @return APRSTypes
     */
    public APRSTypes getType() {
        return this.type;
    }

    /**
     * @return String
     */
    public String getComment() { return this.comment; }

    /** 
     * @return byte[] the raw bytes handed to this object
     */
    public byte[] getRawBytes() {
        return rawBytes;
    }

    /**
     * @param rawBytes set the raw bytes of the packet body
     */
    public void setRawBytes(byte[] rawBytes) {
        this.rawBytes = rawBytes;
    }

    /** 
     * @param o
     * @return int
     */
    @Override
    public int compareTo(APRSData o) {
        if (this.hashCode() > o.hashCode()) {
            return 1;
        }
        if (this.hashCode() == o.hashCode()) {
            return 0;
        }
        return -1;
    }
}