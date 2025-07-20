/*
 * Audio FSK modem for AX25 (1200 Baud, 1200/2200Hz).
 * This class combines two demodulators into one packet stream,
 * to handle both de-emphasized and flat (discriminator) audio.
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

//import java.util.Arrays;

public class Afsk1200MultiDemodulator extends PacketDemodulator {

	private class InnerHandler implements PacketHandler {
		int d;
		public InnerHandler(int demodulator) {
			d = demodulator;
		}
		public void handlePacket(byte[] bytes) {
			Afsk1200MultiDemodulator.this.handlePacket(bytes, d);
		}
		
	}
	
	private int  packet_count;
	private long sample_count;
	private byte[] last;
	//private long   last_sample_count;
	private int dup_count;
	//public void incSampleCount() { sample_count++; }
	private int last_demod, d0_count, d6_count, both_count; 
	public void handlePacket(byte[] bytes, int d) {
	//public void handlePacket(byte[] bytes) {
		if (last!=null && d != last_demod && java.util.Arrays.equals(last, bytes)) {
			//&& sample_count <= last_sample_count + max_sample_delay ) {
			dup_count++;
			//System.err.printf("Duplicate, %d so far\n",dup_count);
			
			if (last_demod == 0)
				d0_count--;
			else
				d6_count--;
			both_count++;				

			//last_demod = d;
		} else {
			packet_count++;
			
			//System.err.printf("Non duplicate, d=%d last_d=%d same-data=%b\n",d, last_demod,
			//		Arrays.equals(last, bytes));
			//
			//if (last!=null) {
			//	System.err.printf("lengths = %d %d\n",bytes.length,last.length);
			//	for (int i=0; i<bytes.length; i++) {
			//		if (i>=last.length) break;
			//		System.err.printf("%02x %02x\n",bytes[i],last[i]);
			//	}
			//}

			if (d == 0)
				d0_count++;
			else
				d6_count++;
			
			last_demod = d;
			//System.out.println(""+packet_count);
			last = Arrays.copyOf(bytes,bytes.length);
			//last_sample_count = sample_count;

			if (h!=null) h.handlePacket(bytes);
		}
		//System.err.printf("d0=%d d6=%d both=%d total=%d\n",d0_count,d6_count,both_count,packet_count);
	}
	
	private PacketHandler h;
	PacketDemodulator d0, d6;
	//private int sample_rate;
	//private int max_sample_delay;

	public Afsk1200MultiDemodulator(int sample_rate, PacketHandler h)  {
  	super(sample_rate);
		//this.sample_rate = sample_rate;
		this.h = h;
		//max_sample_delay = (10 * 8 * sample_rate) / 1200; // a 10 byte delay
	  d0 = new Afsk1200Demodulator(sample_rate,1,0,new InnerHandler(0));
	  d6 = new Afsk1200Demodulator(sample_rate,1,6,new InnerHandler(6));
	}
	protected void addSamplesPrivate(float[] s, int n) {
		sample_count += n;
		d0.addSamples(s, n);
		d6.addSamples(s, n);
	}
	public boolean dcd(){
		return d6.dcd() || d0.dcd();
	}
}
