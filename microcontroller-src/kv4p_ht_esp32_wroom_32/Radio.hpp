#pragma once

#include <Arduino.h>
#include <SPI.h>

#include "CommandEnum.hpp"
#include "ICommandProcessor.hpp"
#include "IAudioProcessor.hpp"
#include "Constants.hpp"

class CommandProcessor;
class AudioProcessor;

/// @brief This is the main class to manage the state and behavior of the ESP32
class Radio
{
  friend class CommandProcessor;
  friend class AudioProcessor;
public:
    /// @brief This is an Enum used to manage the current mode of the Radio
    enum class Mode : uint8_t
    {
        STOPPED,
        RX,
        TX
    };

    /// @brief Default Constructor
    Radio();

    /// @brief Default Destructor
    ~Radio();

    /// @brief Entry point for this class that gets called each loop
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

    /// @brief Change the mode of the Radio
    /// @param newMode The new mode for the Radio
    void setMode(Mode newMode);

    /// @brief Reads command parameters from the serial port.
    ///
    /// This function reads the remaining command parameters from the serial port.
    /// It waits for the specified number of bytes to be available, with a timeout.
    ///
    /// @param buffer Pointer to the buffer where the parameters will be stored.
    /// @param paramBytesMissing  The number of bytes still missing from the command.
    /// @return True if all parameters were read successfully, false on timeout.
    bool readCommandParams(char *buffer, int paramBytesMissing);

    /// @brief Tunes the radio to the specified transmit and receive frequencies.
    ///
    /// This function configures the DRA818V radio module to operate at the given
    /// transmit and receive frequencies, with the specified tone and squelch settings.
    ///
    /// @param freqTx The desired transmit frequency in MHz.
    /// @param freqRx The desired receive frequency in MHz.
    /// @param tone The CTCSS tone frequency in Hz.
    /// @param squelch The squelch level (0-8, where 0 is open and 8 is tightest).
    void tuneTo(float freqTx, float freqRx, int tone, int squelch);

    /// @brief Initializes the I2S peripheral for receiving audio data.
    /// 
    /// This function configures the I2S hardware in master receive mode, using the
    /// built-in ADC to sample audio data. It sets up the I2S driver with the 
    /// specified parameters, including sample rate, bit depth, and channel format.
    /// 
    /// If an I2S driver is already installed, it will be uninstalled before
    /// initializing the new configuration.
    void initI2SRx();

    /// @brief Initializes the I2S peripheral for transmitting audio data.
    /// 
    /// This function configures the I2S hardware in master transmit mode, using the
    /// built-in DAC to output audio data. It sets up the I2S driver with the 
    /// specified parameters, including sample rate, bit depth, and channel format.
    /// 
    /// If an I2S driver is already installed, it will be uninstalled before
    /// initializing the new configuration.
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
