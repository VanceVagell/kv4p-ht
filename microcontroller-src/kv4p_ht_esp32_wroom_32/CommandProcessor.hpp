#include <Arduino.h>
#include "Radio.hpp"

class CommandProcessor
{
public:
    CommandProcessor(Radio &radio) : radio_(radio) {}

    void processCommand(uint8_t command)
    {
        switch (command)
        {
        case COMMAND_STOP:
            radio_.handleStopCommand();
            break;
        case COMMAND_TUNE_TO:
        {
            char paramsStr[MAX_COMMAND_PARAMS_LENGTH + 1] = {0};
            if (radio_.readCommandParams(paramsStr, MAX_COMMAND_PARAMS_LENGTH))
            {
                radio_.handleTuneToCommand(String(paramsStr));
            }
            break;
        }
        case COMMAND_FILTERS:
        {
            char paramsStr[FILTERS_PARAMS_LENGTH + 1] = {0};
            if (radio_.readCommandParams(paramsStr, FILTERS_PARAMS_LENGTH))
            {
                radio_.handleFiltersCommand(String(paramsStr));
            }
            break;
        }
        case COMMAND_PTT_DOWN:
            radio_.handlePttDownCommand();
            break;
        case COMMAND_PTT_UP:
            radio_.handlePttUpCommand();
            break;
        case COMMAND_GET_FIRMWARE_VER:
            radio_.handleGetFirmwareVerCommand();
            break;
            // ... (other commands)
        }
    }

private:
    Radio &radio_;
};