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
 * 
 * Large segments of this code were taken from Matti Aarnio at 
 * http://repo.ham.fi/websvn/java-aprs-fap/
 * I appreciate the base work Matti did - JohnG
 */
package com.vagell.kv4pht.aprs.parser;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.regex.Pattern;

/**
 * This class with decode any of the Position formats specified in the APRS spec, including compressed, uncompressed,
 * and MicE encoded positions
 */

public class PositionParser {
    private static Pattern commaSplit = Pattern.compile(",");

    public static Position parseUncompressed(byte[] msgBody, int cursor) throws Exception {
        Calendar.Builder cb = new Calendar.Builder();
        Calendar date = cb.build();
        if (msgBody[0] == '/' || msgBody[0] == '@') {
            // With a prepended timestamp, jump over it.
            if (msgBody[cursor+6] == 'z') {
                System.err.println("FOUND A ZULU TIMESTAMP");
                int day    = (msgBody[cursor+0] - '0') * 10 + msgBody[cursor+1] - '0';
                int hour   = (msgBody[cursor+2] - '0') * 10 + msgBody[cursor+3] - '0';
                int minute = (msgBody[cursor+4] - '0') * 10 + msgBody[cursor+5] - '0';
                date.set(Calendar.DATE, day);
                date.set(Calendar.HOUR, hour);
                date.set(Calendar.MINUTE, minute);
            }
        }
        if (msgBody.length < cursor + 19) {
            System.err.println("Cursor is "+cursor+", barfed on "+new String(msgBody, StandardCharsets.UTF_8));
            throw new UnparsablePositionException("Uncompressed packet too short");
        }

        int positionAmbiguity = 0;
        char[] posbuf = new char[msgBody.length - cursor + 1];
        int pos = 0;
        for (int i = cursor; i < cursor + 19; i++) {
            posbuf[pos] = (char) msgBody[i];
            pos++;
        }

        /* this block of code accounts for position ambiguity.  It sets the actual position
         * to the middle of the ambiguous range
         */
        if (posbuf[2] == ' ') {
            posbuf[2] = '3';
            posbuf[3] = '0';
            posbuf[5] = '0';
            posbuf[6] = '0';
            positionAmbiguity = 1;
        }
        if (posbuf[3] == ' ') {
            posbuf[3] = '5';
            posbuf[5] = '0';
            posbuf[6] = '0';
            positionAmbiguity = 2;
        }
        if (posbuf[5] == ' ') {
            posbuf[5] = '5';
            posbuf[6] = '0';
            positionAmbiguity = 3;
        }
        if (posbuf[6] == ' ') {
            posbuf[6] = '5';
            positionAmbiguity = 4;
        }
        // longitude
        if (posbuf[12] == ' ') {
            posbuf[12] = '3';
            posbuf[13] = '0';
            posbuf[15] = '0';
            posbuf[16] = '0';
            positionAmbiguity = 1;
        }
        if (posbuf[13] == ' ') {
            posbuf[13] = '5';
            posbuf[15] = '0';
            posbuf[16] = '0';
            positionAmbiguity = 2;
        }
        if (posbuf[15] == ' ') {
            posbuf[15] = '5';
            posbuf[16] = '0';
            positionAmbiguity = 3;
        }
        if (posbuf[16] == ' ') {
            posbuf[16] = '5';
            positionAmbiguity = 4;
        }

        try {
            double latitude = parseDegMin(posbuf, 0, 2, 7, true);
            char lath = (char) posbuf[7];
            char symbolTable = (char) posbuf[8];
            double longitude = parseDegMin(posbuf, 9 , 3, 8, true);
            char lngh = (char) posbuf[17];
            char symbolCode = (char) posbuf[18];

            if (lath == 's' || lath == 'S')
                latitude = 0.0F - latitude;
            else if (lath != 'n' && lath != 'N')
                throw new Exception("Bad latitude sign character");
            if (lngh == 'w' || lngh == 'W')
                longitude = 0.0F - longitude;
            else if (lngh != 'e' && lngh != 'E')
                throw new Exception("Bad longitude sign character");
            Position position = new Position(latitude, longitude, positionAmbiguity, symbolTable, symbolCode);
//          TODO - figure out what I meant here...
//            position.setTimestamp(date);
            return position;
        } catch (Exception e) {
            throw new Exception(e);
        }
    }

    public static Position parseUncompressed(byte[] msgBody) throws Exception {
        return parseUncompressed(msgBody, 1);
    }

