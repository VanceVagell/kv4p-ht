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
import java.text.DecimalFormat;
import java.util.Locale;
/**
 * 
 * @author johng
 * This class represents a Position as specified by the APRS specification.  This includes
 * a symbol table and actual symbol, and a possible timestamp.
 *
 */
public class Position implements Serializable {
	private static final long serialVersionUID = 1L;
	private static DecimalFormat df = new DecimalFormat("0.00000");
	private Double latitude = 0d, longitude = 0d;
	private Integer altitude = -1;
	private Integer positionAmbiguity;
	private char symbolTable, symbolCode;
	private String csTField = " sT";
	
	public Position(double lat, double lon, int posAmb, char st, char sc) {
		this.latitude = Math.round(lat * 100000) * 0.00001D;
		this.longitude = Math.round(lon * 100000) * 0.00001D;
		this.positionAmbiguity = posAmb;
		this.symbolTable = st;
		this.symbolCode = sc;
	}
	
	public Position(double lat, double lon) {
		this.latitude = Math.round(lat * 100000) * 0.00001D;
		this.longitude = Math.round(lon * 100000) * 0.00001D;
		this.positionAmbiguity=0;
		this.symbolTable = '\\';
		this.symbolCode = '.';
	}

	/**
	 * @return the latitude
	 */
	public double getLatitude() {
		return Double.parseDouble(df.format(latitude));
	}

	/**
	 * @param latitude the latitude to set
	 */
	public void setLatitude(double latitude) {
		this.latitude = latitude;
	}

	/**
	 * @return the longitude
	 */
	public double getLongitude() {
		return Double.parseDouble(df.format(longitude));
	}

	/**
	 * @param longitude the longitude to set
	 */
	public void setLongitude(double longitude) {
		this.longitude = longitude;
	}

	/**
	 * @return the altitude
	 */
	public int getAltitude() {
		return altitude;
	}

	/**
	 * @param altitude the altitude to set
	 */
	public void setAltitude(int altitude) {
		this.altitude = altitude;
	}

	/**
	 * @return the positionAmbiguity
	 */
	public int getPositionAmbiguity() {
		return positionAmbiguity;
	}

	/**
	 * @param positionAmbiguity the positionAmbiguity to set
	 */
	public void setPositionAmbiguity(int positionAmbiguity) {
		this.positionAmbiguity = positionAmbiguity;
	}

	/**
	 * @return the symbolTable
	 */
	public char getSymbolTable() {
		return symbolTable;
	}

	/**
	 * @param symbolTable the symbolTable to set
	 */
	public void setSymbolTable(char symbolTable) {
		this.symbolTable = symbolTable;
	}

	/**
	 * @return the symbolCode
	 */
	public char getSymbolCode() {
		return symbolCode;
	}

