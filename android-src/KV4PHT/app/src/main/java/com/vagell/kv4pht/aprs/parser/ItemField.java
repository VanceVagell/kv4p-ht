package com.vagell.kv4pht.aprs.parser;

import java.util.Objects;

public class ItemField extends APRSData {
	private static final long serialVersionUID = 1L;
	private boolean live = true;
	private String objectName;

	/**
	 * @param msgBody byte array of the on-air message
	 * @throws Exception if it is unable to parse the item field from the msg
	 * 
	 * parse an APRS item message
	 */
	public ItemField(byte[] msgBody) throws Exception {
		this.rawBytes = msgBody;
		String body = new String(msgBody);
		int name_length = body.indexOf("!") - 1;
		if (name_length < 1 || name_length > 9) {
			name_length = body.indexOf("_");
			if (name_length < 1 || name_length > 9)
				throw new Exception("Invalid ITEM packet, missing '!' or '_'.");
			this.live = false;
		} else
			this.live = true;
		this.objectName = new String(msgBody, 1, name_length).trim();
		int cursor = name_length + 2;
		comment = new String(msgBody, cursor, msgBody.length - cursor, "UTF-8").trim();
	}

	
	/** 
	 * @return String
	 */
	@Override
	public String toString() {
		if (rawBytes != null)
			return new String(rawBytes);
		return ")" + this.objectName + (live ? "!" : "_") + comment;
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
		if (!(o instanceof ItemField)) {
			return false;
		}
		ItemField itemField = (ItemField) o;
		return live == itemField.live && Objects.equals(objectName, itemField.objectName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(live, objectName);
	}

}
