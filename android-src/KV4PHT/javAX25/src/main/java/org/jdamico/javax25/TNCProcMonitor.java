package org.jdamico.javax25;
import java.io.InputStream;
import java.io.PrintStream;

public class TNCProcMonitor extends Thread{
	
	private final InputStream procstream;
	private final PrintStream outstream;
	
	public TNCProcMonitor(InputStream procstream, PrintStream outstream)
	{
		this.procstream = procstream;
		this.outstream = outstream;
		setName(procstream.toString() + "->" + outstream.toString());
		start();
	}
	
	public void run()
	{
		try 
		{
			for(;;) 
			{
				int _ch = procstream.read();
				if(_ch != -1) 
					outstream.print((char)_ch); 
				else break;
			}
		} 
		catch (Exception e) 
		{
			e.printStackTrace();
		} 
	}
}
