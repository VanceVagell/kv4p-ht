package org.jdamico.javax25;
import java.util.Vector;

public final class TNCQueue
{
	private final Vector queue = new Vector();
	private boolean queueEnabled = true;

	/**
	 * This gets the next object on the queue.
	 * 
	 * @return This is null if the queue is disabled.
	 */
	public final synchronized TNCInterface.AX25Packet getFromQueue()
	{
		if (queueEnabled)
		{
			if (queue.isEmpty())
			{
				try {wait();} catch (InterruptedException e){}
				if (!queueEnabled)
					return null;
			}
			TNCInterface.AX25Packet retval = (TNCInterface.AX25Packet)queue.firstElement();
			queue.removeElementAt(0);
			return retval;
		}
		return null;
	}

	/**
	 * Checks for whether the queue is enabled.
	 */
	public final synchronized boolean isEnabled()
	{
		return queueEnabled;
	}

	/**
	 * This puts the object on the queue.
	 * 
	 * @param newObject If this is null, the queue is purged and shut down.
	 */
	public final synchronized void putOnQueue(TNCInterface.AX25Packet newObject)
	{
		if (queueEnabled)
		{
			if (newObject == null)
			{
				queue.removeAllElements();
				queueEnabled = false;
			}
			else
				queue.addElement(newObject);
			notifyAll();
		}
	}
}