	/**
	 * @param symbolCode the symbolCode to set
	 */
	public void setSymbolCode(char symbolCode) {
		this.symbolCode = symbolCode;
	}
	
	
	/** 
	 * @param decimalDegree latitude or longitude to be encoded
	 * @param isLatitude used to tell the algorithm the decimalDegree is a latitude
	 * @return String
	 */
	public String getDMS(double decimalDegree, boolean isLatitude) {
			int minFrac = (int)Math.round(decimalDegree*6000); ///< degree in 1/100s of a minute
			boolean negative = (minFrac < 0);
			if (negative)
					minFrac = -minFrac;
			int deg = minFrac / 6000;
			int min = (minFrac / 100) % 60;
			minFrac = minFrac % 100;
			String ambiguousFrac;

			switch (positionAmbiguity) {
			case 1: // "dd  .  N"
				ambiguousFrac = "  .  "; break;
			case 2: // "ddm .  N"
				ambiguousFrac = String.format((Locale)null, "%d .  ", min/10); break;
			case 3: // "ddmm.  N"
				ambiguousFrac = String.format((Locale)null, "%02d.  ", min); break;
			case 4: // "ddmm.f N"
				ambiguousFrac = String.format((Locale)null, "%02d.%d ", min, minFrac/10); break;
			default: // "ddmm.ffN"
				ambiguousFrac = String.format((Locale)null, "%02d.%02d", min, minFrac); break;
			}
			if ( isLatitude ) {
				return String.format((Locale)null, "%02d%s%s", deg, ambiguousFrac, ( negative ? "S" : "N"));
			} else {
				return String.format((Locale)null, "%03d%s%s", deg, ambiguousFrac, ( negative ? "W" : "E"));
			}
	}
	
	
	/** 
	 * @return String
	 */
	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append("Latitude:\t"+df.format(this.latitude)+"\n");
		sb.append("Longitude:\t"+df.format(this.longitude)+"\n");
		return sb.toString();
	}
	
	
	/** 
	 * @return String
	 */
	public String toDecimalString() {
		return df.format(latitude)+", "+df.format(longitude);
	}

	
	/** 
	 * @param val 
	 */
	public void setCsTField(String val) {
		if(val == null || val == "") {
			val = " sT";
		}
		csTField = val;
	}

	/** 
	 * @return String
	 */
	public String getCsTField() {
		return csTField;
	}

	/** 
	 * @return String
	 */
	public String toCompressedString() {
		long latbase = Math.round(380926 * (90-this.latitude));
		long latchar1 = latbase / (91*91*91)+33;
		latbase = latbase % (91*91*91);
		long latchar2 = latbase / (91*91)+33;
		latbase = latbase % (91*91);
		int latchar3 = (int)(latbase / 91)+33;
		int latchar4 = (int)(latbase % 91)+33;
		long lonbase = Math.round(190463 * (180+this.longitude));
		long lonchar1 = lonbase / (91*91*91)+33;
		lonbase %= (91*91*91);
		long lonchar2 = lonbase / (91*91)+33;
		lonbase = lonbase % (91*91);
		int lonchar3 = (int)(lonbase / 91)+33;
		int lonchar4 = (int)(lonbase % 91)+33;
		
		return ""+symbolTable+(char)latchar1+(char)latchar2+(char)latchar3+(char)latchar4+
				""+(char)lonchar1+(char)lonchar2+(char)lonchar3+(char)lonchar4+symbolCode+csTField;
	}

	/** 
	 * @param lat1 lattitude of starting point
	 * @param lng1 longitude of starting point
	 * @param lat2 latitude of ending point
	 * @param lng2 longitude of ending point
	 * @return float the distance between two geographic points in miles
	 */
	public static float distFrom(double lat1, double lng1, double lat2, double lng2) {
		    double earthRadius = 3958.75;
		    double dLat = Math.toRadians(lat2-lat1);
		    double dLng = Math.toRadians(lng2-lng1);
		    double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
		               Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
		               Math.sin(dLng/2) * Math.sin(dLng/2);
		    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
		    double dist = earthRadius * c;

		    return (float) dist;
    }
	
	/** 
	 * @param position2 point to determine distance from
	 * @return float the distance in miles from the position in this message and given position
	 */
	public float distance(Position position2) {
		double lat1 = this.getLatitude();
		double lat2 = position2.getLatitude();
		double lng1 = this.getLongitude();
		double lng2 = position2.getLongitude();
		return distFrom(lat1,lng1,lat2,lng2);
	}
	
	/**
	 * Returns Azimuth between two points as measured clockwise from North
	 * and vary from 0° to 360°.
	 * <p>
	 * This function returns the initial bearing (sometimes referred to as forward azimuth)
	 * which if followed in a straight line along a great-circle arc will take you from the
	 * start point to the end point.
	 * </p>
	 * @param position2 end position
	 * @return bearing in degrees
	 */
	public float direction(Position position2) {
		double Lat1 = Math.toRadians(position2.getLatitude());
		double Lon1 = position2.getLongitude();
		double Lat2 = Math.toRadians(getLatitude());
		double Lon2 = getLongitude();
		double dLon = Math.toRadians(Lon2 - Lon1);
		double y = Math.sin(dLon) * Math.cos(Lat2);
		double x = Math.cos(Lat1) * Math.sin(Lat2) - Math.sin(Lat1) *
			Math.cos(Lat2) * Math.cos(dLon);
		return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
	}

	public static void main(String[] args) {
		Position pos = new Position(0,0);
		pos.setLatitude(34.12558);
		pos.setLongitude(-84.13697);
		pos.setSymbolCode('o');
		pos.setSymbolTable('/');
		System.out.println("latitude is "+pos.getLatitude());
		System.out.println("Position string is "+pos.toString());
		System.out.println("Compressed string is "+pos.toCompressedString());
	}

}
