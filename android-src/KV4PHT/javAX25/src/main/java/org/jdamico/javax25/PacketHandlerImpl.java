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
package org.jdamico.javax25;

import java.util.Date;

import org.jdamico.javax25.ax25.Packet;
import org.jdamico.javax25.ax25.PacketHandler;
import org.jdamico.javax25.soundcard.Soundcard;

public class PacketHandlerImpl implements PacketHandler {
	
	public void handlePacket(byte[] bytes) {
		
		String data = Packet.format(bytes);
		Date now = new Date();
		if(Soundcard.jTextArea == null) {
			System.out.println("Packet ====>>>>"  +data);
		
		
			
		}else {
			
			String lines = Soundcard.jTextArea.getText();
			if(lines.length() > 80000) lines = "Cleaning log...\n";  
			Soundcard.jTextArea.setText(lines+"["+BasicHelper.getInstance().dateToString(now, Constants.DATE_TIME_FORMAT)+"] "+data+"\n");
		}
		
		if(Soundcard.receivedPackedMap != null) {
			Soundcard.receivedPackedMap.put(now.getTime(), data);
		}
		
		return;
		/*
		if (last!=null && Arrays.equals(last, bytes) && sample_count <= last_sample_count + 100) {
			dup_count++;
			System.out.printf("Duplicate, %d so far\n",dup_count);
		} else {
			packet_count++;
			System.out.println(""+packet_count);
			last = bytes;
			last_sample_count = sample_count;
		}
		*/
	}

}
