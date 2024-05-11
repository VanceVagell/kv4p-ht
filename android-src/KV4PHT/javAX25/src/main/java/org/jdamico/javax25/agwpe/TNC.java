/*
 * Test program for the Afsk1200 sound-card modem.
 * For examples, see test.bat
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
package org.jdamico.javax25.agwpe;

import java.io.BufferedReader;

import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Properties;

import javax.sound.sampled.*;

import org.jdamico.javax25.ax25.Afsk1200Modulator;
import org.jdamico.javax25.ax25.Afsk1200MultiDemodulator;
import org.jdamico.javax25.ax25.Packet;
import org.jdamico.javax25.ax25.PacketDemodulator;
import org.jdamico.javax25.ax25.PacketHandler;
import org.jdamico.javax25.ax25.PacketModulator;
import org.jdamico.javax25.radiocontrol.SerialTransmitController;
import org.jdamico.javax25.radiocontrol.TransmitController;
import org.jdamico.javax25.soundcard.Soundcard;

public class TNC extends Thread implements PacketHandler {
	
	public void handlePacket(byte[] bytes) {
		System.out.printf("Packet from RF: %s\n",Packet.format(bytes));
		
		byte[] header_data = new byte[37+bytes.length];
	  ByteBuffer bb = ByteBuffer.wrap(header_data).order(ByteOrder.LITTLE_ENDIAN);
	  bb.position(28);
	  bb.putInt(bytes.length);
	  System.arraycopy(bytes, 0, header_data, 36, bytes.length); 
	  try {
			os.write(header_data);
		} catch (IOException e) {
			// We just ignore the error; the socket may have been closed and hopefully will reopen later
		}
	}
	
	public void run() {
		sc.receive();
	}
		
	private Soundcard sc;
	private PacketDemodulator demod;
  private PacketModulator mod;
  private TransmitController ptt;
	
	public TNC(Properties p) {
		/*** create Modem object ***/

		int rate = 48000;
		
		try {
			rate = Integer.parseInt(p.getProperty("rate", "48000").trim());
		} catch (Exception e){
			System.err.println("Exception parsing rate "+e.toString());
		}

		try {
		  demod = new Afsk1200MultiDemodulator(rate,this);
		  mod = new Afsk1200Modulator(rate);
		} catch (Exception e) {
			System.out.println("Exception trying to create an Afsk1200 object: "+e.getMessage());
			System.exit(1);
		}

		/*** preparing to generate or capture audio packets ***/
	  
		String input = p.getProperty("input", null);
		String output = p.getProperty("output", null);

		int buffer_size = -1;
		try {
			// our default is 100ms
			buffer_size = Integer.parseInt(p.getProperty("latency", "100").trim());
			//if (buffer_size ==-1) buffer_size = sc.rate/10;
			//ae.capture_buffer = new byte[2*buffer_size];
		} catch (Exception e){
			System.err.println("Exception parsing buffersize "+e.toString());
		}
		
		sc = new Soundcard(rate,input,output,buffer_size,demod,mod);

		if (p.containsKey("audio-level")) {
			sc.displayAudioLevel();
		}		

		String ptt_port   = p.getProperty("ptt-port", null);
		String ptt_mode   = p.getProperty("ptt-signal", "RTS");

		if (ptt_port != null) {
			try {
				ptt = new SerialTransmitController(ptt_port,ptt_mode);
			} catch (Exception e) {
				System.err.println("PTT initialization error: "+e.getMessage());
				System.exit(1);
			}
		} else {
			System.out.println("No PTT port");
		}
	
	}
	
	private Socket       s;
	private InputStream  is = null;
	private OutputStream os = null;

	public void setSocket(Socket news) {
		if (s!=null) 		
			try {
				s.close();
			} catch (IOException e) {}
		s = news;

		System.out.printf("connection established\n");
		try {
			s.setTcpNoDelay(true);
			is = s.getInputStream();
			os = s.getOutputStream();
		} catch (IOException e) {
			System.err.println("Error getting streams: "+e.getMessage());
		}
	}
	
	public void fromHost() {
		byte[] header = new byte[36];
	  ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		
		while (true) {
			try {
				is.read(header);
			} catch (IOException e) {
				System.err.println("Exception reading from TCP connection: "+e.getMessage());
			}
			bb.rewind();
			int tnc_port = bb.getInt(0);
			byte cmd     = header[4];
			int len      = bb.getInt(28);
			System.out.printf("TNC Port=%d, cmd=%c, len=%d\n", tnc_port,(char)cmd,len);
			byte[] data = new byte[len];
			byte[] Kport = new byte[1];
			try {
				if ((char)cmd=='K') is.read(Kport);
				is.read(data);
			} catch (IOException e) {
				System.err.println("Exception reading from TCP connection: "+e.getMessage());
			}
			
			switch ((char)cmd) {
			case 'K':
				Packet packet = new Packet(data);
				System.out.printf("Sending %s\n",packet);
			  mod.prepareToTransmit(packet);
			  System.out.printf("Start transmitter\n");
			  //sc.startTransmitter();
				if (ptt != null) ptt.startTransmitter();
			  sc.transmit();
			  System.out.printf("Stop transmitter\n");
				if (ptt != null) ptt.stopTransmitter();
				if (ptt != null) ptt.close();

				break;
			default:
				break;
			}		
		}
	}
		
	/*
	 * main program for testing the Afsk1200 modem.
	 */
	
	public static void main(String[] args) {
		
		Properties p = System.getProperties();

		TNC tnc = new TNC(p);
		
		/*** Listen for connection requests ***/
		
		int port=8000;
		try {
			port = Integer.parseInt(p.getProperty("port", "8000").trim());
		} catch (Exception e){
			System.err.println("Exception parsing port: "+e.getMessage());
		}
		
		ServerSocket ss=null;
		
		try {
			ss = new ServerSocket(port);
		} catch (IOException ssioe) {
			// TODO Auto-generated catch block
			System.err.printf("Failed to create server socket: %s\n",ssioe.getMessage());
			System.exit(1);
		}		
		
		while (true) {
			Socket s=null;
			try {
				s = ss.accept();
			} catch (IOException accepte) {
				System.err.printf("Failed to create server socket: %s\n",accepte.getMessage());
				System.exit(1);
			}
			tnc.setSocket(s);
			tnc.start();
			tnc.fromHost();
		}
		
		
		/*** enumerate sound devices ***/
	}
}
