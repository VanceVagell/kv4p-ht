package org.jdamico.javax25;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Properties;

import org.jdamico.javax25.PacketHandlerImpl;
import org.jdamico.javax25.ax25.Afsk1200Modulator;
import org.jdamico.javax25.ax25.Afsk1200MultiDemodulator;
import org.jdamico.javax25.ax25.Packet;
import org.jdamico.javax25.ax25.PacketDemodulator;
import org.jdamico.javax25.radiocontrol.SerialTransmitController;
import org.jdamico.javax25.radiocontrol.TransmitController;
import org.jdamico.javax25.soundcard.Soundcard;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Unit test for simple App.
 */
public class AppTest extends TestCase
{
	/**
	 * Create the test case
	 *
	 * @param testName name of the test case
	 */
	public AppTest( String testName )
	{
		super( testName );
	}

	/**
	 * @return the suite of tests being tested
	 */
	public static Test suite()
	{
		return new TestSuite( AppTest.class );
	}

	/**
	 * Rigourous Test :-)
	 */
	public void testApp()
	{
		Soundcard.enumerate();
		SerialTransmitController.enumerate();

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

		/*** create test packet to transmit ***/

		String callsign = "NOCALL";
		System.out.println("Callsign in test packet is: "+callsign);

		Packet packet = new Packet("APRS",
				callsign,
				new String[] {"WIDE1-1", "WIDE2-2"},
				Packet.AX25_CONTROL_APRS,
				Packet.AX25_PROTOCOL_NO_LAYER_3,
				">Java Modem Test Packet".getBytes());

		System.out.println(packet.toString());


		/*** write a test packet to a text file ***/

		String fout = "/tmp/japrs.txt";
		if (fout != null) {	
			System.out.printf("Writing transmit packets to <%s>\n",fout);
			FileOutputStream f = null;
			PrintStream ps = null;
			try {
				f = new FileOutputStream(fout);
				ps = new PrintStream(f);
			} catch (FileNotFoundException fnfe) {
				System.err.println("File "+fout+" not found: "+fnfe.getMessage());

			}
			mod.prepareToTransmit(packet);
			int n;
			float[] tx_samples = mod.getTxSamplesBuffer();
			while ((n = mod.getSamples()) > 0) {
				for (int i=0; i<n; i++)
					ps.printf("%09e\n",tx_samples[i]);
			}
			ps.close();
		}

		/*** preparing to generate or capture audio packets ***/

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

		/*** sound a test packet and exit ***/

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
