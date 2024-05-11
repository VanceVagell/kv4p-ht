package org.jdamico.javax25;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Vector;


/**
 * This interface must be implemented by any TNC interface module.
 * <P>
 * Synchronization Note:
 * <P>
 * It is expected that any thread-safe synchronization be done by the interface module.
 * For instance, it is possible that all methods may be called from different threads.
 * <PRE>
 * Changed from 2.1:
 *  Packet support to use AX25Packet class
 * Changed from 2.0:
 *  Added byte to receivePacket() for source indicator
 * Changed from 1.1:
 *  Moved all TNC2 formatting into javAPRSIGate.
 * Changed from 1.0:
 *  Added portTable comment to getVersion description
 * </PRE>
 * Requires javAPRSIGate 2.0 or higher
 * 
 * @author Peter Loveall AE5PL
 * @version 3.0
 */
public abstract class TNCInterface
{
	/**
	 * Allows port to be reset or closed.
	 * 
	 * Must be synchronized.  
	 * Should be called by TNC.finalize()
	 */
	public abstract void close();

	/**
	 * Returns version info for status pages.
	 * <P>
	 * The format is the same as for portTable in javAPRSSrvr.
	 * 
	 * @return This can be any text string to be displayed on the status pages.
	 */
	public abstract String getVersion();

	/**
	 * Initializes interface.
	 * <P>
	 * This may be called multiple times so 
	 * must be synchronized to not interfere with receivePacket, sendPacket, and close.
	 * 
	 * @param configuration Properties from configuration file
	 */
	public abstract void init(Properties configuration);

	/**
	 * Determines if connected to physical interface.
	 */
	public abstract boolean isConnected();

	/**
	 * Get next received packet.
	 * <P>
	 * Must be synchronized to enable wait for next packet.
	 * <P>
	 * The AX25Packet.rcvd indicates 0=Received from RF, 1=Send to Inet, 2=Send to RF
	 * 
	 * @return Packet
	 */
	public abstract AX25Packet receivePacket();

	/**
	 * This puts a packet on the TNC receive queue.
	 * <P>
	 * Must be synchronized.
	 * 
	 * @param packet Complete packet.
	 */
	public abstract void sendInternetPacket(AX25Packet packet);

	/**
	 * Submit packet for transmission.
	 * <P>
	 * Must be synchronized to allow placing packet on queue.
	 * Do not delay the calling process.
	 * 
	 * @param packet Complete packet.
	 */
	public abstract void sendPacket(AX25Packet packet);

	
	/**
	 * @return UI packet with PID of 0xf0
	 */
	public final static AX25Packet getAPRSPacket(byte rcvd, String[] addresses, int digied, byte[] IField)
	{
		byte[] restOfPacket = new byte[IField.length+2];
		restOfPacket[0] = 0x03;
		restOfPacket[1] = (byte)0xf0;
		System.arraycopy(IField, 0, restOfPacket, 2, IField.length);
		byte[] ssids = new byte[addresses.length];
		for (int i = 0; i < ssids.length; i++)
		{
			ssids[i] = 0x60;
		}
		return new AX25Packet(rcvd, addresses, ssids, digied, restOfPacket);
	}

	/**
	 * @param rcvd Sets AX25Packet.rcvd
	 * @param TNC2Format TNC2Format line (no CR/LF)
	 * @return UI packet with PID of 0xf0
	 */
	public final static AX25Packet getAPRSPacket(byte rcvd, byte[] TNC2Format)
	{
		StringTokenizer TNC2Parse = null;
		int colon = -1;
		for (int i = 0; i < TNC2Format.length; i++)
		{
			if (TNC2Format[i] == (byte)':')
			{
				colon = i;
				TNC2Parse = new StringTokenizer(new String(TNC2Format, 0, 0, i), ">,");
				break;
			}
		}
		String[] addresses = new String[TNC2Parse.countTokens()];
		addresses[1] = TNC2Parse.nextToken();
		addresses[0] = TNC2Parse.nextToken();
		int digied = 1;
		for (int i = 2; TNC2Parse.hasMoreTokens(); i++)
		{
			addresses[i] = TNC2Parse.nextToken();
			if (addresses[i].endsWith("*"))
			{
				addresses[i] = addresses[i].substring(0, addresses[i].length()-1);
				digied = i;
			}
		}
		byte[] ssids = new byte[addresses.length];
		for (int i = 0; i < ssids.length; i++)
		{
			ssids[i] = 0x60;
		}
		
		byte[] restOfPacket = new byte[TNC2Format.length+1-colon];
		restOfPacket[0] = 0x03;
		restOfPacket[1] = (byte)0xf0;
		if (restOfPacket.length > 2)
			System.arraycopy(TNC2Format, colon+1, restOfPacket, 2, restOfPacket.length-2);
		return new AX25Packet(rcvd, addresses, ssids, digied, restOfPacket);
	}
	
