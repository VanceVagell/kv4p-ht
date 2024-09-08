/*
 * AVRS - http://avrs.sourceforge.net/
 *
 * Copyright (C) 2011,2021 John Gorkos, AB0OO
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class will (eventually) decode any weather sub-packet in the APRS spec.
 * I've tried to make extensive use of rexexp matching here
 */

public class WeatherParser {
    private static final Pattern dataPattern = Pattern
            .compile(".*(\\d{3})/(\\d{3})g(\\d{3})t(.{3})r(\\d{3})p(\\d{3})P(\\d{3})h(\\d{2})b(\\d{5})[\\.e].*");
    private static final Pattern windPattern = Pattern.compile(".*(\\d{3})/(\\d{3}).*.");
    private static final Pattern gustPattern = Pattern.compile(".*g(\\d{3}).*.");
    private static final Pattern tempPattern = Pattern.compile(".*t([-\\d]{3}).*");
    private static final Pattern rainPattern = Pattern.compile(".*r(\\d{3}).*");
    private static final Pattern rain24Pattern = Pattern.compile(".*p(\\d{3}).*");
    private static final Pattern rainMidnightPattern = Pattern.compile(".*P(\\d{3}).*");
    private static final Pattern humidityPattern = Pattern.compile(".*h(\\d{2}).*");
    private static final Pattern pressurePattern = Pattern.compile(".*b(\\d{5}).*");
    private static final Pattern luminosityLowPattern = Pattern.compile(".*L(\\d{3}).*");
    private static final Pattern luminosityHighPattern = Pattern.compile(".*l(\\d{3}).*");

    
    /** 
     * @param msgBody
     * @param cursor
     * @return WeatherField
     * @throws Exception
     */
    public static WeatherField parseWeatherData(byte[] msgBody, int cursor) throws Exception {
        WeatherField wf = new WeatherField();
        String wxReport = new String(msgBody, cursor, msgBody.length - cursor);
        wf.setLastCursorPosition(cursor += 36);
        wf.setType(APRSTypes.T_WX);
        Matcher matcher = dataPattern.matcher(wxReport);
        if (matcher.matches()) {
            try {
                wf.setWindDirection(Integer.parseInt(matcher.group(1)));
                wf.setWindSpeed(Integer.parseInt(matcher.group(2)));
                wf.setWindGust(Integer.parseInt(matcher.group(3)));
                wf.setTemp(Integer.parseInt(matcher.group(4)));
                wf.setRainLastHour(Double.parseDouble(matcher.group(5)) / 100);
                wf.setRainLast24Hours(Double.parseDouble(matcher.group(6)) / 100);
                wf.setRainSinceMidnight(Double.parseDouble(matcher.group(7)) / 100);
                wf.setHumidity(Double.parseDouble(matcher.group(8)));
                wf.setPressure(Double.parseDouble(matcher.group(9)));
            } catch (NumberFormatException nfe) {
                System.err.println("Got a weather packet with bogus data");
            } catch (IllegalStateException ese) {
                System.err.println("something failed in our matching expression");  
            }
        } else {
            // we need to pick out the matches one by one
            matcher = windPattern.matcher(wxReport);
            if (matcher.matches()) {
                wf.setWindDirection(Integer.parseInt(matcher.group(1)));
                wf.setWindSpeed(Integer.parseInt(matcher.group(2)));
            }
            matcher = gustPattern.matcher(wxReport);
            if (matcher.matches()) {
                wf.setWindGust(Integer.parseInt(matcher.group(1)));
            }
            matcher = tempPattern.matcher(wxReport);
            if (matcher.matches()) {
                wf.setTemp(Integer.parseInt(matcher.group(1)));
            }
            matcher = rainPattern.matcher(wxReport);
            matcher.find();
            if (matcher.matches()) {
                wf.setRainLastHour(Double.parseDouble(matcher.group(1)) / 100);
            }
            matcher = rain24Pattern.matcher(wxReport);
            if (matcher.matches()) {
                wf.setRainLast24Hours(Double.parseDouble(matcher.group(1)) / 100);
            }
            matcher = rainMidnightPattern.matcher(wxReport);
            if (matcher.matches()) {
                wf.setRainSinceMidnight(Double.parseDouble(matcher.group(1)) / 100);
            }
            matcher = humidityPattern.matcher(wxReport);
            if (matcher.matches()) {
                wf.setHumidity(Double.parseDouble(matcher.group(1)));
            }
            matcher = pressurePattern.matcher(wxReport);
            if (matcher.matches()) {
                wf.setPressure(Double.parseDouble(matcher.group(1)));
            }
            matcher = luminosityLowPattern.matcher(wxReport);
            if (matcher.matches()) {
                wf.setLuminosity(Integer.parseInt(matcher.group(1)));
            }
            matcher = luminosityHighPattern.matcher(wxReport);
            matcher.find();
            if (matcher.matches()) {
                wf.setLuminosity(Integer.parseInt(matcher.group(1)) + 1000);
            }
        }
        return wf;
    }
}