    public static DataExtension parseUncompressedExtension(byte[] msgBody, int cursor) throws Exception {
        DataExtension de = null;
        // since the symbol code is position (cursor + 18), we start looking for
        // extensions at position 19
        if (msgBody.length <= 18 + cursor) {
            return null;
        }
        if ((char) msgBody[19 + cursor] == 'P' && (char) msgBody[20 + cursor] == 'H'
                && (char) msgBody[21 + cursor] == 'G') {
            PHGExtension phg = new PHGExtension();
            try {
                phg.setPower(Integer.parseInt(new String(msgBody, 22 + cursor, 1)));
                phg.setHeight(Integer.parseInt(new String(msgBody, 23 + cursor, 1)));
                phg.setGain(Integer.parseInt(new String(msgBody, 24 + cursor, 1)));
                phg.setDirectivity(Integer.parseInt(new String(msgBody, 25 + cursor, 1)));
                de = phg;
            } catch (NumberFormatException nfe) {
                de = null;
            }
        } else if ((char) msgBody[22 + cursor] == '/' && (char) msgBody[18 + cursor] != '_') {
            CourseAndSpeedExtension cse = new CourseAndSpeedExtension();

            String courseString = new String(msgBody, cursor + 19, 3);
            String speedString = new String(msgBody, cursor + 23, 3);
            int course = 0;
            int speed = 0;
            try {
                course = Integer.parseInt(courseString);
                speed = Integer.parseInt(speedString);
            } catch (NumberFormatException nfe) {
                course = 0;
                speed = 0;
                // System.err.println("Unable to parse course "+courseString+" or speed "+
                // speedString+" into a valid course/speed for CS Extension from "+new String(msgBody));
            }
            cse.setCourse(course);
            cse.setSpeed(speed);
            de = cse;
        }
        return de;
    }

