#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

class Print {
public:
  virtual ~Print() = default;
  virtual size_t write(uint8_t b) = 0;

  virtual size_t write(const uint8_t *buffer, size_t size) {
    for (size_t i = 0; i < size; i++) {
      write(buffer[i]);
    }
    return size;
  }
};

class Stream : public Print {
public:
  virtual int available() = 0;
  virtual int read() = 0;
  virtual int peek() = 0;
  virtual void flush() {}
};

class NativeSerial : public Stream {
public:
  int available() override { return 0; }
  int read() override { return -1; }
  int peek() override { return -1; }
  size_t write(uint8_t) override { return 1; }
};

inline NativeSerial Serial;
