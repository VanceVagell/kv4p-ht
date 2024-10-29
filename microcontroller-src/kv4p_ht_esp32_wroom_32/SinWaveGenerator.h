#ifndef __sin_wave_generator_h__
#define __sin_wave_generator_h__

#include "SampleSource.h"

class SinWaveGenerator : public SampleSource
{
private:
    int m_sample_rate;
    int m_frequency;
    float m_magnitude;
    float m_current_position;

public:
    SinWaveGenerator(int sample_rate, int frequency, float magnitude);
    virtual int sampleRate() { return m_sample_rate; }
    // This should fill the samples buffer with the specified number of frames
    // A frame contains a LEFT and a RIGHT sample. Each sample should be signed 16 bits
    virtual void getFrames(Frame_t *frames, int number_frames);
};

#endif