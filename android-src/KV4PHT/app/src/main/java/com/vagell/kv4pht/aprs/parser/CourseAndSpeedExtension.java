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

/**
 * 
 * @author johng
 * 
 */
public class CourseAndSpeedExtension extends DataExtension implements Serializable {
	private static final long serialVersionUID = 1L;
	private int course;
	private int speed;
	/**
	 * @return the course in degrees true
	 */
	public int getCourse() {
		return course;
	}
	/**
	 * @param course the course to set in degrees true
	 */
	public void setCourse(int course) {
		this.course = course;
	}
	/**
	 * @return the speed in knots
	 */
	public int getSpeed() {
		return speed;
	}
	/**
	 * @param speed the speed to set in knots
	 */
	public void setSpeed(int speed) {
		this.speed = speed;
	}
	
	@Override
	public String toString() {
		return "Moving "+speed+" kts @ "+course+" deg";
	}
	
	/**
	 * @return the current speed in mph and course in degrees in a formatted string
	 */
	@Override
	public String toSAEString() {
		return "Moving "+Utilities.ktsToMph(speed)+" mph @ "+course+" deg";
	}

	/**
	 * @return Enum indicating the data type extention
	*/
	@Override
	public APRSExtensions getType() {
		return APRSExtensions.T_COURSESPEED;
	}
}
