package com.vagell.kv4pht.aprs.parser;

import java.util.Objects;

public class ObjectField extends APRSData {
	private static final long serialVersionUID = 1L;
	protected String objectName;
	protected boolean live = true;
	protected TimeField timestamp;
	protected PositionField position;

	protected ObjectField() {
	}

	/**
	 * @param msgBody byte array of on air message
	 * parse an APRS object message
	 * 
	 * builds an ObjectField instance with the parsed data
	 */
	public ObjectField(byte[] msgBody) throws Exception {
		// first, we get the object name
		this.objectName = new String(msgBody, 1, 9).trim();
		this.live = (msgBody[10] == '*');
		// then we get the timestamp
		this.timestamp = new TimeField(msgBody, 10);
		this.position = new PositionField(msgBody, "FOO", 17);
		this.setLastCursorPosition(36);
	}

	/**
	 * 
	 * @param objectName
	 * @param live
	 * @param position
	 * @param comment
	 * 
	 * build an ObjectField with the parsed data
	 */
	public ObjectField(String objectName, boolean live, Position position, String comment) {
		this.objectName = objectName;
		this.live = live;
		this.comment = comment;
	}

	/**
	 * @return the objectName
	 */
	public String getObjectName() {
		return objectName;
	}

	/**
	 * @param objectName the objectName to set
	 */
	public void setObjectName(String objectName) {
		this.objectName = objectName;
	}

	/**
	 * @return the live
	 */
	public boolean isLive() {
		return live;
	}

	/**
	 * @param live the live to set
	 */
	public void setLive(boolean live) {
		this.live = live;
	}

	
	/** 
	 * @return String
	 */
	@Override
	public String toString() {
		if (rawBytes != null)
			return new String(rawBytes);
		return String.format(";%-9s%c%s", this.objectName, live ? '*' : '_', comment);
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

	
	/** 
	 * @return boolean
	 */
	@Override
	public boolean hasFault() {
		return this.hasFault;
	}

	
	/** 
	 * @param o
	 * @return boolean
	 */
	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof ObjectField)) {
			return false;
		}
		ObjectField objectField = (ObjectField) o;
		return Objects.equals(objectName, objectField.objectName) && live == objectField.live;
	}

	
	/** 
	 * @return int
	 */
	@Override
	public int hashCode() {
		return Objects.hash(objectName, live);
	}

}
