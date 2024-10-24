#pragma once

#include <Arduino.h>

class IAudioProcessor
{
public:
    IAudioProcessor() = default;
    virtual ~IAudioProcessor() = default;

    virtual void processRxAudio() = 0;

    virtual void processTxAudio(uint8_t tempBuffer[], int bytesRead) = 0;
};
