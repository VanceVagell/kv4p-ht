import serial
import time
import math

# Configure the serial port
ser = serial.Serial('/dev/ttyUSB0', 921600)

# Sine wave parameters
frequency = 300  # Hz
amplitude = 127  # 0-255
offset = 128  # Center the sine wave around 128
sampling_frequency = 44100  # Hz

try:
    while True:
        # Calculate the time for the current sample
        t = time.time()

        # Calculate the sine wave value
        value = int(amplitude * math.sin(2 * math.pi * frequency * t) + offset)

        # Ensure the value is within the 0-255 range
        value = max(0, min(value, 255))

        # Write the value to the serial port as a single byte
        ser.write(bytes([value]))  # Convert to byte array with a single element

        # Calculate the time to sleep for the next sample
        time_to_sleep = 1/sampling_frequency - (time.time() - t)

        # Wait for the calculated time
        if time_to_sleep > 0:
            time.sleep(time_to_sleep)
except KeyboardInterrupt:
    print("Exiting...")
    ser.close()
