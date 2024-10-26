#include <esp_task_wdt.h>

#include "Radio.hpp"
#include "CommandProcessor.hpp"
#include "AudioProcessor.hpp"

#include "Constants.hpp"
#include "Globals.hpp"

Radio::Radio() : mode_(Mode::STOPPED), txStartTime_(0),
                 txCachedAudioBytes_(0), isTxCacheSatisfied_(false),
                 lastSquelched_(false), fadeCounter_(0), fadeDirection_(0),
                 attenuation_(0), matchedDelimiterTokens(0), i2sStarted_(false)
{
    commandProcessor_ = new CommandProcessor(*this);
    audioProcessor_ = new AudioProcessor(*this);
}

Radio::~Radio()
{
    delete commandProcessor_;
    delete audioProcessor_;
}

void Radio::loop()
{
    try
    {
        processCommands();

        switch (mode_)
        {
        case Mode::STOPPED:
            // Nothing to do in stopped mode
            break;
        case Mode::RX:
            handleRx();
            break;
        case Mode::TX:
            handleTx();
            break;
        default:
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

void Radio::processCommands()
{
    if (Serial.available())
    {
        // Read a command from Android app
        uint8_t tempBuffer[100];
        int bytesRead = 0;

        while (bytesRead < (DELIMITER_LENGTH + 1))
        {
            tempBuffer[bytesRead++] = Serial.read();
        }

        const CommandEnum command = static_cast<CommandEnum>(tempBuffer[DELIMITER_LENGTH]);
        commandProcessor_->processCommand(command);
    }
}

void Radio::handleRx()
{
    audioProcessor_->processRxAudio();
}

void Radio::handleTx()
{
    // Check for runaway tx
    int txSeconds = (micros() - txStartTime_) / 1000000;
    if (txSeconds > RUNAWAY_TX_SEC)
    {
        setMode(Mode::RX);
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
                audioProcessor_->processTxAudio(txCachedAudioBuffer_, txCachedAudioBytes_); // Process cached bytes
            }
            else
            {
                memcpy(txCachedAudioBuffer_ + txCachedAudioBytes_, tempBuffer, bytesRead); // Store bytes to cache
                txCachedAudioBytes_ += bytesRead;
            }
        }

        if (isTxCacheSatisfied_)
        {
            audioProcessor_->processTxAudio(tempBuffer, bytesRead);
        }

        for (int i = 0; i < bytesRead && i < TX_TEMP_AUDIO_BUFFER_SIZE; i++)
        {
            // If we've seen the entire delimiter...
            if (matchedDelimiterTokens == DELIMITER_LENGTH)
            {
                // Process next byte as a command.
                CommandEnum command = static_cast<CommandEnum>(tempBuffer[i]);
                matchedDelimiterTokens = 0;

                commandProcessor_->processCommand(command);
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
    setMode(Mode::STOPPED);
    Serial.flush();
    esp_task_wdt_reset();
}

void Radio::handlePttDownCommand()
{
    setMode(Mode::TX);
    esp_task_wdt_reset();
}

void Radio::handlePttUpCommand()
{
    delay(MS_WAIT_BEFORE_PTT_UP); // Wait just a moment so final tx audio data in DMA buffer can be transmitted.
    setMode(Mode::RX);
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
    case Mode::STOPPED:
        digitalWrite(LED_PIN, LOW);
        digitalWrite(PTT_PIN, HIGH);
        break;
    case Mode::RX:
        digitalWrite(LED_PIN, LOW);
        digitalWrite(PTT_PIN, HIGH);
        initI2SRx(); // Assuming this function exists to initialize I2S in RX mode
        break;
    case Mode::TX:
        txStartTime_ = micros();
        digitalWrite(LED_PIN, HIGH);
        digitalWrite(PTT_PIN, LOW);
        initI2STx(); // Assuming this function exists to initialize I2S in TX mode
        txCachedAudioBytes_ = 0;
        isTxCacheSatisfied_ = false;
        break;
    default:
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

void Radio::initI2SRx()
{
    // Remove any previous driver (rx or tx) that may have been installed.
    if (i2sStarted_)
    {
        i2s_driver_uninstall(I2S_NUM_0);
    }
    i2sStarted_ = true;

    // Initialize ADC
    adc1_config_width(ADC_WIDTH_BIT_12);
    adc1_config_channel_atten(ADC1_CHANNEL_6, ADC_ATTEN_DB_0);

    static const i2s_config_t i2sRxConfig = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_ADC_BUILT_IN),
        .sample_rate = AUDIO_SAMPLE_RATE + SAMPLING_RATE_OFFSET,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
        .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
        .communication_format = i2s_comm_format_t(I2S_COMM_FORMAT_I2S | I2S_COMM_FORMAT_I2S_MSB),
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = 4,
        .dma_buf_len = I2S_READ_LEN,
        .use_apll = true,
        .tx_desc_auto_clear = false,
        .fixed_mclk = 0};

    ESP_ERROR_CHECK(i2s_driver_install(I2S_NUM_0, &i2sRxConfig, 0, NULL));
    ESP_ERROR_CHECK(i2s_set_adc_mode(I2S_ADC_UNIT, I2S_ADC_CHANNEL));
}

void Radio::initI2STx()
{
    // Remove any previous driver (rx or tx) that may have been installed.
    if (i2sStarted_)
    {
        i2s_driver_uninstall(I2S_NUM_0);
    }
    i2sStarted_ = true;

    static const i2s_config_t i2sTxConfig = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
        .sample_rate = AUDIO_SAMPLE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
        .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
        .intr_alloc_flags = 0,
        .dma_buf_count = 8,
        .dma_buf_len = I2S_WRITE_LEN,
        .use_apll = true};

    i2s_driver_install(I2S_NUM_0, &i2sTxConfig, 0, NULL);
    i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN);
}