	public static class AX25Packet {
		/**
		 * This byte indicates 0=Received from RF, 1=Send to Inet, 2=Send to RF
		 */
		public final byte rcvd;
		
		/**
		 * This is the address fields in ASCII.  Non-AX.25 SSIDs are allowed but
		 * will be invalid if used with an AX.25 TNC.<BR>
		 * Addresses are as follows:
		 * <PRE>
		 * 0 - Destination
		 * 1 - Source
		 * 2 - Digipeater Zero
		 * 3 - Digipeater One
		 * ...
		 * </PRE>
		 */
		public final String[] addresses;
		
		/**
		 * This is the SSID reserved bits for each address
		 * This is necessary for proper repeater operation
		 */
		public final byte[] ssidBits;
		
		/**
		 * Index into addresses to last transmitted (default is 1)
		 */
		public final int digied;
		
		/**
		 * This is the packet data starting with the control field
		 */
		public final byte[] restOfPacket;
		
		/**
		 * Create straight from AX.25 format packet
		 */
		AX25Packet(byte rcvd, byte[] pkt, int start)
		{
			this.rcvd = rcvd;
			
			Vector tpath = new Vector();
			Vector tssids = new Vector();
			int p = start;
			
			// Destination
			for(int i=p;i<p+6;i++)
			{
				pkt[i] = (byte)(pkt[i]>>>1 & 0x7f);
			}
			String tstr = new String(pkt, 0, p, 6).trim();
			p+=6;
			tssids.addElement(new Integer(pkt[p]));
			int ssid = (pkt[p++] & 0x1e)>>>1;
			if (ssid != 0)
				tpath.addElement(tstr+'-'+ssid);
			else
				tpath.addElement(tstr);

			// Source
			for(int i=p;i<p+6;i++)
			{
				pkt[i] = (byte)(pkt[i]>>>1 & 0x7f);
			}
			tstr = new String(pkt, 0, p, 6).trim();
			p+=6;
			tssids.addElement(new Integer(pkt[p]));
			ssid = (pkt[p++] & 0x1e)>>>1;
			if (ssid != 0)
				tpath.addElement(tstr+'-'+ssid);
			else
				tpath.addElement(tstr);

			// Path
			int digi = 1;
			while((pkt[p-1] & 0x1) != 1)
			{
				for(int i=p;i<p+6;i++)
				{
					pkt[i] = (byte)(pkt[i]>>>1 & 0x7f);
				}
				tstr = new String(pkt, 0, p, 6).trim();
				p+=6;
				byte tssid = pkt[p++];
				ssid = (tssid & 0x1e)>>>1;
				tssids.addElement(new Integer(tssid));
			    if((tssid & 0x80) == 0x80) digi = tpath.size();
				if (ssid != 0)
					tpath.addElement(tstr+'-'+ssid);
				else
					tpath.addElement(tstr);
			}

			restOfPacket = new byte[pkt.length - p];
			System.arraycopy(pkt, p, restOfPacket, 0, pkt.length-p);
		
			addresses = new String[tpath.size()];
			tpath.copyInto(addresses);
			
			ssidBits = new byte[tssids.size()];
			for (int i = 0; i < ssidBits.length; i++)
			{
				ssidBits[i] = (byte)(((Integer)tssids.elementAt(i)).intValue()&0x60);
			}
			digied = digi;
		}
		
