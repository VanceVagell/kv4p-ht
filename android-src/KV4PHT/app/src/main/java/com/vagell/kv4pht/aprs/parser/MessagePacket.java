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

public class MessagePacket extends InformationField {
	private static final long serialVersionUID = 1L;
    private String messageBody;
    private String messageNumber;
    private String targetCallsign ="";
    private boolean isAck = false;
    private boolean isRej = false;
    
    public MessagePacket( byte[] bodyBytes, String destCall ) {
        super(bodyBytes);
        String message = new String(bodyBytes);
        if ( message.length() < 2) {
            this.hasFault = true;
            return;
        }
        int msgSpc = message.indexOf(':', 2);
        if ( msgSpc < 1 ) {
        	this.targetCallsign = "UNKNOWN";
        } else {
        	targetCallsign = message.substring(1,msgSpc).trim().toUpperCase();
        }
        int msgNumberIdx = message.lastIndexOf('{');
        this.messageNumber="";
        if ( msgNumberIdx > -1 ) {
            this.messageNumber = message.substring(msgNumberIdx+1);
            messageBody = message.substring(11,msgNumberIdx);
        } else {
            messageBody = message.substring(11);
        }
        String lcMsg = messageBody.toLowerCase();
        if ( lcMsg.startsWith("ack") ) {
        	isAck = true;
        	this.messageNumber = messageBody.substring(3,messageBody.length());
		this.messageBody = messageBody.substring(0, 3);
        }
        if ( lcMsg.startsWith("rej") ) {
        	isRej = true;
        	this.messageNumber = messageBody.substring(3,messageBody.length());
		this.messageBody = messageBody.substring(0, 3);
        }
    }
    
    public MessagePacket(String targetCallsign, String messageBody, String messageNumber) {
    	this.messageBody = messageBody;
    	this.targetCallsign = targetCallsign;
    	this.messageNumber = messageNumber;
    	if ( messageBody.equals("ack") ) isAck = true;
    	if ( messageBody.equals("rej") ) isRej = true;
    	super.setDataTypeIdentifier(':');
    }
    
    /**
     * @return the messageBody
     */
    public String getMessageBody() {
        return this.messageBody;
    }

    /**
     * @param messageBody the messageBody to set
     */
    public void setMessageBody(String messageBody) {
        this.messageBody = messageBody;
    }

    /**
     * @return the messageNumber
     */
    public String getMessageNumber() {
        return messageNumber;
    }

    /**
     * @param messageNumber the messageNumber to set
     */
    public void setMessageNumber(String messageNumber) {
        this.messageNumber = messageNumber;
    }

    /**
     * @return the targetCallsign
     */
    public String getTargetCallsign() {
        return targetCallsign;
    }

    /**
     * @param targetCallsign the targetCallsign to set
     */
    public void setTargetCallsign(String targetCallsign) {
        this.targetCallsign = targetCallsign;
    }

	/**
	 * @return the isAck
	 */
	public boolean isAck() {
		return isAck;
	}

	/**
	 * @param isAck the isAck to set
	 */
	public void setAck(boolean isAck) {
		this.isAck = isAck;
	}

	/**
	 * @return the isRej
	 */
	public boolean isRej() {
		return isRej;
	}

	/**
	 * @param isRej the isRej to set
	 */
	public void setRej(boolean isRej) {
		this.isRej = isRej;
	}

	
    /** 
     * @return String
     */
    @Override
	public String toString() {
		if (rawBytes != null)
			return new String(rawBytes);
		if ( this.messageBody.equals("ack") || this.messageBody.equals("rej")) {
			return String.format(":%-9s:%s%s", this.targetCallsign, this.messageBody, this.messageNumber);
		} else if (messageNumber.length() > 0) {
			return String.format(":%-9s:%s{%s", this.targetCallsign, this.messageBody, this.messageNumber);
		} else {
			return String.format(":%-9s:%s", this.targetCallsign, this.messageBody);
		}
	}
}
