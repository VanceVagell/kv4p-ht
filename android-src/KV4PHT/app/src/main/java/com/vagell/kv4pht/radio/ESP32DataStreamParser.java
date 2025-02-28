package com.vagell.kv4pht.radio;

import static com.vagell.kv4pht.radio.RadioAudioService.COMMAND_DELIMITER;

import java.io.ByteArrayOutputStream;
import java.util.function.BiConsumer;

public class ESP32DataStreamParser {

    private int matchedDelimiterTokens = 0;
    private byte command;
    private int commandParamLen;
    private final ByteArrayOutputStream commandParams = new ByteArrayOutputStream();
    private final BiConsumer<Byte, byte[]> onCommand;

    public ESP32DataStreamParser(BiConsumer<Byte, byte[]> onCommand) {
        this.onCommand = onCommand;
    }

    public void handleCommands(byte[] newData) {
        for (byte b : newData) {
            if (matchedDelimiterTokens < COMMAND_DELIMITER.length) {
                if (b == COMMAND_DELIMITER[matchedDelimiterTokens]) {
                    matchedDelimiterTokens++;
                } else {
                    matchedDelimiterTokens = 0;
                }
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length) {
                command = b;
                // Log.d("DEBUG", "command: " + command);
                matchedDelimiterTokens++;
            } else if (matchedDelimiterTokens == COMMAND_DELIMITER.length + 1) {
                commandParamLen = b & 0xFF;
                commandParams.reset();
                matchedDelimiterTokens++;
                if (commandParamLen == 0) { // If this command has no params...
                    onCommand.accept(command, commandParams.toByteArray());
                    resetParser();
                }
            } else {
                commandParams.write(b);
                matchedDelimiterTokens++;
                if (commandParams.size() == commandParamLen) {
                    // Log.d("DEBUG", "commandParams: " + commandParams);
                    onCommand.accept(command, commandParams.toByteArray());
                    resetParser();
                }
            }
        }
    }

    private void resetParser() {
        matchedDelimiterTokens = 0;
        commandParams.reset();
        commandParamLen = 0;
    }
}