    public static Position parseMICe(byte[] msgBody, final String destinationCall) throws Exception {
        // Check that the destination call exists and is
        // of the right size for mic-e
        // System.out.print("MICe:");
        String dcall = destinationCall;
        if (destinationCall.indexOf("-") > -1) {
            dcall = destinationCall.substring(0, destinationCall.indexOf("-"));
        }
        if (dcall.length() != 6) {
            throw new UnparsablePositionException("MicE Destination Call incorrect length:  " + dcall);
        }
        // Validate destination call
        char[] destcall = dcall.toCharArray();
        for (int i = 0; i < 3; ++i) {
            char c = destcall[i + 1];
            if (!(('0' <= c && c <= '9') || ('A' <= c && c <= 'L') || ('P' <= c && c <= 'Z'))) {
                throw new UnparsablePositionException("Digit " + i + " dorked:  " + c);
            }
        }
        for (int i = 3; i < 5; ++i) {
            char c = destcall[i + 1];
            if (!(('0' <= c && c <= '9') || ('L' == c) || ('P' <= c && c <= 'Z'))) {
                throw new UnparsablePositionException("Digit " + i + " dorked:  " + c);
            }
        }
        // dstcall format check acceptable
        char c = (char) msgBody[1 + 0];
        if (c < '\u0026' || c > '\u007f') {
            throw new UnparsablePositionException("Raw packet contains " + c + " at position " + 1);
        }
        c = (char) msgBody[1 + 1];
        if (c < '\u0026' || c > '\u0061') {
            throw new UnparsablePositionException("Raw packet contains " + c + " at position " + 2);
        }
        c = (char) msgBody[1 + 2];
        if (c < '\u001c' || c > '\u007f') {
            throw new UnparsablePositionException("Raw packet contains " + c + " at position " + 3);
        }
        c = (char) msgBody[1 + 3];
        if (c < '\u001c' || c > '\u007f') {
            throw new UnparsablePositionException("Raw packet contains " + c + " at position " + 4);
        }
        c = (char) msgBody[1 + 4];
        if (c < '\u001c' || c > '\u007d') {
            throw new UnparsablePositionException("Raw packet contains " + c + " at position " + 5);
        }
        c = (char) msgBody[1 + 5];
        if (c < '\u001c' || c > '\u007f') {
            throw new UnparsablePositionException("Raw packet contains " + c + " at position " + 6);
        }
        c = (char) msgBody[1 + 6];
        if ((c < '\u0021' || c > '\u007b') && (c != '\u007d')) {
            throw new UnparsablePositionException("Raw packet contains " + c + " at position " + 7);
        }
        if (!validSymTableUncompressed((char) msgBody[1 + 7])) {
            throw new UnparsablePositionException("Raw packet contains " + c + " at position " + 8);
        }
        char[] destcall2 = new char[6];
        for (int i = 0; i < 6; ++i) {
            c = destcall[i];
            if ('A' <= c && c <= 'J') {
                destcall2[i] = (char) (c - ('A' - '0')); // cast silences warning
            } else if ('P' <= c && c <= 'Y') {
                destcall2[i] = (char) (c - ('P' - '0')); // cast silences warning
            } else if ('K' == c || 'L' == c || 'Z' == c) {
                destcall2[i] = '_';
            } else
                destcall2[i] = c;
        }
        int posAmbiguity = 0;
        if (destcall2[5] == '_') {
            destcall2[5] = '5';
            posAmbiguity = 4;
        }
        if (destcall2[4] == '_') {
            destcall2[4] = '5';
            posAmbiguity = 3;
        }
        if (destcall2[3] == '_') {
            destcall2[3] = '5';
            posAmbiguity = 2;
        }
        if (destcall2[2] == '_') {
            destcall2[2] = '3';
            posAmbiguity = 1;
        }
        if (destcall2[1] == '_' || destcall2[0] == '_') {
            throw new UnparsablePositionException("bad pos-ambiguity on destcall");
        }

        double lat = 0.0F;
        try {
            lat = parseDegMin(destcall2, 0, 2, 9, false);
        } catch (Exception e) {
            throw new UnparsablePositionException("Destination Call invalid for MicE:  " + new String(destcall2));
        }
        // Check north/south direction, and correct the latitude sign if
        // necessary
        if (destinationCall.charAt(3) <= 'L') {
            lat = 0.0F - lat;
        }

        // Now parsing longitude
        int longDeg = (char) msgBody[1 + 0] - 28;
        if ((char) destcall[4] >= 'P')
            longDeg += 100;
        if (longDeg >= 180 && longDeg <= 189)
            longDeg -= 80;
        else if (longDeg >= 190 && longDeg <= 199)
            longDeg -= 190;
        int longMin = (char) msgBody[1 + 1] - 28;
        if (longMin >= 60)
            longMin -= 60;
        int longMinFract = (char) msgBody[1 + 2] - 28;

        float lng = 0.0F;

        switch (posAmbiguity) { // degree of positional ambiguity
        case 0:
            lng = ((float) longDeg + ((float) longMin) / 60.0F + ((float) longMinFract / 6000.0F));
            break;
        case 1:
            lng = ((float) longDeg + ((float) longMin) / 60.0F + ((float) (longMinFract - longMinFract % 10 + 5) / 6000.0F));
            break;
        case 2:
            lng = ((float) longDeg + ((float) longMin) / 60.0F);
            break;
        case 3:
            lng = ((float) longDeg + ((float) (longMin - longMin % 10 + 5)) / 60.0F);
            break;
        case 4:
            lng = ((float) longDeg + 0.5F);
            break;
        default:
            throw new UnparsablePositionException("Unable to extract longitude from MicE");
        }
        if ((char) destcall[1 + 4] >= 'P') { // Longitude east/west sign
            lng = 0.0F - lng; // east positive, west negative
        }
        return new Position((double) lat, (double) lng, posAmbiguity, (char) msgBody[1 + 7], (char) msgBody[1 + 6]);
    }

    public static CourseAndSpeedExtension parseMICeExtension(byte msgBody[], String destinationField) throws Exception {
        CourseAndSpeedExtension cse = new CourseAndSpeedExtension();
        int sp = msgBody[1 + 3] - 28;
        int dc = msgBody[1 + 4] - 28;
        int se = msgBody[1 + 5] - 28;
        // Decoded according to Chap 10, p 52 of APRS Spec 1.0
        int speed = sp * 10;
        int q = (int) (dc / 10);
        speed += q;
        int r = (int) (dc % 10) * 100;
        int course = r + se;
        if (course >= 400)
            course -= 400;
        if (speed >= 800)
            speed -= 800;
        cse.setSpeed(speed);
        cse.setCourse(course);
        return cse;
    }

