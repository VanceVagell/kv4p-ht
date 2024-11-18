#pragma once

#include <cstdint>

enum class MsgType : uint8_t
{
  DATA = 0xF0,
  CMD = 0x0F,
  DEFAULT_CMD = 0
};
