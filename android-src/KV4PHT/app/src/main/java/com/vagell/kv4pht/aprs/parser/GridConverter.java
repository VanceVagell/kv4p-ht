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
/**
 * 
 * @author johng
 * Incomplete, but a good start for a class to calculate grid squares from
 * latitude and longitude.  I'll finish this some time...
 */
public class GridConverter {
	private static char[] field = { 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
			'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R' };

/** 
 * @param ARGS
 */
//	private static char[] sub square = { 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
//			'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u',
//			'v', 'w', 'x' };

	public static void main( String[] ARGS ) {
		double latitude = 0;
		double longitude = 0;
		if ( ARGS.length < 2 ) {
			System.out.println("Usage:  GridConverter <latitude> <longitude>");
			System.exit(1);
		}
		try {
			latitude = Double.parseDouble(ARGS[0]);
			longitude = Double.parseDouble(ARGS[1]);
		} catch ( NumberFormatException nfe ) {
			System.err.println("Please check your number formats.");
			System.exit(1);
		}
		latitude = 41.7146;
		longitude = -72.7271; 
		System.out.println(" Converting "+longitude+", "+latitude);
		double olong = 180.0 + longitude;
		double olat = 90.0 + latitude;
		System.out.println(olong+" "+olat);
		int fieldLongitude = (int)(Math.floor(olong/20.0));
		int fieldLatitude = (int)(Math.floor(olat/10.0));
		System.out.println(fieldLongitude+" "+fieldLatitude);
		char field1 = field[fieldLongitude];
		char field2 = field[fieldLatitude];
		double degreesFromEasting = olong - (fieldLongitude * 20);
		double degreesFromNorthing = olat - (fieldLatitude * 10);
		System.out.println(degreesFromEasting+" "+degreesFromNorthing);
		int squareLongitude = (int)Math.floor(degreesFromEasting / 2);
		int squareLatitude = (int)Math.floor(degreesFromNorthing);
		System.out.println(field1+""+field2+""+squareLongitude+""+squareLatitude);
	}
}