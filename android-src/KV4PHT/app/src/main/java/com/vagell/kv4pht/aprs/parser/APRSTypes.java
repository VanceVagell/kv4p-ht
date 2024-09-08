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

 *  This code lifted blatantly and directly from the JAVA FAP translation done
 *  by Matti Aarnio at http://repo.ham.fi/websvn/java-aprs-fap/
 */

package com.vagell.kv4pht.aprs.parser;

public enum APRSTypes {
    T_UNSPECIFIED,
	T_TIMESTAMP,
	T_POSITION,
	T_WX,
	T_THIRDPARTY,
	T_QUERY,
	T_OBJECT,
	T_ITEM,
	T_NORMAL,
	T_KILL,
	T_STATUS,
	T_STATCAPA,
	T_TELEMETRY,
	T_USERDEF,
	T_MESSAGE,
	T_NWS;  // Used on fap.getSubtype()

	;
}
