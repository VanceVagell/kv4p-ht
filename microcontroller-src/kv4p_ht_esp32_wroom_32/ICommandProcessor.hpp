#pragma once

#include "CommandEnum.hpp"

class ICommandProcessor
{
public:
    ICommandProcessor() = default;
    virtual ~ICommandProcessor() = default;
    virtual void processCommand(CommandEnum command) = 0;
};
