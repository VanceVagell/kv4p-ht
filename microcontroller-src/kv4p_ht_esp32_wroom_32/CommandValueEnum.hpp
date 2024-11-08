#pragma once

#include <cstdint>

// Commands defined here must match the Android app
enum class CommandValue : uint8_t
{
    COMMAND_PTT_DOWN = 1,        // start transmitting audio that Android app will send
    COMMAND_PTT_UP = 2,          // stop transmitting audio, go into RX mode
    COMMAND_TUNE_TO = 3,         // change the frequency
    COMMAND_FILTERS = 4,         // toggle filters on/off
    COMMAND_STOP = 5,            // stop everything, just wait for next command
    COMMAND_GET_FIRMWARE_VER = 6 // report FIRMWARE_VER in the format '00000001' for 1 (etc.)
};
