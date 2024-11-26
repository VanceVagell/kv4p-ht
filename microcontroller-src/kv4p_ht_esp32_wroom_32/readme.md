# KV4P-HT Serial Interface Documentation

This document provides detailed instructions on how to interact with the KV4P-HT device via its serial console. It covers the serial connection setup, communication protocol, and command usage, including changing frequencies, controlling Push-To-Talk (PTT), and configuring filters.

---

## Table of Contents

1. [Serial Connection Setup](#1-serial-connection-setup)
2. [Communication Protocol](#2-communication-protocol)
   - [2.1. Delimiter](#21-delimiter)
   - [2.2. Command Structure](#22-command-structure)
3. [Commands](#3-commands)
   - [3.1. COMMAND_PTT_DOWN (0x01)](#31-command_ptt_down-0x01)
   - [3.2. COMMAND_PTT_UP (0x02)](#32-command_ptt_up-0x02)
   - [3.3. COMMAND_TUNE_TO (0x03)](#33-command_tune_to-0x03)
   - [3.4. COMMAND_FILTERS (0x04)](#34-command_filters-0x04)
   - [3.5. COMMAND_STOP (0x05)](#35-command_stop-0x05)
   - [3.6. COMMAND_GET_FIRMWARE_VER (0x06)](#36-command_get_firmware_ver-0x06)
4. [Examples](#4-examples)
   - [4.1. Changing Frequencies](#41-changing-frequencies)
   - [4.2. Starting Transmission](#42-starting-transmission)
   - [4.3. Stopping Transmission](#43-stopping-transmission)
   - [4.4. Configuring Filters](#44-configuring-filters)
   - [4.5. Retrieving Firmware Version](#45-retrieving-firmware-version)
5. [Additional Notes](#5-additional-notes)

---

## 1. Serial Connection Setup

To interact with the KV4P-HT device, establish a serial connection using the following parameters:

- **Port**: The serial port connected to the KV4P-HT device (e.g., `COM3`, `/dev/ttyUSB0`).
- **Baud Rate**: `921600` bits per second.
- **Data Bits**: `8` bits.
- **Parity**: `None`.
- **Stop Bits**: `1` bit.
- **Flow Control**: `None`.

**Note**: Ensure that your serial communication software or terminal emulator supports the high baud rate of 921600 bps.

---

## 2. Communication Protocol

### 2.1. Delimiter

All commands sent to the KV4P-HT device must be preceded by an 8-byte delimiter. This delimiter signals the start of a new command and helps synchronize communication.

**Delimiter Byte Sequence**:

```plaintext
0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00
```

### 2.2. Command Structure

After the delimiter, send a single-byte command code followed by any required parameters. The general structure is as follows:

```plaintext
[Delimiter][Command Code][Parameters (if any)]
```

- **Delimiter**: 8 bytes (as specified above).
- **Command Code**: 1 byte (see the list of commands below).
- **Parameters**: Varies by command.

---

## 3. Commands

Below is a list of commands supported by the KV4P-HT device, along with their descriptions and parameter requirements.

### 3.1. COMMAND_PTT_DOWN (0x01)

- **Description**: Initiates transmission mode. The device expects audio data to follow, which will be transmitted over the radio.
- **Parameters**: None.
- **Usage**:
  - Send the delimiter.
  - Send the command code `0x01`.

### 3.2. COMMAND_PTT_UP (0x02)

- **Description**: Stops transmission and returns the device to receive mode.
- **Parameters**: None.
- **Usage**:
  - Send the delimiter.
  - Send the command code `0x02`.

### 3.3. COMMAND_TUNE_TO (0x03)

- **Description**: Changes the transmit and receive frequencies, as well as the tone and squelch settings.
- **Parameters**: 19 bytes total.
  - **Transmit Frequency**: 8 ASCII characters (e.g., `146.5200`).
  - **Receive Frequency**: 8 ASCII characters.
  - **Tone**: 2 ASCII characters representing an integer (e.g., `00` for none).
  - **Squelch**: 1 ASCII character (`0` thru `8`).
- **Usage**:
  - Send the delimiter.
  - Send the command code `0x03`.
  - Send the parameters concatenated as a string.

**Parameter Details**:

| Parameter             | Length | Description                                          |
|-----------------------|--------|------------------------------------------------------|
| Transmit Frequency    | 8      | Frequency in MHz (e.g., `146.5200`)                  |
| Receive Frequency     | 8      | Frequency in MHz                                     |
| Tone                  | 2      | CTCSS tone frequency code (`00` for none)            |
| Squelch               | 1      | `0` through `8` (off through maximum squelch)        |

### 3.4. COMMAND_FILTERS (0x04)

- **Description**: Configures the emphasis, high-pass, and low-pass filters.
- **Parameters**: 3 bytes.
  - Each byte is either `0` (disable) or `1` (enable).
  - Order: `[Emphasis][High-Pass][Low-Pass]`.
- **Usage**:
  - Send the delimiter.
  - Send the command code `0x04`.
  - Send the 3-byte parameter string.

### 3.5. COMMAND_STOP (0x05)

- **Description**: Stops all operations and resets the device to standby mode, waiting for the next command.
- **Parameters**: None.
- **Usage**:
  - Send the delimiter.
  - Send the command code `0x05`.

### 3.6. COMMAND_GET_FIRMWARE_VER (0x06)

- **Description**: Requests the firmware version from the device.
- **Parameters**: None.
- **Response**: The device will send back the string `VERSION` followed by an 8-character firmware version (e.g., `00000002`).
- **Usage**:
  - Send the delimiter.
  - Send the command code `0x06`.

---

## 4. Examples

### 4.1. Changing Frequencies

**Objective**: Set the transmit frequency to `146.5200` MHz, receive frequency to `146.5200` MHz, no tone, and squelch off.

**Command Sequence**:

1. **Delimiter**:

   ```plaintext
   0xFF 0x00 0xFF 0x00 0xFF 0x00 0xFF 0x00
   ```

2. **Command Code**:

   ```plaintext
   0x03 (COMMAND_TUNE_TO)
   ```

3. **Parameters**:

   - Transmit Frequency: `146.5200` (ASCII)
   - Receive Frequency: `146.5200` (ASCII)
   - Tone: `00` (ASCII)
   - Squelch: `0` (ASCII)

**Full Byte Sequence** (in hexadecimal):

```plaintext
FF 00 FF 00 FF 00 FF 00 03 31 34 36 2E 35 32 30 30 31 34 36 2E 35 32 30 30 30 30 30
```

**Explanation**:

- `31 34 36 2E 35 32 30 30` is ASCII for `146.5200`.
- Tone `00`: `30 30`.
- Squelch `0`: `30`.

### 4.2. Starting Transmission

**Objective**: Begin transmitting audio data.

**Command Sequence**:

1. **Delimiter**:

   ```plaintext
   0xFF 0x00 0xFF 0x00 0xFF 0x00 0xFF 0x00
   ```

2. **Command Code**:

   ```plaintext
   0x01 (COMMAND_PTT_DOWN)
   ```

**Following Data**:

- Send the audio data bytes immediately after the command.
- Audio data should be raw 8-bit PCM audio sampled at **44.1 kHz**.

### 4.3. Stopping Transmission

**Objective**: Stop transmitting and return to receive mode.

**Command Sequence**:

1. **While in transmission mode**, send the delimiter and the `COMMAND_PTT_UP` code.

   ```plaintext
   0xFF 0x00 0xFF 0x00 0xFF 0x00 0xFF 0x00 0x02
   ```

**Note**: There may be a brief delay (~40 ms) to allow final audio data to be transmitted.

### 4.4. Configuring Filters

**Objective**: Enable emphasis and disable high-pass and low-pass filters.

**Command Sequence**:

1. **Delimiter**:

   ```plaintext
   0xFF 0x00 0xFF 0x00 0xFF 0x00 0xFF 0x00
   ```

2. **Command Code**:

   ```plaintext
   0x04 (COMMAND_FILTERS)
   ```

3. **Parameters**:

   - Emphasis: `1`
   - High-Pass: `0`
   - Low-Pass: `0`

**Full Byte Sequence** (in hexadecimal):

```plaintext
FF 00 FF 00 FF 00 FF 00 04 31 30 30
```

### 4.5. Retrieving Firmware Version

**Objective**: Get the firmware version from the device.

**Command Sequence**:

1. **Delimiter**:

   ```plaintext
   0xFF 0x00 0xFF 0x00 0xFF 0x00 0xFF 0x00
   ```

2. **Command Code**:

   ```plaintext
   0x06 (COMMAND_GET_FIRMWARE_VER)
   ```

**Expected Response**:

- The device will send back `VERSION00000002` (assuming the firmware version is `00000002`).

---

## 5. Additional Notes

- **Audio Data Format**:
  - When transmitting (after `COMMAND_PTT_DOWN`), send raw 8-bit PCM audio data sampled at **44.1 kHz**.
  - Ensure continuous data flow to prevent underruns or audio glitches.

- **Runaway Transmission Prevention**:
  - The device has a built-in safeguard that limits continuous transmission to **200 seconds** to prevent unintended prolonged transmissions.

- **Squelch Behavior**:
  - When in receive mode, the device handles squelch transitions smoothly using fade-in and fade-out effects to prevent audio pops.

- **Buffer Sizes**:
  - The device uses internal buffers to manage audio data. Avoid sending data exceeding buffer capacities in a single burst.

- **Watchdog Timer (WDT)**:
  - The device resets its watchdog timer regularly. If unresponsive, it may automatically reboot to recover from potential lock-ups.

- **Error Handling**:
  - Unexpected commands or malformed data may be ignored. Ensure that commands and parameters are correctly formatted according to the protocol.

---

**Disclaimer**: Always test commands in a controlled environment to prevent unintended behavior. This documentation assumes familiarity with serial communication protocols and the KV4P-HT device's operational context.
