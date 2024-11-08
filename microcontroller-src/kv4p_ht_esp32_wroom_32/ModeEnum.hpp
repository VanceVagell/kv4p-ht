#pragma once

#include <cstdint>

enum class Mode : u_int8_t
{
    MODE_TX = 0,
    MODE_RX = 1,
    MODE_STOPPED = 2
};