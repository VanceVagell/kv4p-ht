/*
 * Audio FSK modem for AX25 (1200 Baud, 1200/2200Hz).
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

public class Afsk1200Modulator 
  implements PacketModulator
  //implements HalfduplexSoundcardClient 
  {

	private float phase_inc_f0, phase_inc_f1;
	private float phase_inc_symbol;
	
	//private Packet packet; // received packet
	private int sample_rate;

	public Afsk1200Modulator(int sample_rate) {
		this.sample_rate = sample_rate;
		phase_inc_f0 = (float) (2.0*Math.PI*1200.0/sample_rate);
		phase_inc_f1 = (float) (2.0*Math.PI*2200.0/sample_rate);
		phase_inc_symbol = (float) (2.0*Math.PI*1200.0/sample_rate);
	}
  	
	//private float phase_f0, phase_f1;	
	//private int t; // running sample counter

	//private float f1cos, f1sin, f0cos, f0sin;
	
	/**************************/
	/*** Packet Transmitter ***/
	/**************************/

	public void setTxDelay(int delay) { tx_delay = delay; };
	public void setTxTail (int delay) { tx_tail  = delay; };
	
	private static enum TxState {
		IDLE,
		PREAMBLE,
		DATA,
		TRAILER
	};
	private TxState tx_state = TxState.IDLE;
	private byte[]  tx_bytes;
	private int     tx_index;
	private int     tx_delay = 20; // default is 20*10ms = 500ms
	private int     tx_tail  = 0;  // obsolete
	private float   tx_symbol_phase, tx_dds_phase;

	private float[] tx_samples;
	private int     tx_last_symbol;
	private int     tx_stuff_count;

	public void prepareToTransmitFlags(int seconds) {
		if (tx_state != TxState.IDLE) {
			System.err.println("Warning: trying to trasmit while Afsk1200 modulator is busy, discarding");
			return;
		}
		tx_bytes = null; // no data
		tx_state = TxState.PREAMBLE;
		tx_index = (int) Math.ceil((double) seconds / (8.0/1200.0)); // number of flags to transmit
		//if (transmit_controller!=null) transmit_controller.startTransmitter();
		tx_symbol_phase = tx_dds_phase = 0.0f;
	}
	
	public void prepareToTransmit(Packet p) {
		if (tx_state != TxState.IDLE) {
			System.err.println("Warning: trying to trasmit while Afsk1200 modulator is busy, discarding");
			return;
		}
		tx_bytes = p.bytesWithCRC(); // This includes the CRC
		tx_state = TxState.PREAMBLE;
		tx_index = (int) Math.ceil(tx_delay * 0.01 / (8.0/1200.0)); // number of flags to transmit
		if (tx_index < 1) tx_index = 1;
		//if (transmit_controller!=null) transmit_controller.startTransmitter();
		tx_symbol_phase = tx_dds_phase = 0.0f;
	}

	public float[] getTxSamplesBuffer() {
		if (tx_samples==null) {
			// each byte makes up to 10 symbols,
			// each symbol takes (1/1200)s to transmit.
			// not sure if it's really necessary to add one.		
			tx_samples = new float[ (int) Math.ceil((10.0/1200.0) * sample_rate) + 1 ];
		}		
		return tx_samples;
	}

  private int generateSymbolSamples(int symbol, float[] s, int position) {
		int count = 0;
		while (tx_symbol_phase < (float) (2.0*Math.PI)) {
			s[position] = (float) Math.sin(tx_dds_phase);
			
			if (symbol==0) tx_dds_phase += phase_inc_f0;
			else           tx_dds_phase += phase_inc_f1;
			
			tx_symbol_phase += phase_inc_symbol;
			
			//if (tx_symbol_phase > (float) (2.0*Math.PI)) tx_symbol_phase -= (float) (2.0*Math.PI);
			if (tx_dds_phase    > (float) (2.0*Math.PI)) tx_dds_phase    -= (float) (2.0*Math.PI);
			
			position++;
			count++;
		}
		
		tx_symbol_phase -= (float) (2.0*Math.PI);

		return count;		
	}
	
	private int byteToSymbols(int bits, boolean stuff) {
		int symbol;
		int position = 0;
		int n;
  	//System.out.printf("byte=%02x stuff=%b\n",bits,stuff);
	  for (int i=0; i<8; i++) {
	  	int bit = bits & 1;
	  	//System.out.println("i="+i+" bit="+bit);
	  	bits = bits >> 1;
	  	if (bit == 0) { // we switch sybols (frequencies)
	  		symbol = (tx_last_symbol == 0) ? 1 : 0;
	  		n = generateSymbolSamples(symbol, tx_samples, position);
	  		position += n;

	  		if (stuff) tx_stuff_count = 0;
	  		tx_last_symbol = symbol;
	  	} else {
	  		symbol = (tx_last_symbol == 0) ? 0 : 1;
	  		n = generateSymbolSamples(symbol, tx_samples, position);
	  		position += n;

	  		if (stuff) tx_stuff_count++;
	  		tx_last_symbol = symbol;
	  		
	  		if (stuff && tx_stuff_count==5) {
	  			// send a zero
	  			//System.out.println("stuffing a zero bit!");
		  		symbol = (tx_last_symbol == 0) ? 1 : 0;
		  		n = generateSymbolSamples(symbol, tx_samples, position);
		  		position += n;

		  		tx_stuff_count = 0;
		  		tx_last_symbol = symbol;
	  		}
	  	}
	  }
	  //System.out.println("generated "+position+" samples");
	  return position;
	}
	
	public int getSamples() {
		int count;
		
		assert(tx_samples != null);

		switch (tx_state) {
		case IDLE: 
			return 0;
		case PREAMBLE:
			count = byteToSymbols(0x7E,false);
						
			tx_index--;
			if (tx_index==0) {
				tx_state = TxState.DATA;
				tx_index = 0;
				tx_stuff_count = 0;
			}
			break;
		case DATA: 
			if (tx_bytes==null) { // we just wanted to transmit tones to adjust the transmitter
				tx_state = TxState.IDLE;
				//if (transmit_controller!=null) transmit_controller.stopTransmitter();
				return 0;
			}
			//System.out.printf("Data byte %02x\n",tx_bytes[tx_index]);
			count = byteToSymbols(tx_bytes[tx_index],true);

			tx_index++;
			if (tx_index==tx_bytes.length) {
				tx_state = TxState.TRAILER;
				if (tx_tail <= 0) { // this should be the normal case
					tx_index = 2;
				} else {
					tx_index = (int) Math.ceil(tx_tail * 0.01 / (8.0/1200.0)); // number of flags to transmit
					if (tx_tail < 2) tx_tail = 2;
				}
			}
			break;
		case TRAILER:
			count = byteToSymbols(0x7E,false);

			tx_index--;
			if (tx_index==0) {
				tx_state = TxState.IDLE;
				//if (transmit_controller!=null) transmit_controller.stopTransmitter();
			}
			break;
		default: 
			assert(false);
			count = -1;
			break;
		}
		
		return count;
	}
}
