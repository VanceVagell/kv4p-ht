package com.vagell.kv4pht.radio;

import static com.vagell.kv4pht.radio.RadioAudioService.COMMAND_DELIMITER;

import java.io.ByteArrayOutputStream;
import java.util.function.BiConsumer;

public class RxStreamParser {

    private int matchedDelimiterTokens = 0;
    private byte command;
    private byte commandParamLen;
    private final ByteArrayOutputStream commandParams = new ByteArrayOutputStream();
    private final ByteArrayOutputStream lookaheadBuffer = new ByteArrayOutputStream();

    private final BiConsumer<Byte, byte[]> onCommand;

    public RxStreamParser(BiConsumer<Byte, byte[]> onCommand) {
        this.onCommand = onCommand;
    }

    public byte[] extractAudioAndHandleCommands(byte[] newData) {
        //commandParamLen = -1;

        ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
        for (byte b : newData) {
            lookaheadBuffer.write(b);
            if (matchedDelimiterTokens < COMMAND_DELIMITER.length) {
                if (b == COMMAND_DELIMITER[matchedDelimiterTokens]) {
                    matchedDelimiterTokens++;
                } else {
                    flushLookaheadBuffer(audioOut);
                    matchedDelimiterTokens = 0;
                }
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length) {
                command = b;
                matchedDelimiterTokens++;
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length + 1) {
                commandParamLen = b;
                commandParams.reset();
                matchedDelimiterTokens++;

                if (commandParamLen == 0) { // If this command has no params...
                    lookaheadBuffer.reset();
                    onCommand.accept(command, commandParams.toByteArray());
                    resetParser(audioOut);
                }
            } else {
                commandParams.write(b);
                matchedDelimiterTokens++;
                lookaheadBuffer.reset();
                if (commandParams.size() == commandParamLen) {
                    onCommand.accept(command, commandParams.toByteArray());
                    resetParser(audioOut);
                }
            }
        }
        return audioOut.toByteArray();
    }

    private void flushLookaheadBuffer(ByteArrayOutputStream audioOut) {
        byte[] buffer = lookaheadBuffer.toByteArray();
        audioOut.write(buffer, 0, buffer.length);
        lookaheadBuffer.reset();
    }

    private void resetParser(ByteArrayOutputStream audioOut) {
        flushLookaheadBuffer(audioOut);
        matchedDelimiterTokens = 0;
        commandParams.reset();
        commandParamLen = 0;
    }
}