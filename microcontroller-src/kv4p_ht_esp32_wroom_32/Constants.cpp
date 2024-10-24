#include "Constants.hpp"

const uint8_t FIRMWARE_VER[8] = {'0', '0', '0', '0', '0', '0', '0', '1'}; // Should be 8 characters representing a zero-padded version, like 00000001.
const uint8_t VERSION_PREFIX[7] = {'V', 'E', 'R', 'S', 'I', 'O', 'N'};    // Must match RadioAudioService.VERSION_PREFIX in Android app.

const uint8_t delimiter[DELIMITER_LENGTH] = {0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00};