    public static Position parseNMEA(byte[] msgBody) throws Exception {
        String[] nmea = commaSplit.split(new String(msgBody));
        String lats = null; // Strings of Lat/Lon
        String lngs = null;
        String lath = null; // Polarity of Lat/Lon
        String lngh = null;
        if (nmea.length < 5) { // Too few parts
            throw new UnparsablePositionException("Too few parts in NMEA sentence");
        }

        // NMEA sentences to understand:
        // $GPGGA Global Positioning System Fix Data
        // $GPGLL Geographic Position, Latitude/Longitude Data
        // $GPRMC Remommended Minimum Specific GPS/Transit Data
        // $GPWPT Way Point Location ?? (bug in APRS specs ?)
        // $GPWPL Waypoint Load (not in APRS specs, but in NMEA specs)
        // $PNTS Seen on APRS-IS, private sentense based on NMEA..
        // $xxTLL Not seen on radio network, usually $RATLL - Target positions
        // reported by RAdar.

        if ("$GPGGA".equals(nmea[0]) && nmea.length >= 15) {
            // GPGGA,175059,3347.4969,N,11805.7319,W,2,12,1.0,6.8,M,-32.1,M,,*7D
            // v=1, looks fine
            // GPGGA,000000,5132.038,N,11310.221,W,1,09,0.8,940.0,M,-17.7,,
            // v=1, timestamp odd, coords look fine
            // GPGGA,,,,,,0,00,,,,,,,*66
            // v=0, invalid
            // GPGGA,121230,4518.7931,N,07322.3202,W,2,08,1.0,40.0,M,-32.4,M,,*46
            // v=2, looks valid ?
            // GPGGA,193115.00,3302.50182,N,11651.22581,W,1,08,01.6,00465.90,M,-32.891,M,,*5F
            // $GPGGA,hhmmss.dd,xxmm.dddd,<N|S>,yyymm.dddd,<E|W>,v,
            // ss,d.d,h.h,M,g.g,M,a.a,xxxx*hh<CR><LF>

            if (!("1".equals(nmea[6]))) { // Not valid position fix
                throw new UnparsablePositionException("Not a valid position fix");
            }

            lats = nmea[2];
            lath = nmea[3];
            lngs = nmea[4];
            lngh = nmea[5];

        } else if ("$GPGLL".equals(nmea[0]) && nmea.length > 7) {
            // $GPGLL,xxmm.dddd,<N|S>,yyymm.dddd,<E|W>,hhmmss.dd,S,M*hh<CR><LF>
            if (!"A".equals(nmea[6]) || nmea[7].charAt(0) != 'A') {
                // Not valid ("A") or not autonomous ("A")
                throw new UnparsablePositionException("Not valid or not autonomous NMEA sentence");
            }

            lats = nmea[1];
            lath = nmea[2];
            lngs = nmea[3];
            lngh = nmea[4];

        } else if ("$GPRMC".equals(nmea[0]) && nmea.length > 11) {
            // $GPRMC,hhmmss.dd,S,xxmm.dddd,<N|S>,yyymm.dddd,<E|W>,s.s,h.h,ddmmyy,d.d,
            // <E|W>,M*hh<CR><LF>
            // ,S, = Status: 'A' = Valid, 'V' = Invalid
            // 
            // GPRMC,175050,A,4117.8935,N,10535.0871,W,0.0,324.3,100208,10.0,E,A*3B
            // GPRMC,000000,V,0000.0000,0,00000.0000,0,000,000,000000,,*01/It
            // wasn't me :)
            // invalid..
            // GPRMC,000043,V,4411.7761,N,07927.0448,W,0.000,0.0,290697,10.7,W*57
            // GPRMC,003803,A,3347.1727,N,11812.7184,W,000.0,000.0,140208,013.7,E*67
            // GPRMC,050058,A,4609.1143,N,12258.8184,W,0.000,0.0,100208,18.0,E*5B

            if (!nmea[2].equals("A")) {
                throw new UnparsablePositionException("Not valid or not autonomous NMEA sentence");
            }

            lats = nmea[3];
            lath = nmea[4];
            lngs = nmea[5];
            lngh = nmea[6];

        } else if ("$GPWPL".equals(nmea[0]) && nmea.length > 5) {
            // $GPWPL,4610.586,N,00607.754,E,4*70
            // $GPWPL,4610.452,N,00607.759,E,5*74

            lats = nmea[1];
            lath = nmea[2];
            lngs = nmea[3];
            lngh = nmea[4];

        } else if (nmea.length > 15 && "$PNTS".equals(nmea[0]) && "1".equals(nmea[1])) {
            // PNTS version 1
            // $PNTS,1,0,11,01,2002,231932,3539.687,N,13944.480,E,0,000,5,Roppongi
            // UID RELAY,000,1*35
            // $PNTS,1,0,14,01,2007,131449,3535.182,N,13941.200,E,0,0.0,6,Oota-Ku
            // KissUIDigi,000,1*1D
            // $PNTS,1,0,17,02,2008,120824,3117.165,N,13036.481,E,49,059,1,Kagoshima,000,1*71
            // $PNTS,1,0,17,02,2008,120948,3504.283,N,13657.933,E,00,000.0,6,,000,1*36
            // 
            // From Alinco EJ-41U Terminal Node Controller manual:
            // 
            // 5-4-7 $PNTS
            // This is a private-sentence based on NMEA-0183. The data contains date,
            // time, latitude, longitude, moving speed, direction, altitude plus a short
            // message, group codes, and icon numbers. The EJ-41U does not analyze this
            // format but can re-structure it.
            // The data contains the following information:
            // l $PNTS Starts the $PNTS sentence
            // l version
            // l the registered information. [0]=normal geographical location data.
            // This is the only data EJ-41U can re-structure. [s]=Initial position
            // for the course setting [E]=ending position for the course setting
            // [1]=the course data between initial and ending [P]=the check point
            // registration [A]=check data when the automatic position transmission
            // is set OFF [R]=check data when the course data or check point
            // data is
            // received.
            // l dd,mm,yyyy,hhmmss: Date and time indication.
            // l Latitude in DMD followed by N or S
            // l Longitude in DMD followed by E or W
            // l Direction: Shown with the number 360 degrees divided by 64.
            // 00 stands for true north, 16 for east. Speed in Km/h
            // l One of 15 characters [0] to [9], [A] to [E].
            // NTSMRK command determines this character when EJ-41U is used.
            // l A short message up to 20 bites. Use NTSMSG command to determine
            // this message.
            // l A group code: 3 letters with a combination of [0] to [9], [A] to [Z].
            // Use NTSGRP command to determine.
            // l Status: [1] for usable information, [0] for non-usable information.
            // l *hh<CR><LF> the check-sum and end of PNTS sentence.

            lats = nmea[7];
            lath = nmea[8];
            lngs = nmea[9];
            lngh = nmea[10];

        } else if ("$GPGSA".equals(nmea[0]) || "$GPVTG".equals(nmea[0]) || "$GPGSV".equals(nmea[0])) {
            // recognized but ignored
            throw new UnparsablePositionException("Ignored NMEA sentence");
        }

        // Parse lats,lath, lngs, lngh
        if (lats == null) {
            throw new UnparsablePositionException("Invalid NMEA sentence");
        }
        try {
            double lat = parseDegMin(lats.toCharArray(), 0, 2, 9, true);
            double lng = parseDegMin(lngs.toCharArray(), 0, 3, 9, true);
            if (lat > 90.0F)
                throw new UnparsablePositionException("Latitude too high");
            if (lng > 180.0F)
                throw new UnparsablePositionException("Longitude too high");

            if (lath.equals("S") || lath.equals("s"))
                lat = 0.0F - lat; // South negative
            else if (!(lath.equals("N") || lath.equals("n")))
                throw new UnparsablePositionException("Bad latitude sign");

            if (lngh.equals("W") || lngh.equals("w"))
                lng = 0.0F - lng; // West negative
            else if (!(lngh.equals("E") || lngh.equals("e")))
                throw new UnparsablePositionException("Bad longitude sign");

            return new Position(lat, lng, 0, '/', '>'); // FIXME: GPS symbols
            // fillPos(fap, lat, lng, '/', '>', 0);

        } catch (Exception e) {
            throw new UnparsablePositionException("Abject failure parsing NMEA sentence");
        }
    }

