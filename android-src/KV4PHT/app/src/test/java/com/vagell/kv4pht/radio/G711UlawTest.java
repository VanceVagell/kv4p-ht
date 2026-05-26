/*
kv4p HT (see http://kv4p.com)
Copyright (C) 2024 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.vagell.kv4pht.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class G711UlawTest {
    @Test
    public void knownDecodeMappings() {
        assertEquals(0, G711Ulaw.decode((byte) 0xFF));
        assertEquals(0, G711Ulaw.decode((byte) 0x7F));
        assertTrue(G711Ulaw.decode((byte) 0x80) > 30000);
        assertTrue(G711Ulaw.decode((byte) 0x00) < -30000);
    }

    @Test
    public void knownEncodeMappings() {
        assertEquals(0xFF, G711Ulaw.encode((short) 0) & 0xFF);
        assertEquals(0x80, G711Ulaw.encode(Short.MAX_VALUE) & 0xFF);
        assertEquals(0x00, G711Ulaw.encode(Short.MIN_VALUE) & 0xFF);
    }

    @Test
    public void bufferEncodeDecodeReturnsSampleCounts() {
        short[] pcm = new short[] {0, 1000, -1000, 16000, -16000};
        byte[] ulaw = new byte[pcm.length + 2];
        short[] decoded = new short[pcm.length + 2];

        assertEquals(pcm.length, G711Ulaw.encode(pcm, 0, ulaw, 1, pcm.length));
        assertEquals(pcm.length, G711Ulaw.decode(ulaw, 1, decoded, 1, pcm.length));
    }

    @Test
    public void roundTripIsApproximatelyCorrect() {
        short[] samples = new short[] {-30000, -10000, -1000, 0, 1000, 10000, 30000};
        for (short sample : samples) {
            short decoded = G711Ulaw.decode(G711Ulaw.encode(sample));
            assertTrue(Math.abs(decoded - sample) < 1200);
        }
    }
}
