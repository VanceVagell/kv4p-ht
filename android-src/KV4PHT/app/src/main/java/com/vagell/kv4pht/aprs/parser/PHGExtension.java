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

public class PHGExtension extends DataExtension implements Serializable {
	private static final long serialVersionUID = 1L;
	private static int[] powerCodes = {0,1,4,9,16,25,36,49,64,81};
	private static int[] heightCodes = {10,20,40,80,160,320,640,1280,2560,5120};
	private static int[] gainCodes = {0,1,2,3,4,5,6,7,8,9};
	private static int[] directivityCodes = {0,45,90,135,180,225,270,315,360,0};
	
	private int power;
	private int height;
	private int gain;
	private int directivity;
	/**
	 * @return the power
	 */
	public int getPower() {
		return power;
	}
	/**
	 * @param power the power to set
	 */
	public void setPower(int power) {
		this.power = powerCodes[power];
	}
	/**
	 * @return the height
	 */
	public int getHeight() {
		return height;
	}
	/**
	 * @param height the height to set
	 */
	public void setHeight(int height) {
		this.height = heightCodes[height];
	}
	/**
	 * @return the gain
	 */
	public int getGain() {
		return gain;
	}
	/**
	 * @param gain the gain to set
	 */
	public void setGain(int gain) {
		this.gain = gainCodes[gain];
	}
	/**
	 * @return the directivity
	 */
	public int getDirectivity() {
		return directivity;
	}
	/**
	 * @param directivity the directivity to set
	 */
	public void setDirectivity(int directivity) {
		this.directivity = directivityCodes[directivity];
	}
	
	/**
	 * @return Enum indicating the data type extention
	*/
	@Override
	public APRSExtensions getType() {
		return APRSExtensions.T_PHG;
	}
	
	/** 
	 * @return String
	 */
	@Override
	public String toSAEString() {
		return power+" watts at "+height+" ft HAAT with "+gain+" dBi gain directed at "+directivity+" degress";
	}
}
