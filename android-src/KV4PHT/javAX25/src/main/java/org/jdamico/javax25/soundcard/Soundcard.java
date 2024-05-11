/*
 * Soundcard: this class interfaces clients (such as javAPRSsrvr or trackers, etc)
 * to half-duplex modems like AX25's Afsk1200.
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
package org.jdamico.javax25.soundcard;

//import java.io.FileNotFoundException;
//import java.io.FileOutputStream;
//import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Properties;
import java.util.List;
import java.util.Map;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.JLabel;
import javax.swing.JTextArea;

public class Soundcard {
	
	
	public static JTextArea jTextArea = null;
	public static JLabel audioLevelValue = null;
	public static Map<Long, String> receivedPackedMap;
	public static boolean running = true;

	private int rate;
	//private final int channels = 2;
	//private final int samplebytes = 4;
	private final int channels = 1;
	private final int samplebytes = 2;

	private TargetDataLine tdl = null;
	private SourceDataLine sdl = null;
	private byte[] capture_buffer; 

	private boolean display_audio_level = false;

	//private Afsk1200 afsk;
	//private HalfduplexSoundcardClient afsk;
	private SoundcardConsumer consumer;
	private SoundcardProducer producer;

	private int latency_ms;

	public Soundcard(int rate, 
			String input_device, 
			String output_device, 
			int latency_ms,
			//HalfduplexSoundcardClient afsk
			SoundcardConsumer consumer,
			SoundcardProducer producer
			) {
		
		Soundcard.running = true;
		this.rate = rate;
		//this.afsk = afsk;
		this.producer = producer;
		this.consumer = consumer;
		this.latency_ms = latency_ms;
		if (input_device  != null) openSoundInput (input_device);
		if (output_device != null) openSoundOutput(output_device);
		
	}

	public void transmit() {	
		sdl.flush();
		sdl.start();

		int n;
		float[] samples = producer.getTxSamplesBuffer();
		byte[] buffer = new byte[2*samples.length];
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

		while ((n = producer.getSamples()) > 0){
			bb.rewind();
			for (int i=0; i<n; i++){
				// Convert to a 16-bit signed integer and encode as little endian
				short s = (short) Math.round(32767.0f * samples[i]);
				bb.putShort(s);
			}
			sdl.write(buffer, 0, 2*n);	  	
		}		
		sdl.drain(); // wait until all data has been sent
		sdl.stop();
	}


	public static void enumerate() {
		Mixer.Info[] mis = AudioSystem.getMixerInfo();

		System.out.printf("Available sound devices:\n");

		for (Mixer.Info mi: mis) {
			String name = mi.getName();
			System.out.println("  "+mi.getName()+": "+mi.getVendor()+": "+mi.getDescription());

			Line.Info[] lis;
			lis = AudioSystem.getMixer(mi).getSourceLineInfo();
			for (Line.Info li: lis) {
				if (SourceDataLine.class.equals(li.getLineClass()))
					System.out.println("  output: "+name);
				//System.out.println("    output device = "+li.getLineClass());
			}
			lis = AudioSystem.getMixer(mi).getTargetLineInfo();
			for (Line.Info li: lis) {
				if (TargetDataLine.class.equals(li.getLineClass())) {
					System.out.println("  input : "+name);

				}


				//System.out.println("    input device = "+li.getLineClass());
				//System.out.println("    isInstance = "+li.getLineClass().isInstance(TargetDataLine.class));
				//System.out.println("    isInstance = "+));
			}			
		}		
	}


	public static List<Mixer.Info> getInputDevicesLst() {
		List<Mixer.Info> lst = new ArrayList<>();
		Mixer.Info[] mis = AudioSystem.getMixerInfo();

		for (Mixer.Info mi: mis) {
			Line.Info[] lis;
			lis = AudioSystem.getMixer(mi).getTargetLineInfo();
			for (Line.Info li: lis) {
				if (TargetDataLine.class.equals(li.getLineClass())) {
					lst.add(mi);

				}
				
			}			
		}
		return lst;
	}



	public void displayAudioLevel() { display_audio_level = true; }

	public void receive() {		
		int j = 0;
		int buffer_size_in_samples = (int) Math.round(latency_ms * ((double) rate / 1000.0) / 4.0);
		capture_buffer = new byte[2*buffer_size_in_samples];
		if (tdl==null) {
			String errMsg = "No sound input device, receiver exiting.\n";
			if(Soundcard.jTextArea != null) {
				Soundcard.jTextArea.setText(Soundcard.jTextArea.getText()+errMsg);
			}else System.err.println(errMsg);
			return;
		} 
		tdl.flush();
		//tdl.drain();
		float min =  1.0f;
		float max = -1.0f;
		ByteBuffer bb = ByteBuffer.wrap(capture_buffer).order(ByteOrder.LITTLE_ENDIAN);
		float[] f = new float[capture_buffer.length / 2];
		System.err.printf("Listening for packets\n");
		while (Soundcard.running ) {
			int rv;
			rv = tdl.read(capture_buffer, 0, capture_buffer.length);
			bb.rewind();
			//System.out.printf("read %d bytes of audio\n",rv);
			for (int i=0; i<rv/2; i++) {
				short s = bb.getShort();
				f[i] = (float) s / 32768.0f;	
				if (!display_audio_level) continue;
				j ++;
				//System.out.printf("j=%d\n",j);			  	
				//if (f[i] > max) max = f[i];
				//if (f[i] < min) min = f[i];
				if (j == rate) {
					//System.err.printf("Audio in range [%f, %f]\n",min,max);
					if(audioLevelValue == null) System.err.printf("Audio level %d\n",consumer.peak());
					else {
						audioLevelValue.setText(String.valueOf(consumer.peak()));
					}
					j = 0;
					//min =  1.0f;
					//max = -1.0f;
				}
			}
			consumer.addSamples(f,rv/2);
		}	
	}

	private void openSoundInput(String mixer) {
		AudioFormat fmt;
		fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 
				rate, 
				16, /* bits per value */
				channels,  /* stereo */ 
				samplebytes,  /* sample size */
				rate, 
				false /* false=little endian */);

		Mixer.Info[] mis = AudioSystem.getMixerInfo();

		//TargetDataLine tdl = null;
		for (Mixer.Info mi: mis) {
			if (mi.getName().equalsIgnoreCase(mixer)) {
				try {
					tdl = AudioSystem.getTargetDataLine(fmt, mi);
					System.err.println("Opened an input sound device (target line): "+mixer);			  
				} catch (LineUnavailableException lue) {
					System.err.println("Sound input device not available: "+mixer);
				}
				catch (IllegalArgumentException iae) {
					System.err.println("Failed to open an input sound device: "+iae.getMessage());
				}
			}			
		}

		if (tdl == null) {
			System.err.println("Sound device not found (or is not an input device): "+mixer);
			return;
		}

		//Control[] controls = tdl.getControls();
		//for (Control c: controls) {
		//	System.out.println("  Control: +"+c.getType().getClass());
		//}

		int buffer_size_in_samples = (int) Math.round(latency_ms * ((double) rate / 1000.0));
		try {
			tdl.open(fmt,2*buffer_size_in_samples);
			tdl.start();
		} catch (LineUnavailableException lue) {
			tdl=null;
			System.err.println("Cannot open input sound device");
		}		
	}

	private void openSoundOutput(String mixer) {
		//System.out.println("Playback");
		AudioFormat fmt;
		fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 
				rate, 
				16, /* bits per value */
				channels,  /* stereo */ 
				samplebytes,  /* sample size */
				rate, 
				false /* false=little endian */);

		Mixer.Info[] mis = AudioSystem.getMixerInfo();

		//TargetDataLine tdl = null;
		for (Mixer.Info mi: mis) {
			if (mi.getName().equalsIgnoreCase(mixer)) {
				//System.out.println("@@@");
				try {
					sdl = AudioSystem.getSourceDataLine(fmt, mi);
					System.err.println("Opened a sound output device (source data line): "+mixer);			  
				} catch (LineUnavailableException lue) {
					System.err.println("Sound output device not available: "+mixer);
				} catch (IllegalArgumentException iae) {
					System.err.println("Failed to open a sound output device: "+iae.getMessage());
				}
			}			
		}

		if (sdl == null) {
			System.err.println("Sound output device not found (or is not a playback device): "+mixer);
			return;
		}

		int buffer_size_in_samples = (int) Math.round(latency_ms * ((double) rate / 1000.0));
		try {
			sdl.open(fmt,2*buffer_size_in_samples);
			//sdl.start();
		} catch (LineUnavailableException lue) {
			sdl=null;
			System.err.println("Cannot open sound output device device");
		}
	}

	public static List<Mixer.Info> getOutputDevicesLst() {
		List<Mixer.Info> lst = new ArrayList<>();
		Mixer.Info[] mis = AudioSystem.getMixerInfo();

		for (Mixer.Info mi: mis) {
			Line.Info[] lis;
			lis = AudioSystem.getMixer(mi).getSourceLineInfo();
			for (Line.Info li: lis) {
				if (SourceDataLine.class.equals(li.getLineClass())) {
					lst.add(mi);

				}
			}			
		}
		return lst;
	}


}