    public static Position parseCompressed(byte[] msgBody, int cursor) throws Exception {
        // A compressed position is always 13 characters long.
        // Make sure we get at least 13 characters and that they are ok.
        // Also check the allowed base-91 characters at the same time.
        if (msgBody.length < cursor + 13) {
            throw new UnparsablePositionException("Compressed position too short");
        }

        // Validate characters to be of expected code point range
        for (int i = 1; i < 9; ++i) {
            char c = (char) msgBody[cursor + i];
            if (c < 0x21 || c > 0x7b) {
                throw new UnparsablePositionException("Compressed position characters out of range");
            }
        }

        int lat1 = (char) msgBody[cursor + 1] - 33;
        int lat2 = (char) msgBody[cursor + 2] - 33;
        int lat3 = (char) msgBody[cursor + 3] - 33;
        int lat4 = (char) msgBody[cursor + 4] - 33;

        int lng1 = (char) msgBody[cursor + 5] - 33;
        int lng2 = (char) msgBody[cursor + 6] - 33;
        int lng3 = (char) msgBody[cursor + 7] - 33;
        int lng4 = (char) msgBody[cursor + 8] - 33;

        float lat = 90.0F - ((float) (lat1 * 91 * 91 * 91 + lat2 * 91 * 91 + lat3 * 91 + lat4) / 380926.0F);
        float lng = -180.0F + ((float) (lng1 * 91 * 91 * 91 + lng2 * 91 * 91 + lng3 * 91 + lng4) / 190463.0F);
        return new Position(lat, lng, 0, (char) msgBody[cursor + 0], (char) msgBody[cursor + 9]);
    }

