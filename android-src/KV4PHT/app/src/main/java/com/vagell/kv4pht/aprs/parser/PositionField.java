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

 *  Please note that significant portions of this code were taken from the JAVA FAP
 *  conversion by Matti Aarnio at http://repo.ham.fi/websvn/java-aprs-fap/
 */
package com.vagell.kv4pht.aprs.parser;

import java.util.Objects;

public class PositionField extends APRSData {
	private static final long serialVersionUID = 1L;
	private Position position = new Position(0, 0);
	private String positionSource;
	private boolean compressedFormat;
	DataExtension extension = null;

	public PositionField(byte[] msgBody, String destinationField) throws Exception {
		System.err.println("Executing alternate constructor");
		new PositionField(msgBody, destinationField, 0);
	}

	public PositionField(byte[] msgBody, String destinationField, int cursor) throws Exception {
		super(msgBody);
		positionSource = "Unknown";
		char packetType = (char) msgBody[0];
		this.hasFault = false;
		try {
			switch (packetType) {
				case '\'':
				case '`': // Possibly MICe
					// (char)packet.length >= 9 ?
					this.type = APRSTypes.T_POSITION;
					this.position = PositionParser.parseMICe(msgBody, destinationField);
					this.extension = PositionParser.parseMICeExtension(msgBody, destinationField);
					this.positionSource = "MICe";
					cursor = 10;
					if (cursor < msgBody.length
							&& (msgBody[cursor] == '>' || msgBody[cursor] == ']' || msgBody[cursor] == '`'))
						cursor++;
					if (cursor < msgBody.length && msgBody[cursor] == '"')
						cursor += 4;
					break;
				case '!':
					// "$ULT..." -- Ultimeter 2000 weather instrument
					if (msgBody[1] == 'U' && msgBody[2] == 'L' && msgBody[3] == 'T') {
						this.type = APRSTypes.T_WX;
						break;
					}
				case '=':
				case '/':
				case '@':
					if (msgBody.length < 10) { // Too short!
						this.hasFault = true;
						this.comment += " Packet too short.";
					} else {

						// Normal or compressed location packet, with or without
						// timestamp, with or without messaging capability
						// ! and / have messaging, / and @ have a prepended timestamp

						this.type = APRSTypes.T_POSITION;
						char posChar = (char) msgBody[cursor];
						if (validSymTableCompressed(posChar)) { /* [\/\\A-Za-j] */
							// compressed position packet
							this.position = PositionParser.parseCompressed(msgBody, cursor);
							this.extension = PositionParser.parseCompressedExtension(msgBody, cursor);
							this.positionSource = "Compressed";
							cursor += 13;
						} else if ('0' <= posChar && posChar <= '9') {
							// normal uncompressed position
							try {
								this.position = PositionParser.parseUncompressed(msgBody, cursor);
							} catch (Exception ex) {
								this.comment = ex.getMessage();
								System.err.println(ex);
								hasFault = true;
							}
							try {
								this.extension = PositionParser.parseUncompressedExtension(msgBody, cursor);
							} catch (ArrayIndexOutOfBoundsException oobex) {
								this.extension = null;
							}
							this.positionSource = "Uncompressed";
							cursor += 19;
						} else {
							this.positionSource = "Who knows...";
							hasFault = true;
						}
						break;
					}
				case '$':
					if (msgBody.length > 10) {
						this.type = APRSTypes.T_POSITION;
						this.position = PositionParser.parseNMEA(msgBody);
						this.positionSource = "NMEA";
					} else {
						hasFault = true;
					}
					break;

			}
			if (null != position && position.getSymbolCode() == '_') {
				// pass
			} else {
				if (cursor > 0 && cursor < msgBody.length) {
					comment = new String(msgBody, cursor, msgBody.length - cursor, "UTF-8");
				}
			}
			this.setLastCursorPosition(cursor);
			compressedFormat = false;
		} catch (Exception ex) {
			this.hasFault = true;
			this.comment = this.comment + " INVALID position format.";
		}
	}

	public PositionField(Position position, String comment) {
		this.position = position;
		this.type = APRSTypes.T_POSITION;
		// this.comment = comment;
		compressedFormat = false;
	}

	public PositionField(Position position, String comment, boolean msgCapable) {
		this(position, comment);
		// canMessage = msgCapable;
	}

	
	/** 
	 * @param val tells the encoder to compress this packet out output
	 */
	public void setCompressedFormat(boolean val) {
		compressedFormat = val;
	}

	
	/** 
	 * @return boolean returns true if this packet is compressed
	 */
	public boolean getCompressedFormat() {
		return compressedFormat;
	}

	/** 
	 * @return boolean returns true if this packet has a valid symbol
	 */
	private boolean validSymTableCompressed(char c) {
		if (c == '/' || c == '\\')
			return true;
		if ('A' <= c && c <= 'Z')
			return true;
		if ('a' <= c && c <= 'j')
			return true;
		return false;
	}

	/*
	 * private boolean validSymTableUncompressed(char c) { if (c == '/' || c ==
	 * '\\') return true; if ('A' <= c && c <= 'Z') return true; if ('0' <= c && c
	 * <= '9') return true; return false; }
	 * 
	 * public String toString() { return "Latitude:  " + position.getLatitude() +
	 * ", longitude: " + position.getLongitude(); }
	 */

	/**
	 * @return the position
	 */
	public Position getPosition() {
		return position;
	}

	/**
	 * @param position the position to set
	 */
	public void setPosition(Position position) {
		this.position = position;
	}

	/**
	 * @return DataExtension returns any data extension found in this packet
	 */
	public DataExtension getExtension() {
		return extension;
	}

	/**
	 * @param e data extension to add to this position
	 */
	public void setExtension( DataExtension e) {
		this.extension = e;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer("---POSITION---\n");
		sb.append("Position Source\t" + this.positionSource + "\n");
		sb.append("Is Compressed:\t" + this.compressedFormat + "\n");
		sb.append(this.position.toString());
		sb.append("Comment:  " + this.comment + "\n");
		return sb.toString();
	}

	/**
	 * @return the positionSource
	 */
	public String getPositionSource() {
		return positionSource;
	}

	public void setPositionSource(String positionSource) {
		this.positionSource = positionSource;
	}

	public boolean isCompressedFormat() {
		return this.compressedFormat;
	}

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

	@Override
	public boolean hasFault() {
		return this.hasFault;
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof PositionField)) {
			return false;
		}
		PositionField positionField = (PositionField) o;
		return Objects.equals(position, positionField.position)
				&& Objects.equals(positionSource, positionField.positionSource)
				&& compressedFormat == positionField.compressedFormat
				&& Objects.equals(extension, positionField.extension);
	}

	@Override
	public int hashCode() {
		return Objects.hash(position, positionSource, compressedFormat, extension);
	}

}
