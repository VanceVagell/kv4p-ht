package org.jdamico.javax25.threads;
import java.util.Properties;

import org.jdamico.javax25.TNCInterface;
import org.jdamico.javax25.TNCProcMonitor;
import org.jdamico.javax25.TNCQueue;

/**
 * Override this class to do your connection initialization and read functions.
 */
public abstract class TNCConnectThread extends TNCInterface implements Runnable 
{
	protected final TNCQueue queue = new TNCQueue();
	protected volatile TNCWriteThread writer = null;
	
	protected String version;

	protected boolean firstTime = true;

	/**
	 * Returns version info for inclusion on status page.
	 */
	public String getVersion()
	{
		return version;
	}
	
	/**
	 * Determines if connected to physical interface.
	 */
	public boolean isConnected()
	{
		if (writer == null)
			return false;
		return writer.queue.isEnabled();
	}

	/**
	 * Get next received packet.
	 * <P>
	 * Must be synchronized to enable wait for next packet.
	 * 
	 * @return TNC2 formated packet, no cr or lf
	 */
	public TNCInterface.AX25Packet receivePacket()
	{
		return queue.getFromQueue();
	}

	/**
	 * This puts a packet on the TNC receive queue.
	 * <P>
	 * Must be synchronized.
	 * The packet is in TNC2 format.
	 * 
	 * @param packet Complete packet including TNC2 header.
	 */
	public void sendInternetPacket(TNCInterface.AX25Packet packet)
	{
		queue.putOnQueue(packet);
	}
	
	/**
	 * Submit packet for transmission.
	 * <P>
	 * Must be synchronized to allow placing packet on queue.
	 * Do not delay the calling process.
	 * <P>
	 * The packet is in TNC2 format.
	 * 
	 * @param packet Complete packet including TNC2 header.
	 */
	public void sendPacket(TNCInterface.AX25Packet packet)
	{
		if (writer != null)
		{
			queue.putOnQueue(packet); // For logging
			writer.queue.putOnQueue(packet);
		}
	}

	/**
	 * Override this function with your operating code
	 */
	abstract public void run();
	
	protected void initParams(Properties configuration)
	{
		try {TNCWriteThread.TNCSpeed = Integer.parseInt(configuration.getProperty("TNCSpeed", "1200").trim());}
		catch (Exception e)
		{
			System.err.println("Exception parsing TNCSpeed");
			e.printStackTrace();
		}
		
		try {TNCWriteThread.TNCIMax = Integer.parseInt(configuration.getProperty("TNCIFieldMax", "256").trim());}
		catch (Exception e)
		{
			System.err.println("Exception parsing TNCIFieldMax");
			e.printStackTrace();
		}

		try {TNCWriteThread.TNCVias = Integer.parseInt(configuration.getProperty("TNCMaxVias", "7").trim());}
		catch (Exception e)
		{
			System.err.println("Exception parsing TNCMaxVias");
			e.printStackTrace();
		}

		if (firstTime)
		{
			String TNCInit = configuration.getProperty("TNCPortInit", "");
			if (TNCInit.length() > 0)
			{
				try
				{
					boolean waitforend = true;
					try {waitforend = Boolean.valueOf(configuration.getProperty("TNCPortInitWait", "true").trim()).booleanValue();}
					catch (Exception be)
					{
						System.err.println("Exception parsing TNCPortInitWait");
						be.printStackTrace();
					}
					Process pr = Runtime.getRuntime().exec(TNCInit);
					Thread tpo = new TNCProcMonitor(pr.getInputStream(), System.err);
					Thread tpe = new TNCProcMonitor(pr.getErrorStream(), System.err);
					if (waitforend)
					{
						pr.waitFor();
						// The following are to ensure proper error log printing
						tpo.join();
						tpe.join();
					}
				}
				catch (Exception e)
				{
					System.err.println("Error trying to run "+TNCInit);
					e.printStackTrace();
				}
			}
		}
	}
}
