package com.vagell.kv4pht.aprs.parser;

/**
 * Represents a third-party relayed APRS packet (Data Type Identifier '}').
 * The inner packet is parsed recursively, and the carrying station is appended
 * to the inner packet's digipeater path per the APRS specification.
 */
public class ThirdPartyField extends APRSData {
    private static final long serialVersionUID = 1L;
    private final APRSPacket innerPacket;

    public ThirdPartyField(byte[] rawBytes, APRSPacket innerPacket) {
        super(rawBytes);
        this.innerPacket = innerPacket;
        this.type = APRSTypes.T_THIRDPARTY;
        setLastCursorPosition(rawBytes.length);
    }

    /**
     * @return the recursively parsed inner APRSPacket
     */
    public APRSPacket getInnerPacket() {
        return innerPacket;
    }

    @Override
    public String toString() {
        return "ThirdPartyField{" + innerPacket + '}';
    }

    @Override
    public boolean hasFault() {
        return innerPacket != null && innerPacket.hasFault();
    }
}
