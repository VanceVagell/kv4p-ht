package com.vagell.kv4pht.com.vagell.kv4pht.ax25;

import org.jdamico.javax25.ax25.Afsk1200Demodulator;
import org.jdamico.javax25.ax25.Afsk1200Modulator;
import org.jdamico.javax25.ax25.Packet;
import org.jdamico.javax25.ax25.PacketDemodulator;
import org.jdamico.javax25.ax25.PacketHandler;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

public class Ax25Helper {
    private static Afsk1200Modulator modulator = null;
    private static Afsk1200Demodulator demodulator = null;
    private static MessageHandler msgHandler = null;

    public static byte[] stringToAx25AudioBytes(String fromCall, String toCall, String input) {
        if (modulator == null) {
            modulator = new Afsk1200Modulator(8000);
        }

        Packet packet = null;
        try {
            packet = new Packet(toCall, fromCall, null, Packet.AX25_CONTROL_APRS, Packet.AX25_PROTOCOL_NO_LAYER_3, input.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        modulator.prepareToTransmit(packet);
        ArrayList<Byte> outBytesArray = new ArrayList<Byte>();

        int samplesToAdd = 0;
        do {
            float[] samplesBuffer = modulator.getTxSamplesBuffer();
            samplesToAdd = modulator.getSamples();
            if (samplesToAdd > 0) {
                for (int i = 0; i < samplesToAdd; i++) {
                    outBytesArray.add(new Byte((byte) (128f * samplesBuffer[i]))); // Convert audio floats to 8-bit audio bytes
                }
            }
        } while (samplesToAdd > 0);

        byte[] outBytes = new byte[outBytesArray.size()];
        for (int i = 0; i < outBytesArray.size(); i++) {
            outBytes[i] = outBytesArray.get(i).byteValue();
        }
        return outBytes;
    }

    public static abstract class MessageHandler {
        protected abstract void handle(String msg);
    }

    public static void setMessageHandler(Ax25Helper.MessageHandler msgHandler) {
        Ax25Helper.msgHandler = msgHandler;
    }
    public static void processAx25AudioBytes(byte[] input) {
        if (demodulator == null) {
            try {
                demodulator = new Afsk1200Demodulator(8000, 26, 0, new PacketHandler() {
                    @Override
                    public void handlePacket(byte[] packet) {
                        if (msgHandler != null) {
                            msgHandler.handle(Packet.format(packet));
                        }
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        float[] audioFloat = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            audioFloat[i] = (float) (input[i] / 128f); // Convert 8-bit audio bytes to audio floats
        }

        demodulator.addSamples(audioFloat, audioFloat.length);
        demodulator.dcd();
    }
}
