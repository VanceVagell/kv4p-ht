#pragma once

#include <Arduino.h>
#include <SPI.h>

#include "CommandEnum.hpp"
#include "ICommandProcessor.hpp"
#include "IAudioProcessor.hpp"
#include "Constants.hpp"

class CommandProcessor;
class AudioProcessor;

class Radio
{
  friend class CommandProcessor;
  friend class AudioProcessor;
public:
    enum class Mode : uint8_t
    {
        STOPPED,
        RX,
        TX
    };

    Radio();
    ~Radio();

    void loop();

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

    void initI2SRx();
    void initI2STx();

    ICommandProcessor* commandProcessor_;
    IAudioProcessor* audioProcessor_;
    Mode mode_;
    unsigned long txStartTime_;
    bool lastSquelched_;
    int fadeCounter_;
    int fadeDirection_;
    int attenuation_;
    int txCachedAudioBytes_;
    bool isTxCacheSatisfied_; ///< Will be true when the DAC has enough cached tx data to avoid any stuttering (i.e. at least TX_CACHED_AUDIO_BUFFER_SIZE bytes).
    uint8_t txCachedAudioBuffer_[TX_CACHED_AUDIO_BUFFER_SIZE];
    int matchedDelimiterTokens;
    // have we installed an I2S driver at least once?
    bool i2sStarted_;
};
