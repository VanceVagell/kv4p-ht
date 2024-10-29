
#include <Arduino.h>
#include "driver/i2s.h"
#include <math.h>
#include <SPIFFS.h>
#include <FS.h>
#include <array>

#include "SampleSource.h"
#include "I2SOutput.h"

#include "SinWaveGenerator.h"

// number of frames to try and send at once (a frame is a left and right sample)
#define NUM_FRAMES_TO_SEND 512

// void i2sWriterTask(void *param)
// {
//     I2SOutput *output = (I2SOutput *)param;
//     int availableBytes = 0;
//     int buffer_position = 0;
//     Frame_t *frames = (Frame_t *)malloc(sizeof(Frame_t) * NUM_FRAMES_TO_SEND);
//     while (output->continueWritingOutput)
//     {
//         // wait for some data to be requested
//         i2s_event_t evt;
//         if (xQueueReceive(output->m_i2sQueue, &evt, portMAX_DELAY) == pdPASS)
//         {
//             if (evt.type == I2S_EVENT_TX_DONE)
//             {
//                 size_t bytesWritten = 0;
//                 do
//                 {
//                     if (availableBytes == 0)
//                     {
//                         // get some frames from the wave file - a frame consists of a 16 bit left and right sample
//                         output->m_sample_generator->getFrames(frames, NUM_FRAMES_TO_SEND);
//                         // how many bytes do we now have to send
//                         availableBytes = NUM_FRAMES_TO_SEND * sizeof(uint32_t);
//                         // reset the buffer position back to the start
//                         buffer_position = 0;
//                     }
//                     // do we have something to write?
//                     if (availableBytes > 0)
//                     {
//                         // write data to the i2s peripheral
//                         i2s_write(output->m_i2sPort, buffer_position + (uint8_t *)frames,
//                                   availableBytes, &bytesWritten, portMAX_DELAY);
//                         availableBytes -= bytesWritten;
//                         buffer_position += bytesWritten;
//                     }
//                 } while (bytesWritten > 0);
//             }
//         }
//     }
//   vTaskDelete(NULL); // Ensure task deletes itself when done
// }

// void i2sWriterTask(void *param) {
//   I2SOutput *output = (I2SOutput *)param;
//   i2s_event_t evt;
//   Frame_t frames[NUM_FRAMES_TO_SEND]; // Allocate frames on the stack

//   while (output->continueWritingOutput) {
//     // Wait for the I2S_EVENT_TX_DONE event
//     if (xQueueReceive(output->m_i2sQueue, &evt, portMAX_DELAY) == pdPASS) {
//       if (evt.type == I2S_EVENT_TX_DONE) {
//         // Get some frames from the sample generator
//         output->m_sample_generator->getFrames(frames, NUM_FRAMES_TO_SEND);

//         // Write data to the I2S peripheral
//         size_t bytesWritten = 0;
//         i2s_write(output->m_i2sPort, (uint8_t *)frames,
//                   NUM_FRAMES_TO_SEND * sizeof(Frame_t),
//                   &bytesWritten, portMAX_DELAY);
//       }
//     }
//   }
//   vTaskDelete(NULL); // Ensure task deletes itself when done
// }

void i2sWriterTask(void *param)
{
    I2SOutput *output = (I2SOutput *)param;
    size_t bytesWritten = 0;
    Frame_t frames[NUM_FRAMES_TO_SEND]; // Allocate frames on the stack

    // Uncomment this to TX a sin wave
    //   delete output->m_sample_generator;
    //   output->m_sample_generator = new SinWaveGenerator(44100, 3000, 0.5);

    while (output->continueWritingOutput)
    {
        // Get some frames from the sample generator
        output->m_sample_generator->getFrames(frames, NUM_FRAMES_TO_SEND);

        // Write data to the I2S peripheral
        i2s_write(output->m_i2sPort, (uint8_t *)frames,
                  NUM_FRAMES_TO_SEND * sizeof(Frame_t),
                  &bytesWritten, portMAX_DELAY);
    }
    vTaskDelete(NULL); // Ensure task deletes itself when done
}

void I2SOutput::start(i2s_port_t i2sPort, i2s_pin_config_t &i2sPins, SampleSource *sample_generator)
{
    continueWritingOutput = true;

    m_sample_generator = sample_generator;

    static const i2s_config_t i2sTxConfig = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
        .sample_rate = m_sample_generator->sampleRate() - 200,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
        .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
        .intr_alloc_flags = 0,
        .dma_buf_count = 8,
        .dma_buf_len = 1024,
        .use_apll = true};

    i2s_driver_install(I2S_NUM_0, &i2sTxConfig, 0, NULL);
    i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN);

    // // i2s config for writing both channels of I2S
    // i2s_config_t i2sConfig = {
    //     .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
    //     .sample_rate = m_sample_generator->sampleRate(),
    //     .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    //     .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
    //     .communication_format = (i2s_comm_format_t)(I2S_COMM_FORMAT_I2S),
    //     .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    //     .dma_buf_count = 4,
    //     .dma_buf_len = 1024};

    // m_i2sPort = i2sPort;
    // // install and start i2s driver
    // i2s_driver_install(m_i2sPort, &i2sConfig, 4, &m_i2sQueue);

    // // set up the i2s pins
    // // i2s_set_pin(m_i2sPort, &i2sPins);
    // // TODO: ?? i2s_set_adc_mode(I2S_ADC_UNIT, I2S_ADC_CHANNEL)
    // i2s_set_dac_mode(I2S_DAC_CHANNEL_RIGHT_EN);
    // clear the DMA buffers
    i2s_zero_dma_buffer(m_i2sPort);
    // start a task to write samples to the i2s peripheral
    TaskHandle_t writerTaskHandle;
    xTaskCreate(i2sWriterTask, "i2s Writer Task", 4096, this, 1, &writerTaskHandle);
}

void I2SOutput::stop(i2s_port_t i2sPort)
{
    continueWritingOutput = false;
    i2s_driver_uninstall(m_i2sPort);
}