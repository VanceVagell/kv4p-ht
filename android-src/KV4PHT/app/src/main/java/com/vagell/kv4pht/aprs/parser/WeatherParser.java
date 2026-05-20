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
    private static final Pattern windPattern = Pattern.compile("(?:^|_)(\\d{3})/(\\d{3})");
    private static final Pattern gustPattern = Pattern.compile(".*g(\\d{3}).*.");
    private static final Pattern tempPattern = Pattern.compile(".*t(-?\\d{2,3}).*");
    private static final Pattern rainPattern = Pattern.compile(".*r(\\d{3}).*");
    private static final Pattern rain24Pattern = Pattern.compile(".*p(\\d{3}).*");
    private static final Pattern rainMidnightPattern = Pattern.compile(".*P(\\d{3}).*");
    private static final Pattern humidityPattern = Pattern.compile(".*h(\\d{2}).*");
    private static final Pattern pressurePattern = Pattern.compile(".*b(\\d{5}).*");
    private static final Pattern luminosityLowPattern = Pattern.compile(".*L(\\d{3}).*");
    private static final Pattern luminosityHighPattern = Pattern.compile(".*l(\\d{3}).*");
    private static final Pattern snowPattern = Pattern.compile(".*s(\\d{3}).*");


    public static WeatherField parseWeatherData(byte[] msgBody, int cursor) {
        WeatherField wf = new WeatherField();
        String wxReport = new String(msgBody, cursor, msgBody.length - cursor);
        wf.setType(APRSTypes.T_WX);

        Matcher matcher = windPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setWindDirection(Integer.parseInt(matcher.group(1)));
                wf.setWindSpeed(Integer.parseInt(matcher.group(2)));
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = gustPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setWindGust(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = tempPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setTemp(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = rainPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setRainLastHour(Double.parseDouble(matcher.group(1)) / 100.0);
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = rainMidnightPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setRainSinceMidnight(Double.parseDouble(matcher.group(1)) / 100.0);
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = rain24Pattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setRainLast24Hours(Double.parseDouble(matcher.group(1)) / 100.0);
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = humidityPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                double hum = Double.parseDouble(matcher.group(1));
                if (hum == 0) hum = 100;
                wf.setHumidity(hum);
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = pressurePattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setPressure(Double.parseDouble(matcher.group(1)) / 10.0);
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = luminosityLowPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setLuminosity(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = luminosityHighPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setLuminosity(Integer.parseInt(matcher.group(1)) + 1000);
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        matcher = snowPattern.matcher(wxReport);
        if (matcher.find()) {
            try {
                wf.setSnowfallLast24Hours(Double.parseDouble(matcher.group(1)));
            } catch (NumberFormatException nfe) {
                // ignore
            }
        }

        // Weather data consumes the remainder of the packet
        wf.setLastCursorPosition(msgBody.length);
        return wf;
    }
}
