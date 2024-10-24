#pragma once

#include <Arduino.h>
#include <SPI.h>
// #include <DRA818.h>
#include <driver/i2s.h>

#include "Radio.hpp"
#include "IAudioProcessor.hpp"

#include "Constants.hpp"

// ... (your other includes and definitions, like I2S_NUM_0, SQ_PIN, etc.)

class AudioProcessor : public IAudioProcessor
{
public:
    AudioProcessor(Radio &radio) : radio_(radio) {}

    void processRxAudio()
    {
        size_t bytesRead = 0;
        uint8_t buffer32[I2S_READ_LEN * 4] = {0};
        ESP_ERROR_CHECK(i2s_read(I2S_NUM_0, &buffer32, sizeof(buffer32), &bytesRead, 100));
        size_t samplesRead = bytesRead / 4;

        byte buffer8[I2S_READ_LEN] = {0};
        bool squelched = (digitalRead(SQ_PIN) == HIGH);

        // Check for squelch status change
        if (squelched != radio_.lastSquelched_)
        {
            if (squelched)
            {
                // Start fade-out
                radio_.fadeCounter_ = FADE_SAMPLES;
                radio_.fadeDirection_ = -1;
            }
            else
            {
                // Start fade-in
                radio_.fadeCounter_ = FADE_SAMPLES;
                radio_.fadeDirection_ = 1;
            }
        }
        radio_.lastSquelched_ = squelched;

        int attenuationIncrement = ATTENUATION_MAX / FADE_SAMPLES;

        for (int i = 0; i < samplesRead; i++)
        {
            uint8_t sampleValue;

            // Extract 8-bit sample from 32-bit buffer
            sampleValue = buffer32[i * 4 + 3] << 4;
            sampleValue |= buffer32[i * 4 + 2] >> 4;

            // Adjust attenuation during fade
            if (radio_.fadeCounter_ > 0)
            {
                radio_.fadeCounter_--;
                radio_.attenuation_ += radio_.fadeDirection_ * attenuationIncrement;
                radio_.attenuation_ = max(0, min(radio_.attenuation_, ATTENUATION_MAX));
            }
            else
            {
                radio_.attenuation_ = squelched ? 0 : ATTENUATION_MAX;
                radio_.fadeDirection_ = 0;
            }

            // Apply attenuation to the sample
            int adjustedSample = (((int)sampleValue - 128) * radio_.attenuation_) >> 8;
            adjustedSample += 128;
            buffer8[i] = (uint8_t)adjustedSample;
        }

        Serial.write(buffer8, samplesRead);
    }

    void processTxAudio(uint8_t tempBuffer[], int bytesRead)
    {
        if (bytesRead == 0)
        {
            return;
        }

        // Convert the 8-bit audio data to 16-bit
        uint8_t buffer16[bytesRead * 2] = {0};
        for (int i = 0; i < bytesRead; i++)
        {
            buffer16[i * 2 + 1] = tempBuffer[i]; // Move 8-bit audio into top 8 bits of 16-bit byte that I2S expects.
        }

        size_t totalBytesWritten = 0;
        size_t bytesWritten;
        size_t bytesToWrite = sizeof(buffer16);
        do
        {
            ESP_ERROR_CHECK(i2s_write(I2S_NUM_0, buffer16 + totalBytesWritten, bytesToWrite, &bytesWritten, 100));
            totalBytesWritten += bytesWritten;
            bytesToWrite -= bytesWritten;
        } while (bytesToWrite > 0);
    }

private:
    Radio &radio_;
};
