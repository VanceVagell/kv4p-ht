package org.jdamico.javax25;

import java.util.Properties;

import org.jdamico.javax25.ax25.Afsk1200Modulator;
import org.jdamico.javax25.ax25.Afsk1200MultiDemodulator;
import org.jdamico.javax25.ax25.PacketDemodulator;
import org.jdamico.javax25.soundcard.Soundcard;

public class App {
	public static void main( String[] args) {
		Soundcard.enumerate();

		Properties p = System.getProperties();

		int rate = 48000;
		int filter_length = 32;

		PacketHandlerImpl t = new PacketHandlerImpl();
		Afsk1200Modulator mod = null;
		PacketDemodulator multi = null;
		try {
			multi = new Afsk1200MultiDemodulator(rate,t);
			mod = new Afsk1200Modulator(rate);
		} catch (Exception e) {
			System.out.println("Exception trying to create an Afsk1200 object: "+e.getMessage());
		}


		/*** preparing to generate or capture audio packets ***/

		String input = p.getProperty("input", null);
		String output = p.getProperty("output", null);

		int buffer_size = -1;
		try {
			// our default is 100ms
			buffer_size = Integer.parseInt(p.getProperty("latency", "100").trim());
		} catch (Exception e){
			System.err.println("Exception parsing buffersize "+e.toString());
		}

		Soundcard sc = new Soundcard(rate,input,output,buffer_size,multi,mod);

		
			sc.displayAudioLevel();
		


		/*** listen for incoming packets ***/

		if (input != null) {
			System.out.printf("Listening for packets\n");
			//sc.openSoundInput(input);			
			sc.receive();
		}else {
			System.err.println("Input is null!");
		}

	}
}
