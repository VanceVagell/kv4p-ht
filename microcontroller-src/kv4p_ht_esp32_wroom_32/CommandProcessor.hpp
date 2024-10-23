#include <Arduino.h>
#include "Radio.hpp"

#include "CommandEnum.hpp"

class CommandProcessor
{
public:
    CommandProcessor(Radio &radio) : radio_(radio) {}

    void processCommand(CommandEnum command)
    {
        switch (command)
        {
        case CommandEnum::COMMAND_STOP:
            radio_.handleStopCommand();
            break;
        case CommandEnum::COMMAND_TUNE_TO:
        {
            char paramsStr[MAX_COMMAND_PARAMS_LENGTH + 1] = {0};
            if (radio_.readCommandParams(paramsStr, MAX_COMMAND_PARAMS_LENGTH))
            {
                radio_.handleTuneToCommand(String(paramsStr));
            }
            break;
        }
        case CommandEnum::COMMAND_FILTERS:
        {
            char paramsStr[FILTERS_PARAMS_LENGTH + 1] = {0};
            if (radio_.readCommandParams(paramsStr, FILTERS_PARAMS_LENGTH))
            {
                radio_.handleFiltersCommand(String(paramsStr));
            }
            break;
        }
        case CommandEnum::COMMAND_PTT_DOWN:
            radio_.handlePttDownCommand();
            break;
        case CommandEnum::COMMAND_PTT_UP:
            radio_.handlePttUpCommand();
            break;
        case CommandEnum::COMMAND_GET_FIRMWARE_VER:
            radio_.handleGetFirmwareVerCommand();
            break;
        default:
            break;
        }
    }

private:
    Radio &radio_;
};