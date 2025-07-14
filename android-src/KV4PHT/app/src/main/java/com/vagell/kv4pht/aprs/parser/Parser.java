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

 * Please note that significant portions of this code were taken from the JAVA FAP
 * translation by Matti Aarnio at http://repo.ham.fi/websvn/java-aprs-fap/
 * 
 */

package com.vagell.kv4pht.aprs.parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
/**
 * 
 * @author johng
 *	This is the code parser for AX25 UI packets that are traditionally used in APRS networks, in TNC2
 * format.  TNC2 format is defined as:
 * SOURCE>DESTIN,VIA,VIA:payload
 * In APRS packets, the first character of the payload is the Data Type Identifier, which is the key for
 * further parsing of the message.  This class parses raw TNC2 packets and returns instances of APRSPackets
 */
public class Parser {
	
	
	/** 
	 * @param args
	 */
	public static void main( String[] args ) {
		if ( args.length > 0 ) {
			try {
				APRSPacket packet = Parser.parse(args[0]);
				System.out.println("From:	"+packet.getSourceCall());
				System.out.println("To:	"+packet.getDestinationCall());
				System.out.println("Via:	"+packet.getDigiString());
				System.out.println("DTI:	"+packet.getDti());
				System.out.println("Valid:	"+packet.isAprs());
				InformationField data = packet.getPayload();
				System.out.println("Data:	" + data);
				if ( packet.isAprs() && data != null) {
					System.out.println("    Type:	" + data.getClass().getName());
					System.out.println("    Messaging:	" + data.canMessage);
					System.out.println("    Comment:	" + data.getComment());
					System.out.println("    Extension:	" + data.getExtension());
				}
			} catch ( Exception ex ) {
				System.err.println("Unable to parse:  "+ex);
				ex.printStackTrace();
			}
		}
	}
    
    
	/** 
	 * @param rawPacket
	 * @return APRSPacket
	 */
	public static APRSPacket parsePacket(byte[] rawPacket) {
        //if ( packet.getDti() == '!' || packet.getDti() == '=' ) {
            // !3449.94N/08448.56W_203/000g000t079P133h85b10149OD1
        return new APRSPacket(null, null, null, null);
    }
    
    
	/** 
	 * @param packet
	 * @return APRSPacket
	 * @throws Exception
	 */
	public static APRSPacket parse(final String packet) throws Exception {
        int cs = packet.indexOf('>');
        String source = packet.substring(0,cs).toUpperCase();
        int ms = packet.indexOf(':');
        String digiList = packet.substring(cs+1,ms);
        String[] digiTemp = digiList.split(",");
        String dest = digiTemp[0].toUpperCase();
        ArrayList<Digipeater> digis = Digipeater.parseList(digiList, false);
        String body = packet.substring(ms+1);
        APRSPacket ap = parseBody(source, dest, digis, body);
        ap.setOriginalString(packet);
        return ap;
    }

    
	/** 
	 * @param packet
	 * @return APRSPacket
	 * @throws Exception
	 */
	public static APRSPacket parseAX25(byte[] packet) throws Exception {
	    int pos = 0;
	    String dest = new Callsign(packet, pos).toString();
	    pos += 7;
	    String source = new Callsign(packet, pos).toString();
	    pos += 7;
	    ArrayList<Digipeater> digis = new ArrayList<Digipeater>();
	    while ((packet[pos - 1] & 1) == 0) {
		    Digipeater d =new Digipeater(packet, pos);
		    digis.add(d);
		    pos += 7;
	    }
	    if (packet[pos] != 0x03 || packet[pos+1] != -16 /*0xf0*/)
		    throw new IllegalArgumentException("control + pid must be 0x03 0xF0!");
	    pos += 2;
	    String body = new String(packet, pos, packet.length - pos);
	    return parseBody(source, dest, digis, body);
    }

	/**
	 * 
	 * @param source Source callsign
	 * @param dest Destination callsing, may be part of a compressed postion
	 * @param digis array of digipeaters this packet has passed through
	 * @param body msg body of the on air message
	 * @return
	 * @throws Exception
	 * 
	 * This is the core packet parser.  It parses the entire "body" of the APRS Packet,
	 * starting with the Data Type Indicator in position 0.
	 */

