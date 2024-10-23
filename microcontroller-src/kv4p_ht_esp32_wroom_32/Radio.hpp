#pragma once

#include <Arduino.h>
#include <SPI.h>

#include "CommandEnum.h"

class Radio
{
public:
    enum Mode
    {
        STOPPED,
        RX,
        TX
    };

    Radio() : mode_(STOPPED), txStartTime_(0),
              txCachedAudioBytes_(0), isTxCacheSatisfied_(false),
              lastSquelched_(false), fadeCounter_(0), fadeDirection_(0),
              attenuation_(0), matchedDelimiterTokens(0)
    {
        commandProcessor_ = CommandProcessor(*this);
        audioProcessor_ = AudioProcessor(*this);
    }

    void loop()
    {
        try
        {
            processCommands();

            switch (mode_)
            {
            case STOPPED:
                // Nothing to do in stopped mode
                break;
            case RX:
                handleRx();
                break;
            case TX:
                handleTx();
                break;
            }

            // Regularly reset the WDT timer
            esp_task_wdt_reset();
        }
        catch (int e)
        {
            // Handle exceptions
        }
    }

private:
    void processCommands();
    void handleRx();
    void handleTx();

    void handleTuneToCommand(const String &paramsStr);
    void handleFiltersCommand(const String &paramsStr);
    void handleStopCommand();
    void handlePttDownCommand();
    void handlePttUpCommand();
    void handleGetFirmwareVerCommand();

    void setMode(Mode newMode);
    bool readCommandParams(char *buffer, int paramBytesMissing);
    void tuneTo(float freqTx, float freqRx, int tone, int squelch);

    // ... (other private members)

    CommandProcessor commandProcessor_;
    AudioProcessor audioProcessor_;
    Mode mode_;
    unsigned long txStartTime_;
    bool lastSquelched_;
    int fadeCounter_;
    int fadeDirection_;
    int attenuation_;
    int txCachedAudioBytes_;
    bool isTxCacheSatisfied_;
    uint8_t txCachedAudioBuffer_[TX_CACHED_AUDIO_BUFFER_SIZE];
    int matchedDelimiterTokens;
    // ... (other member variables)
};

// Radio class member functions

void Radio::processCommands()
{
    if (Serial.available())
    {
        // Read a command from Android app
        uint8_t tempBuffer[100];
        int bytesRead = 0;

        while (bytesRead < (DELIMITER_LENGTH + 1))
        {
            if (Serial.available())
            {
                tempBuffer[bytesRead++] = Serial.read();
            }
        }

        const CommandEnum command = tempBuffer[DELIMITER_LENGTH];
        commandProcessor_.processCommand(command);
    }
}

void Radio::handleRx()
{
    audioProcessor_.processRxAudio();
}

void Radio::handleTx()
{
    // Check for runaway tx
    int txSeconds = (micros() - txStartTime_) / 1000000;
    if (txSeconds > RUNAWAY_TX_SEC)
    {
        setMode(MODE_RX);
        esp_task_wdt_reset();
        return;
    }

    // Check for incoming commands or audio from Android
    int bytesRead = 0;
    uint8_t tempBuffer[TX_TEMP_AUDIO_BUFFER_SIZE];
    int bytesAvailable = Serial.available();
    if (bytesAvailable > 0)
    {
        bytesRead = Serial.readBytes(tempBuffer, bytesAvailable);

        // Pre-cache transmit audio to ensure precise timing (required for any data encoding to work, such as BFSK).
        if (!isTxCacheSatisfied_)
        {
            if (txCachedAudioBytes_ + bytesRead >= TX_CACHED_AUDIO_BUFFER_SIZE)
            {
                isTxCacheSatisfied_ = true;
                audioProcessor_.processTxAudio(txCachedAudioBuffer_, txCachedAudioBytes_); // Process cached bytes
            }
            else
            {
                memcpy(txCachedAudioBuffer_ + txCachedAudioBytes_, tempBuffer, bytesRead); // Store bytes to cache
                txCachedAudioBytes_ += bytesRead;
            }
        }

        if (isTxCacheSatisfied_)
        {
            audioProcessor_.processTxAudio(tempBuffer, bytesRead);
        }

        for (int i = 0; i < bytesRead && i < TX_TEMP_AUDIO_BUFFER_SIZE; i++)
        {
            // If we've seen the entire delimiter...
            if (matchedDelimiterTokens == DELIMITER_LENGTH)
            {
                // Process next byte as a command.
                CommandEnum command = tempBuffer[i];
                matchedDelimiterTokens = 0;

                commandProcessor_.processCommand(command);
            }
            else
            {
                if (tempBuffer[i] == delimiter[matchedDelimiterTokens])
                { // This byte may be part of the delimiter
                    matchedDelimiterTokens++;
                }
                else
                { // This byte is not consistent with the command delimiter, reset counter
                    matchedDelimiterTokens = 0;
                }
            }
        }
    }
}