    public static DataExtension parseCompressedExtension(byte[] msgBody, int cursor) throws Exception {
        DataExtension de = null;
        if (msgBody[cursor + 9] == '_') {
            // this is a weather report packet, and thus has no extension
            return null;
        }
        int t = (char) msgBody[cursor + 12] - 33;
        int nmeaSource = (t & 0x18) >> 3;
        if (nmeaSource == 2) {
            // this message came from a GPGGA sentance, and therefore has altitude
            return null;
        }
        int c = (char) msgBody[cursor + 10] - 33;
        if (c + 33 == ' ') {
            // another special case, where csT is ignored
            return null;
        }
        if (c < 90) {
            // this is a compressed course/speed value
            int s = (char) msgBody[cursor + 11] - 33;
            CourseAndSpeedExtension cse = new CourseAndSpeedExtension();
            cse.setCourse(c * 4);
            cse.setSpeed((int) Math.round(Math.pow(1.08, s) - 1));
            de = cse;
        } else if (c == (char) ('{')) {
            int s = (char) msgBody[cursor + 11] - 33;
            s = (int) Math.round(2 * Math.pow(1.08, s));
            RangeExtension re = new RangeExtension(s);
            de = re;
        }
        return de;
    }

    private static double parseDegMin(char[] txt, int cursor, int degSize, int len, boolean decimalDot)
            throws Exception {
        if (txt == null || txt.length < cursor + degSize + 2)
            throw new Exception("Too short degmin data");
        double result = 0.0F;
        for (int i = 0; i < degSize; ++i) {
            char c = txt[cursor + i];
            if (c < '0' || c > '9')
                throw new Exception("Bad input decimals:  " + c);
            result = result * 10.0F + (c - '0');
        }
        double minFactor = 10.0F; // minutes factor, divide by 10.0F for every
        // minute digit
        double minutes = 0.0F;
        int mLen = txt.length - degSize - cursor;
        if (mLen > len - degSize)
            mLen = len - degSize;
        for (int i = 0; i < mLen; ++i) {
            char c = txt[cursor + degSize + i];
            if (decimalDot && i == 2) {
                if (c == '.')
                    continue; // Skip it! (but only at this position)
                throw new Exception("Expected decimal dot");
            }
            if (c < '0' || c > '9')
                throw new Exception("Bad input decimals: " + c);
            minutes += minFactor * (c - '0');
            minFactor *= 0.1D;
        }
        if (minutes >= 60.0D)
            throw new Exception("Bad minutes value - 60.0 or over");
        // return result
        result += minutes / 60.0D;
        result = Math.round(result * 100000.0) * 0.00001D;
        if (degSize == 2 && result > 90.01D)
            throw new Exception("Latitude value too high");
        if (degSize == 3 && result > 180.01F)
            throw new Exception("Longitude value too high");
        return result;
    }

    /*
     * private boolean validSymTableCompressed(char c) { if (c == '/' || c == '\\') return true; if ('A' <= c && c <=
     * 'Z') return true; if ('a' <= c && c <= 'j') return true; return false; }
     */
    private static boolean validSymTableUncompressed(char c) {
        if (c == '/' || c == '\\')
            return true;
        if ('A' <= c && c <= 'Z')
            return true;
        if ('0' <= c && c <= '9')
            return true;
        return false;
    }
}
