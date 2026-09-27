#pragma once

#include <stdint.h>

using effect_t = int16_t;

class AudioEffect {
public:
  virtual ~AudioEffect() = default;
  virtual effect_t process(effect_t input) { return input; }
  virtual AudioEffect *clone() { return nullptr; }

  bool active() const { return _active; }
  void setActive(bool active) { _active = active; }
  uint8_t id() const { return _id; }
  void setId(uint8_t id) { _id = id; }

private:
  bool _active = true;
  uint8_t _id = 0;
};
