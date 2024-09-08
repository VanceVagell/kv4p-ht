/*
 * 
 * 
 * Copyright (C) Sivan Toledo, 2012
 * 
 *      This program is free software; you can redistribute it and/or modify
 *      it under the terms of the GNU General Public License as published by
 *      the Free Software Foundation; either version 2 of the License, or
 *      (at your option) any later version.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU General Public License for more details.
 *
 *      You should have received a copy of the GNU General Public License
 *      along with this program; if not, write to the Free Software
 *      Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package com.vagell.kv4pht.javAX25.ax25;

public class Filter {
	public final static float[] xxxLOWPASS_1200_48000_39 = {
			1.230588e-004f,
			4.146753e-004f,
			8.667268e-004f,
			1.606401e-003f,
			2.759319e-003f,
			4.437769e-003f,
			6.729266e-003f,
			9.686389e-003f,
			1.331883e-002f,
			1.758836e-002f,
			2.240733e-002f,
			2.764087e-002f,
			3.311284e-002f,
			3.861510e-002f,
			4.391962e-002f,
			4.879247e-002f,
			5.300873e-002f,
			5.636712e-002f,
			5.870346e-002f,
			5.990168e-002f,
			5.990168e-002f,
			5.870346e-002f,
			5.636712e-002f,
			5.300873e-002f,
			4.879247e-002f,
			4.391962e-002f,
			3.861510e-002f,
			3.311284e-002f,
			2.764087e-002f,
			2.240733e-002f,
			1.758836e-002f,
			1.331883e-002f,
			9.686389e-003f,
			6.729266e-003f,
			4.437769e-003f,
			2.759319e-003f,
			1.606401e-003f,
			8.667268e-004f,
			4.146753e-004f,
			1.230588e-004f
	};
	
	public final static float[] xxxBANDPASS_1150_1250_48000_39 = {
			-7.469398e-003f,
			-7.830087e-003f,
			-8.975283e-003f,
			-1.060416e-002f,
			-1.227981e-002f,
			-1.347975e-002f,
			-1.365708e-002f,
			-1.230591e-002f,
			-9.024217e-003f,
			-3.567459e-003f,
			4.112634e-003f,
			1.384849e-002f,
			2.525863e-002f,
			3.777121e-002f,
			5.066739e-002f,
			6.314018e-002f,
			7.436358e-002f,
			8.356498e-002f,
			9.009411e-002f,
			9.348162e-002f,
			9.348162e-002f,
			9.009411e-002f,
			8.356498e-002f,
			7.436358e-002f,
			6.314018e-002f,
			5.066739e-002f,
			3.777121e-002f,
			2.525863e-002f,
			1.384849e-002f,
			4.112634e-003f,
			-3.567459e-003f,
			-9.024217e-003f,
			-1.230591e-002f,
			-1.365708e-002f,
			-1.347975e-002f,
			-1.227981e-002f,
			-1.060416e-002f,
			-8.975283e-003f,
			-7.830087e-003f,
			-7.469398e-003f,
	};

	public final static float[] xxxBANDPASS_2150_2250_48000_39 = {
			5.961802e-003f,
			4.708974e-003f,
			3.164012e-003f,
			4.947263e-004f,
			-4.027708e-003f,
			-1.075260e-002f,
			-1.944758e-002f,
			-2.922208e-002f,
			-3.860217e-002f,
			-4.575104e-002f,
			-4.879775e-002f,
			-4.621260e-002f,
			-3.715482e-002f,
			-2.172039e-002f,
			-1.034705e-003f,
			2.283710e-002f,
			4.715506e-002f,
			6.890336e-002f,
			8.525608e-002f,
			9.402762e-002f,
			9.402762e-002f,
			8.525608e-002f,
			6.890336e-002f,
			4.715506e-002f,
			2.283710e-002f,
			-1.034705e-003f,
			-2.172039e-002f,
			-3.715482e-002f,
			-4.621260e-002f,
			-4.879775e-002f,
			-4.575104e-002f,
			-3.860217e-002f,
			-2.922208e-002f,
			-1.944758e-002f,
			-1.075260e-002f,
			-4.027708e-003f,
			4.947263e-004f,
			3.164012e-003f,
			4.708974e-003f,
			5.961802e-003f,
	};

	// filter a signal x stored in a cyclic buffer with
	// a FIR filter f
	// The length of x must be larger than the length of the filter.
	public static float filter(float[] x, int j, float[] f) {
		float c = (float) 0.0;
		for (int i=0; i<f.length; i++) {
			c += x[j]*f[i];
			j--;
			if (j==-1) j=x.length - 1;
		}
		return c;
	}
}