		/**
		 * Create straight from AX.25 format packet
		 */
		AX25Packet(byte rcvd, byte[] pkt)
		{
			this(rcvd, pkt, 0);
		}
		
		/**
		 * Intialize from already parsed packet
		 */
		AX25Packet(byte rcvd, String[] addresses, byte[] ssidBits, int digied, byte[] restOfPacket)
		{
			this.rcvd = rcvd;
			this.addresses = addresses;
			this.ssidBits = ssidBits;
			this.digied = digied;
			this.restOfPacket = restOfPacket;
		}

		/**
		 * @return AX.25 Format packet; null if not AX.25 packet
		 */
		public final byte[] getAX25Packet()
		{
			int alen = addresses.length*7;
			byte[] tpack = new byte[alen + restOfPacket.length];
			if (!getAddress(addresses[0], ssidBits[0], tpack, 0)) return null;
			if (!getAddress(addresses[1], ssidBits[1], tpack, 7)) return null;
			for (int i = 2; i < addresses.length; i++)
			{
				if (!getAddress(addresses[i], ssidBits[i], tpack, i*7)) return null;
				if (i <= digied) tpack[i*7+6] |= (byte)0x80;
			}
			tpack[alen - 1] |= 0x01;
			System.arraycopy(restOfPacket, 0, tpack, alen, restOfPacket.length);
			return tpack;
		}
		
		private final boolean getAddress(String strAddress, byte ssidbits, byte[] packet, int addressndx)
		{
			int da = strAddress.lastIndexOf('-');
			if (da < 0) da = strAddress.length();
			if (da > 6) return false;  // Invalid callsign length
			for(int i=0;i<da;i++)
			{ // Set call
			    packet[addressndx++] = (byte)(strAddress.charAt(i)<<1);
			}
			for(int i=da;i<6;i++)
			{ // Fill empty spots
			    packet[addressndx++] = 0x40;
			}
			switch (strAddress.length()-da)
			{
			case 2: // single digit
			    packet[addressndx] = (byte)(ssidbits | (strAddress.charAt(da+1)-0x30)<<1);
				break;
			case 3: // double digit
			    packet[addressndx] = (byte)(ssidbits | (strAddress.charAt(da+2)-0x30+10)<<1);
				break;
			default: // no SSID
			    packet[addressndx] = (byte)(ssidbits);
			}
			return true;
		}

		/**
		 * @return TNC2 Format packet with rcvd prepended; null if not APRS packet
		 */
		public final byte[] getAPRSPacket()
		{
			if (restOfPacket.length <= 2
				|| restOfPacket[0] != 0x03
				|| restOfPacket[1] != (byte)0xf0) return null;
			ByteArrayOutputStream bpkt = new ByteArrayOutputStream(restOfPacket.length+addresses.length*10+3);
			DataOutputStream dpkt = new DataOutputStream(bpkt);
			try
			{
				bpkt.write(rcvd);
				dpkt.writeBytes(addresses[1]);
				bpkt.write('>');
				dpkt.writeBytes(addresses[0]);
				for (int i = 2; i < addresses.length; i++)
				{
					bpkt.write(',');
					dpkt.writeBytes(addresses[i]);
					if (i == digied) dpkt.writeByte('*');
				}
				bpkt.write(':');
				bpkt.write(restOfPacket, 2, restOfPacket.length-2);
			}
			catch (IOException ie)
			{
				ie.printStackTrace();
			}
			return bpkt.toByteArray();
		}
		
		public final String toString()
		{
			StringBuffer sb = new StringBuffer("Src:").append(addresses[1]).append(" Dest:").append(addresses[0]);
			if (addresses.length > 2)
				sb.append(" Via:").append(addresses[2]);
			if (digied > 1) sb.append('*');
			for (int i = 3; i < addresses.length; i++)
			{
				sb.append(',').append(addresses[i]);
				if (digied >= i) sb.append('*');
			}
			sb.append(" Rest of Packet Len:").append(restOfPacket.length);
			return sb.toString();
		}
	}
}
