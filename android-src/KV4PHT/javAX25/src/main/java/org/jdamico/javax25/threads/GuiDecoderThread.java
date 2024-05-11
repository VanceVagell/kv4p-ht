package org.jdamico.javax25.threads;

import java.util.Properties;

import org.jdamico.javax25.GuiApp;
import org.jdamico.javax25.PacketHandlerImpl;
import org.jdamico.javax25.ax25.Afsk1200Modulator;
import org.jdamico.javax25.ax25.Afsk1200MultiDemodulator;
import org.jdamico.javax25.ax25.PacketDemodulator;
import org.jdamico.javax25.soundcard.Soundcard;

public class GuiDecoderThread extends Thread {
	private String input;
	private String output;
	public GuiDecoderThread(String input, String output) {
		this.input = input;
		this.output = output;
		
	}

	public void run() {
		Properties p = System.getProperties();

		int rate = 48000;

		PacketHandlerImpl t = new PacketHandlerImpl();
		PacketDemodulator multi = null;
		try {
			multi = new Afsk1200MultiDemodulator(rate,t);
			GuiApp.mod = new Afsk1200Modulator(rate);
		} catch (Exception e) {
			System.out.println("Exception trying to create an Afsk1200 object: "+e.getMessage());
		}


		/*** preparing to generate or capture audio packets ***/

		int buffer_size = -1;
		try {
			// our default is 100ms
			buffer_size = Integer.parseInt(p.getProperty("latency", "100").trim());
		} catch (Exception e){
			System.err.println("Exception parsing buffersize "+e.toString());
		}

		GuiApp.sc = new Soundcard(rate,input,output,buffer_size,multi,GuiApp.mod);
		
		

		GuiApp.sc.displayAudioLevel();


		/*** listen for incoming packets ***/

		if (input != null) {
			String listeningMsg = "Listening for packets\n";
			if(Soundcard.jTextArea != null) {
				Soundcard.jTextArea.setText(Soundcard.jTextArea.getText()+listeningMsg);
			}else System.out.printf(listeningMsg);	
			GuiApp.sc.receive();
		}else {
			String errMsg = "Input is null!";
			if(Soundcard.jTextArea != null) {
				Soundcard.jTextArea.setText(Soundcard.jTextArea.getText()+errMsg);
			}else System.err.println(errMsg);
		}
	}

}
