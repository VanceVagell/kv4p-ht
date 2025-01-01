package com.vagell.kv4pht.radio;

import static com.vagell.kv4pht.radio.RadioAudioService.COMMAND_DELIMITER;

import java.io.ByteArrayOutputStream;
import java.util.function.BiConsumer;

public class RxStreamParser {

    private int matchedDelimiterTokens = 0;
    private byte command;
    private byte commandParamLen;
    private final ByteArrayOutputStream commandParams = new ByteArrayOutputStream();

    private final BiConsumer<Byte, byte[]> onCommand;

    public RxStreamParser(BiConsumer<Byte, byte[]> onCommand) {
        this.onCommand = onCommand;
    }

    public byte[] extractAudioAndHandleCommands(byte[] newData) {
        ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
        for (byte b : newData) {
            if (matchedDelimiterTokens < COMMAND_DELIMITER.length) {
                // Matching delimiter
                if (b == COMMAND_DELIMITER[matchedDelimiterTokens]) {
                    matchedDelimiterTokens++;
                } else {
                    // If partial delimiter exists, flush to audio
                    if (matchedDelimiterTokens > 0) {
                        audioOut.write(COMMAND_DELIMITER, 0, matchedDelimiterTokens);
                    }
                    audioOut.write(b);  // Write the audio byte
                    matchedDelimiterTokens = 0;
                }
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length) {
                // After full delimiter, expect command byte
                command = b;
                matchedDelimiterTokens++;
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length + 1) {
                // Expect length byte after command
                commandParamLen = b;
                commandParams.reset();
                matchedDelimiterTokens++;
            } else {
                // Collect command parameters
                commandParams.write(b);
                matchedDelimiterTokens++;
                // Once full parameter is received, process the command
                if (commandParams.size() == commandParamLen) {
                    onCommand.accept(command, commandParams.toByteArray());
                    matchedDelimiterTokens = 0;
                    commandParamLen = 0;
                }
            }
        }
        // Return any audio processed so far
        return audioOut.toByteArray();
    }
}