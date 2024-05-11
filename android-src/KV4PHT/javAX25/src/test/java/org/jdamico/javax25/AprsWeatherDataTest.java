package org.jdamico.javax25;

import static org.junit.Assert.*;

import java.util.Properties;

import org.jdamico.javax25.ax25.Afsk1200Modulator;
import org.jdamico.javax25.ax25.Afsk1200MultiDemodulator;
import org.jdamico.javax25.ax25.Packet;
import org.jdamico.javax25.ax25.PacketDemodulator;
import org.jdamico.javax25.radiocontrol.SerialTransmitController;
import org.jdamico.javax25.radiocontrol.TransmitController;
import org.jdamico.javax25.soundcard.Soundcard;
import org.junit.Before;
import org.junit.Test;

public class AprsWeatherDataTest {

	@Before
	public void setUp() throws Exception {
	}

	@Test
	public void test() {
		String callsign = "NOCALL";
		System.out.println("Callsign in test packet is: "+callsign);
		
		String hms_position = "092122z2332.53S/04645.51W";
		String weather_data = "_220/004g005t-07r000p000P000h50b09900xSCI";
		
		String complete_weather_data = hms_position+weather_data;
		
		
		
		Packet packet = new Packet("APRS",
				callsign,
				new String[] {"WIDE1-1", "WIDE2-2"},
				Packet.AX25_CONTROL_APRS,
				Packet.AX25_PROTOCOL_NO_LAYER_3,
				complete_weather_data.getBytes());
		
		System.out.println(packet);
		
		
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
		
		String input = null;
		String output = "PulseAudio Mixer";

		int buffer_size = -1;
		try {
			// our default is 100ms
			buffer_size = Integer.parseInt(p.getProperty("latency", "100").trim());
		} catch (Exception e){
			System.err.println("Exception parsing buffersize "+e.toString());
		}

		Soundcard sc = new Soundcard(rate,input,output,buffer_size,multi,mod);

		if (p.containsKey("audio-level")) {
			sc.displayAudioLevel();
		}

		/*** generate test tones and exit ***/

		TransmitController ptt = null;

		int tones_duration = -1; // in seconds
		try {
			tones_duration = Integer.parseInt(p.getProperty("tones", "-1").trim());
		} catch (Exception e){
			System.err.println("Exception parsing tones "+e.toString());
		}
		if (tones_duration > 0) {
			//sc.openSoundOutput(output);			
			mod.prepareToTransmitFlags(tones_duration);
			sc.transmit();
		}

		if (output != null) {
			//sc.openSoundOutput(output);			
			mod.prepareToTransmit(packet);
			System.out.printf("Start transmitter\n");
			//sc.startTransmitter();
			if (ptt != null) ptt.startTransmitter();
			sc.transmit();
			System.out.printf("Stop transmitter\n");
			if (ptt != null) ptt.stopTransmitter();
			if (ptt != null) ptt.close();
			//if (ptt != null) ptt.stopTransmitter());
			//sc.stopTransmitter();
			//int n;
			//while ((n = ae.afsk.getSamples()) > 0){
			// 	ae.afsk.addSamples(Arrays.copyOf(tx_samples, n));
			//}
		}
		
	}

}