void Radio::handleTuneToCommand(const String &paramsStr)
{
    float freqTxFloat = paramsStr.substring(0, 8).toFloat();
    float freqRxFloat = paramsStr.substring(7, 15).toFloat();
    int toneInt = paramsStr.substring(14, 16).toInt();
    int squelchInt = paramsStr.substring(16, 17).toInt();

    tuneTo(freqTxFloat, freqRxFloat, toneInt, squelchInt);
}

void Radio::handleFiltersCommand(const String &paramsStr)
{
    bool emphasis = (paramsStr.charAt(0) == '1');
    bool highpass = (paramsStr.charAt(1) == '1');
    bool lowpass = (paramsStr.charAt(2) == '1');

    dra->filters(emphasis, highpass, lowpass);
}

void Radio::handleStopCommand()
{
    setMode(MODE_STOPPED);
    Serial.flush();
    esp_task_wdt_reset();
}

void Radio::handlePttDownCommand()
{
    setMode(MODE_TX);
    esp_task_wdt_reset();
}

void Radio::handlePttUpCommand()
{
    delay(MS_WAIT_BEFORE_PTT_UP); // Wait just a moment so final tx audio data in DMA buffer can be transmitted.
    setMode(MODE_RX);
    esp_task_wdt_reset();
}

void Radio::handleGetFirmwareVerCommand()
{
    Serial.write(VERSION_PREFIX, sizeof(VERSION_PREFIX));
    Serial.write(FIRMWARE_VER, sizeof(FIRMWARE_VER));
    Serial.flush();
    esp_task_wdt_reset();
}

void Radio::setMode(Mode newMode)
{
    mode_ = newMode;
    switch (mode_)
    {
    case MODE_STOPPED:
        digitalWrite(LED_PIN, LOW);
        digitalWrite(PTT_PIN, HIGH);
        break;
    case MODE_RX:
        digitalWrite(LED_PIN, LOW);
        digitalWrite(PTT_PIN, HIGH);
        initI2SRx(); // Assuming this function exists to initialize I2S in RX mode
        break;
    case MODE_TX:
        txStartTime_ = micros();
        digitalWrite(LED_PIN, HIGH);
        digitalWrite(PTT_PIN, LOW);
        initI2STx(); // Assuming this function exists to initialize I2S in TX mode
        txCachedAudioBytes_ = 0;
        isTxCacheSatisfied_ = false;
        break;
    }
}

bool Radio::readCommandParams(char *buffer, int paramBytesMissing)
{
    if (paramBytesMissing > 0)
    {
        for (int j = 0; j < paramBytesMissing; j++)
        {
            unsigned long waitStart = micros();
            while (!Serial.available())
            {
                if ((micros() - waitStart) > COMMAND_TIMEOUT_MICROS)
                {
                    esp_task_wdt_reset();
                    return false; // Timeout
                }
            }
            buffer[j] = Serial.read();
        }
    }
    return true;
}

void Radio::tuneTo(float freqTx, float freqRx, int tone, int squelch)
{
    int result = dra->group(DRA818_25K, freqTx, freqRx, tone, squelch, 0);
    // Serial.println("tuneTo: " + String(result));
}
