#include "SampleSource.h"

#include <array>
#include <mutex>

template<size_t BufferSize = 2048>
class SerialSampleSource : public SampleSource
{
public:
  SerialSampleSource(int sampleRate)
      : m_sampleRate(sampleRate),
        m_buffer(),
        m_bufferHead(0),
        m_bufferTail(0) {}

  int sampleRate() override
  {
    return m_sampleRate;
  }

  size_t getFrames(Frame_t *frames, int number_frames) override
  {
    std::lock_guard<std::recursive_mutex> m(lock);
    size_t framesAvailable = availableFrames();
    size_t framesToRead = std::min(framesAvailable, static_cast<size_t>(number_frames));

    for (size_t i = 0; i < framesToRead; ++i)
    {
      frames[i] = m_buffer[m_bufferTail];
      m_bufferTail = (m_bufferTail + 1) % m_buffer.size();
    }

    return framesToRead;
  }

  void readFromSerial(size_t bytesToRead)
  {
    std::lock_guard<std::recursive_mutex> m(lock);
    // Calculate the number of frames to read (each frame holds one right sample)
    size_t framesToRead = bytesToRead;

    // Calculate available space in the buffer in terms of frames
    size_t availableFrames = availableSpaceInBytes() / sizeof(Frame_t);

    // Helper function to read and copy samples to the buffer
    auto readAndCopySamples = [&](uint8_t *tempBuffer, size_t frames, size_t bufferOffset)
    {
      Serial.readBytes(tempBuffer, frames);
      for (size_t i = 0; i < frames; ++i)
      {
        size_t currentFrameIndex = (bufferOffset + i)%BufferSize;
        m_buffer[currentFrameIndex].right = tempBuffer[i];
        m_buffer[currentFrameIndex].left = 0;
      }
    };

    if (framesToRead <= availableFrames)
    {
      // Enough space: read all samples at once
      uint8_t *tempBuffer = new uint8_t[bytesToRead];
      readAndCopySamples(tempBuffer, framesToRead, m_bufferHead);
      delete[] tempBuffer;
      m_bufferHead = (m_bufferHead + framesToRead) % m_buffer.size();
    }
    else
    {
      // Not enough space: split the read
      size_t framesToEnd = availableFrames;
      uint8_t *tempBuffer1 = new uint8_t[framesToEnd];
      readAndCopySamples(tempBuffer1, framesToEnd, m_bufferHead);
      delete[] tempBuffer1;

      size_t remainingFrames = framesToRead - framesToEnd;
      uint8_t *tempBuffer2 = new uint8_t[remainingFrames];
      readAndCopySamples(tempBuffer2, remainingFrames, 0); // Write to the beginning
      delete[] tempBuffer2;

      m_bufferHead = remainingFrames;
    }
  }

private:
  int m_sampleRate;
  std::array<Frame_t, BufferSize> m_buffer;
  size_t m_bufferHead;
  size_t m_bufferTail;
  std::recursive_mutex lock;

  size_t availableSpaceInBytes()
  {
    std::lock_guard<std::recursive_mutex> m(lock);
    if (m_bufferHead >= m_bufferTail)
    {
      return (m_buffer.size() - m_bufferHead) + m_bufferTail;
    }
    else
    {
      return m_bufferTail - m_bufferHead;
    }
  }

  size_t availableFrames()
  {
    std::lock_guard<std::recursive_mutex> m(lock);
    return (m_buffer.size() - availableSpaceInBytes()) / sizeof(Frame_t);
  }
};