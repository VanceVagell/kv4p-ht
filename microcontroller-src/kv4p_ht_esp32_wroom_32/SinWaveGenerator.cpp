#include <math.h>
#include "SinWaveGenerator.h"

SinWaveGenerator::SinWaveGenerator(int sample_rate, int frequency, float magnitude)
{
    m_sample_rate = sample_rate;
    m_frequency = frequency;
    m_magnitude = magnitude;
    m_current_position = 0;
}

size_t SinWaveGenerator::getFrames(Frame_t *frames, int number_frames)
{
    float full_wave_samples = m_sample_rate / m_frequency;
    float step_per_sample = M_TWOPI / full_wave_samples;
    // fill the buffer with data from the file wrapping around if necessary
    for (int i = 0; i < number_frames; i++)
    {
        frames[i].left = frames[i].right = __UINT8_MAX__ * m_magnitude * sin(m_current_position);
        m_current_position += step_per_sample;
        // wrap around to maintain numerical stability
        if (m_current_position > M_TWOPI)
        {
            m_current_position -= M_TWOPI;
        }
    }
    return number_frames;
}
