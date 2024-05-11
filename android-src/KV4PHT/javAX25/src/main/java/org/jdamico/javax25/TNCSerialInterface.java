package org.jdamico.javax25;
import java.util.*;
import java.io.*;

public interface TNCSerialInterface
{
	/**
	 * Returns version string
	 */
	public abstract String getVersion();

	/**
	 * Closes related input and output streams
	 */
	public abstract void close() throws IOException;

	/**
	 * Returns an InputStream object
	 * 
	 * @return Interface specific InputStream
	 */
	public abstract InputStream getInputStream() throws IOException;

	/**
	 * Returns an OutputStream object
	 * 
	 * @return Interface specific OutputStream
	 */
	public abstract OutputStream getOutputStream() throws IOException;

	/**
	 * Open the port.  Throws IOException upon failure.
	 * 
	 * @param config Interface specific properties
	 */
	public abstract void open(Properties config) throws IOException;
}
