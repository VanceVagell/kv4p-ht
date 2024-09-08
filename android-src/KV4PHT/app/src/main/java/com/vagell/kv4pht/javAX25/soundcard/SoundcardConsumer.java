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

package com.vagell.kv4pht.javAX25.soundcard;

public abstract class SoundcardConsumer {
	
	int   sample_rate;
	float peak = 0.0f;
	float decay;
	//private final float oneovermax = 1.0f/32768.0f;
	public SoundcardConsumer(int sample_rate) {
		this.sample_rate = sample_rate;
		decay = (float) ( 1.0 - Math.exp(Math.log(0.5)/(double)sample_rate) );
		System.out.printf("decay = %e\n", (double)decay);

	}
	//public void addSamples(float[] s) {
	//	addSamples(s,s.length);
	//}

	protected abstract void addSamplesPrivate(float[] s, int n);
	
	public int peak() {
		return (int) Math.ceil(peak*100.0f);
	}

	public void addSamples(float[] s, int n) {
		for (int i=0; i<n; i++) {
			float abs = s[i] >= 0 ? s[i] : -s[i];
			if (abs > peak) peak = abs;
			else peak = peak  - (peak * decay);
			
			//if (peak > 1.0f)
			//  System.out.printf("sample=%f peak=%f decay=%f\n", abs,peak,decay);
		}
		addSamplesPrivate(s,n);
	}
}