    public static APRSPacket parseBody(String source, String dest, ArrayList<Digipeater> digis, String body) throws Exception {
		APRSPacket packet = new APRSPacket(source,dest,digis, body.getBytes());
        byte[] msgBody = body.getBytes();
        byte dti = msgBody[0];
		// get the invalid crap out of the way right away.
		if ( (dti >='A' && dti <= 'S') || 
		     (dti >='U' && dti <= 'Z') ||
			 (dti >='0' && dti <= '9') ) {
			packet.setHasFault(true);
			packet.setComment("Invalid DTI");
			return packet;
		}
		InformationField infoField = packet.getPayload();
		int cursor = 0;
        switch ( dti ) {
        	case '/':
        	case '@':
				// These have timestamps, so we need to parse those, advance the cursor, and then look for
				// the position data
				TimeField timeField = new TimeField(msgBody, cursor);
				infoField.addAprsData(APRSTypes.T_TIMESTAMP, timeField);
				cursor = timeField.getLastCursorPosition();
				PositionField pf = new PositionField(msgBody, dest, cursor+1);
				infoField.addAprsData(APRSTypes.T_POSITION,pf);
				cursor = pf.getLastCursorPosition();
				if ( pf.getPosition().getSymbolCode() == '_' ) {
					// this is a weather packet, so pull the weather info from it
					WeatherField wf = WeatherParser.parseWeatherData(msgBody, cursor);
					wf.setType(APRSTypes.T_WX);
					infoField.addAprsData(APRSTypes.T_WX, wf);
					cursor = wf.getLastCursorPosition();
				}
				break;
	    	case '!':
        	case '=':
        	case '`':
        	case '\'':
        	case '$':
        		if ( body.startsWith("$ULTW") ) {
        			// Ultimeter II weather packet
        		} else {
					// these are non-timestamped packets with position.
					PositionField posField = new PositionField(msgBody, dest, cursor+1);
					cursor = posField.getLastCursorPosition();
					infoField.addAprsData(APRSTypes.T_POSITION, posField );
					if ( posField.getPosition().getSymbolCode() == '_' && msgBody.length > 20) {
						// with weather...
						WeatherField wf = WeatherParser.parseWeatherData(msgBody, cursor + 1);
						infoField.addAprsData(APRSTypes.T_WX, wf);
						cursor = wf.getLastCursorPosition();
					}
        		}
    			break;
        	case ':':
        		infoField = new MessagePacket(msgBody,dest);
        		break;
    		case ';':
    			if (msgBody.length > 29) {
    				//System.out.println("Parsing an OBJECT");
					ObjectField of = new ObjectField(msgBody);
    				infoField.addAprsData(APRSTypes.T_OBJECT, of);
					cursor = of.getLastCursorPosition();
					byte[] slice = Arrays.copyOfRange(msgBody, cursor, msgBody.length-1);
					packet.setComment(new String(slice, StandardCharsets.UTF_8));
    			} else {
    				System.err.println("Object packet body too short for valid object");
    				packet.setHasFault(true); // too short for an object
    			}
    			break;
    		case '>':
//				packet.setType(APRSTypes.T_STATUS);
    			break;
    		case '<':
//				packet.setType(APRSTypes.T_STATCAPA);
    			break;
    		case '?':
//				packet.setType(APRSTypes.T_QUERY);
    			break;
    		case ')':
//				packet.setType(APRSTypes.T_ITEM);
//    			if (msgBody.length > 18) {
//				infoField = new ItemPacket(msgBody);
//    			} else {
//    				packet.hasFault = true; // too short
//    			}
    			break;
    		case 'T':
    			if (msgBody.length > 18) {
    				//System.out.println("Parsing TELEMETRY");
    				//parseTelem(bodyBytes);
    			} else {
    				packet.setHasFault(true); // too short
	   			}
    			break;
    		case '#': // Peet Bros U-II Weather Station
    		case '*': // Peet Bros U-II Weather Station
    		case '_': // Weather report without position
				WeatherField  wf = WeatherParser.parseWeatherData(msgBody, cursor);
				infoField.addAprsData(APRSTypes.T_WX, wf);
				cursor = wf.getLastCursorPosition();
    			break;
    		case '{':
//				packet.setType(APRSTypes.T_USERDEF);
    			break;
    		case '}': // 3rd-party
//				packet.setType(APRSTypes.T_THIRDPARTY);
    			break;

    		default:
    			packet.setHasFault(true); // UNKNOWN!
    			break;

        }
		return packet;
    }
    
}
