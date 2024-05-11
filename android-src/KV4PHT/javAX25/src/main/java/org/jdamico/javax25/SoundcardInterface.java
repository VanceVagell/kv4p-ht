package org.jdamico.javax25;
import java.util.Properties;

import org.jdamico.javax25.ax25.Afsk1200Modulator;
import org.jdamico.javax25.ax25.PacketDemodulator;
import org.jdamico.javax25.ax25.PacketHandler;
import org.jdamico.javax25.radiocontrol.SerialTransmitController;
import org.jdamico.javax25.radiocontrol.TransmitController;
import org.jdamico.javax25.soundcard.Soundcard;
import org.jdamico.javax25.threads.SoundcardWriteThread;
import org.jdamico.javax25.threads.TNCConnectThread;



/**
 * An interface to Sivan Toledo's sound-card modem for javAPRSIGate.
 * <BR>
 * Some code used by permission from Roger Bille SM5NRK
 */
final public class SoundcardInterface extends TNCConnectThread
                                      implements PacketHandler
{
	
	private String soundin, soundout;
	private int    rate;
	private int    latency_ms;
	//sivantoledo.ax25.Afsk1200 afsk;
	Afsk1200Modulator modulator;
	PacketDemodulator demodulator;
	Soundcard sc;
		
	private int TXDelay = 20;          // KISS specification default is 50 (= 500ms)
	private int Persist = 255;   // KISS specification default is 63 (p = 0.25)
	private int SlotTime = 0;          // KISS specification default is 10 (= 100ms)
	private int FullDuplex = 0;        // KISS specification default is 0  (= half duplex)
	private int TXTail = 10;           // KISS specification says this is obsolete
		
	//private final nsByteArrayOutputStream inbuf = new nsByteArrayOutputStream(1024);
	
	private TransmitController ptt;

	SoundcardInterface()
	{
		String version = "Soundcard Interface; Copyright � 2005 - Pete Loveall AE5PL;";
	}
	
	/**
	 * Closes the serial port connection.
	 */
	public final synchronized void close()
	{
	}

	/**
	 * Returns version info for inclusion on status page.
	 */
	public String getVersion()
	{
		return version+"; Sivan Toledo 2012";
	}

	public synchronized void init(Properties configuration)
	{
		initParams(configuration);
		if (firstTime)
		{
			firstTime = false;

			//try {TNCPort = Integer.parseInt(configuration.getProperty("SoundcardTNCDevice", "1").trim());}
			//catch (Exception e){System.err.println("Exception parsing KISSTNCPortNumber "+e.toString());}
			//tncPort =(byte)((TNCPort-1)<<4);
			try {TXDelay = (Integer.parseInt(configuration.getProperty("KISSTXDelay", "200").trim())/10);}
			catch (Exception e){System.err.println("Exception parsing KISSTXDelay "+e.toString());}
			try {Persist = Integer.parseInt(configuration.getProperty("KISSPersist", "255").trim());}
			catch (Exception e){System.err.println("Exception parsing KISSPersist "+e.toString());}
			try {SlotTime = (Integer.parseInt(configuration.getProperty("KISSSlotTime", "0").trim()));}
			catch (Exception e){System.err.println("Exception parsing KISSPersist "+e.toString());}
			boolean fulldux = false;
			try {fulldux = Boolean.valueOf(configuration.getProperty("KISSFullDuplex", "false").trim()).booleanValue();}
			catch (Exception e){System.err.println("Exception parsing KISSFullDuplex "+e.toString());}
			if (fulldux) FullDuplex = 1;
			try {TXTail = (Integer.parseInt(configuration.getProperty("KISSTXTail", "100").trim())/10);}
			catch (Exception e){System.err.println("Exception parsing KISSTXTail "+e.toString());}

			try {rate = Integer.parseInt(configuration.getProperty("SoundcardSampleRate", "9600").trim());}
			catch (Exception e){System.err.println("Exception parsing SoundcardSampleRate "+e.toString());}

			try {latency_ms = Integer.parseInt(configuration.getProperty("SoundcardLatency", "100").trim());}
			catch (Exception e){System.err.println("Exception parsing SoundcardLatency "+e.toString());}
			
			//if (!configuration.containsKey("SoundCardName"))
			//	configuration.put("SerialPortName", configuration.getProperty("KISSPortName", ""));
			String soundcard = configuration.getProperty("SoundcardName", "default").trim();
			soundin   = configuration.getProperty("SoundcardInputName", "default").trim();
			soundout  = configuration.getProperty("SoundcardOutputName", "default").trim();
			if (soundin.equals("default"))  soundin  = soundcard;
			if (soundout.equals("default")) soundout = soundcard;
			
	  	System.err.println("Starting up Afsk1200 modem on ["+soundin+", "+soundout+"] at "+rate+" samples/s");

			try {
	  	  modulator   = new org.jdamico.javax25.ax25.Afsk1200Modulator(rate);
	  	  demodulator = new org.jdamico.javax25.ax25.Afsk1200MultiDemodulator(rate,this);
	  	  //afsk = new sivantoledo.ax25.Afsk1200(rate, this);
	  	  modulator.setTxDelay(TXDelay);
			} catch (Exception e) {
				System.err.println("Afsk1200 constructor exception: "+e.getMessage());
				System.exit(1);
			}

			String ptt_port   = configuration.getProperty("PTTPort",   "none").trim();
			String ptt_signal = configuration.getProperty("PTTSignal", "RTS").trim();
			if (!ptt_port.equals("none")) {
				try {
					ptt = new SerialTransmitController(ptt_port,ptt_signal);
					System.err.println("Opened a serial PTT port: "+ptt_port);
				} catch (Exception e) {
					System.err.println("PTT initialization error: "+e.getMessage());
					ptt=null;
				}
			} else {
				System.err.println("Warning: No PTT port (okay for receive only or for VOX)");
			}

			
	  	sc   = new org.jdamico.javax25.soundcard.Soundcard(rate, soundin, soundout, latency_ms, 
	  	                                      demodulator, modulator);
			new Thread(this, "SoundInterface Read").start();
		}
	}
	
	public void handlePacket(byte[] packet) {
		try {
			TNCInterface.AX25Packet tnc = new TNCInterface.AX25Packet((byte)0, packet);
			if (tnc.restOfPacket.length > 0)
				queue.putOnQueue(tnc);
		}
		catch (Exception e){}
	}
	
	public void run() {
		//if (serial == null) return;
		// Init TNC
		try
		{
			writer = new SoundcardWriteThread(demodulator, modulator,
					                              sc,
					                              (double) Persist / 255.0,SlotTime,
					                              ptt);
		}
		catch (Exception e)
		{
			System.err.println("Error initializing Soundcard "+e.toString());
			return;
		}
		
		sc.receive();
	}
}
