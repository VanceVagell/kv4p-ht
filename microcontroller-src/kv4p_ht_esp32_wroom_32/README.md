# This intends to refactor KV4P-HT to use the AudioTools library

## Todo:

### Rx Todo

- [ ] Configure both the input and output DACs to switch between input and output
- [ ] Configure the input sound to come from the DAC

### Tx Dodo

- [ ] Configure the output sound to come from the 8 bit stream from Serial

## Requirements

We want to create an ESP32 program that can either transmit or receive audio data over a serial connection, using the AudioTools library for audio processing. Here are the key points:

- **Hardware**: ESP32 DevKit V1 with built-in ADC and DAC.
- **Audio**: Mono, 44100Hz sample rate, 8-bit PCM format.
- **Serial**: 921600 baud rate.
- **Modes**:
  - **RX Mode**: Capture audio from the ADC and send it over serial.
  - **TX Mode**: Receive audio data over serial and play it through the DAC.
- **Serial Protocol**: Simple command/data protocol with length indication.

## Implementation

We'll use the AudioTools library to manage the audio streams and conversions. Here's the general approach:

### 1. Initialization

1. Configure the Serial port between the ESP32 and the App
2. Setup the Watchdog timer
3. Setup the status LED
4. Setup the DRA818 control interface on Serial2
5. Setup the AudioTools
   1. Setup the Rx streams
   2. Setup the Tx streams
   3. Initialize the buffered audio stream
