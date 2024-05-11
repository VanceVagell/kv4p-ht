package org.jdamico.javax25.radiocontrol;

import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.NoSuchPortException;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;

import java.io.IOException;
import java.util.Enumeration;

public class SerialTransmitController implements TransmitController {

	public static void enumerate() {
		Enumeration<CommPortIdentifier> ports = CommPortIdentifier.getPortIdentifiers();
		System.out.println("Serial ports:");
    while ( ports.hasMoreElements() ) {
        CommPortIdentifier portIdentifier = ports.nextElement();
        System.out.println("  "+portIdentifier.getName());
    }        
	}
	
	private SerialPort ptt_serial_port = null;
	private boolean dtr = false;
	private boolean rts = false;

	public SerialTransmitController(String port, String rts_or_dtr) throws Exception {
		CommPortIdentifier pi = null;
		try {
  		pi = CommPortIdentifier.getPortIdentifier(port);
		} catch (NoSuchPortException nspe) {
			String port_names = "";
			boolean comma = false;
			Enumeration<CommPortIdentifier> ports = CommPortIdentifier.getPortIdentifiers();
	    while ( ports.hasMoreElements() ) {
	        CommPortIdentifier portIdentifier = ports.nextElement();
	        if (comma) port_names += ",";
	        port_names += portIdentifier.getName();
	        comma = true;
	    }        
			throw new IOException(String.format("Port %s is not valid (valid ports are %s)", port,port_names));
		}
		CommPort ptt_comm_port = null;
		try {
	  	ptt_comm_port = pi.open(this.getClass().getName(),1000);	
		} catch (PortInUseException piue) {
			throw new IOException(String.format("Port %s is in use", port));
		}
		if ( ptt_comm_port instanceof SerialPort ) {
			ptt_serial_port = (SerialPort) ptt_comm_port;
			//serialPort.setSerialPortParams(57600,SerialPort.DATABITS_8,
			//		                           SerialPort.STOPBITS_1,SerialPort.PARITY_NONE);
		} else {
			System.err.println("5");
			throw new IOException(String.format("Port %s is not a serial port", port));
		}

		ptt_serial_port.setRTS(false);
		ptt_serial_port.setDTR(false);
		
		System.err.println("Opened a PTT port: "+port);

		if (rts_or_dtr.equalsIgnoreCase("RTS")) { rts = true; return; }
		if (rts_or_dtr.equalsIgnoreCase("DTR")) { dtr = true; return; }

		throw new IOException(String.format("Signal %s is not valid (must be RTS or DTR)", rts_or_dtr));
	}
	
	@Override
	public void startTransmitter() {
		if (rts) ptt_serial_port.setRTS(true);
		if (dtr) ptt_serial_port.setDTR(true);
	}

	@Override
	public void stopTransmitter() {
		if (rts) ptt_serial_port.setRTS(false);
		if (dtr) ptt_serial_port.setDTR(false);
	}
	
	@Override
	public void close() {
		ptt_serial_port.close();
	}
}
