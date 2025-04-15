# KV4P-HT Communication Protocol

## Overview

The KV4P-HT protocol defines the communication interface between the microcontroller and external systems. It specifies message structures and command types for data exchange.

## Protocol Version

- **Current Version:** 2.1
- **Changelog:**
  - Initial version with core command set.
  - Parameter length field upgraded from 1 byte to 2 bytes (`uint16_t`).
  - Added `COMMAND_WINDOW_UPDATE`.
  - `COMMAND_VERSION` payload now includes `windowSize`.
  - Audio streams are now OPUS encoded.
  - Window-based flow control implemented for all incoming commands, inspired by HTTP/2.
  - Command delimiter now 4 byte `0xDEADBEEF`

## Packet Structure

Each message consists of the following fields:

| Field             | Size (bytes) | Description                                |
| ----------------- | ------------ | ------------------------------------------ |
| Command Delimiter | 4            | Fixed value (`0xDEADBEEF`)                 |
| Command           | 1            | Identifies the request or response         |
| Parameter Length  | 2            | Length of the following parameters (0-65535) |
| Parameters        | 0-2048       | Command-specific data                      |

## Incoming Commands (Android → ESP32)

| Command Code | Name                    | Description                              |
| ------------ | ----------------------- | ---------------------------------------- |
| `0x01`       | `COMMAND_HOST_PTT_DOWN` | Push-to-talk activation                  |
| `0x02`       | `COMMAND_HOST_PTT_UP`   | Push-to-talk deactivation                |
| `0x03`       | `COMMAND_HOST_GROUP`    | Set group (parameters required)          |
| `0x04`       | `COMMAND_HOST_FILTERS`  | Set filters (parameters required)        |
| `0x05`       | `COMMAND_HOST_STOP`     | Stop current operation                   |
| `0x06`       | `COMMAND_HOST_CONFIG`   | Configure device (may return version)    |
| `0x07`       | `COMMAND_HOST_TX_AUDIO` | Receive Tx OPUS audio data (payload required, flow-controlled) |
| `0x08`       | `COMMAND_WINDOW_UPDATE` | Updates available receive window         |

## Outgoing Commands (ESP32 → Android)

| Command Code | Name                    | Description                            |
| ------------ | ----------------------- | -------------------------------------- |
| `0x53`       | `COMMAND_SMETER_REPORT` | Reports RSSI level                     |
| `0x44`       | `COMMAND_PHYS_PTT_DOWN` | Physical push-to-talk activation       |
| `0x55`       | `COMMAND_PHYS_PTT_UP`   | Physical push-to-talk deactivation     |
| `0x01`       | `COMMAND_DEBUG_INFO`    | Sends debug info message               |
| `0x02`       | `COMMAND_DEBUG_ERROR`   | Sends debug error message              |
| `0x03`       | `COMMAND_DEBUG_WARN`    | Sends debug warning message            |
| `0x04`       | `COMMAND_DEBUG_DEBUG`   | Sends debug debug-level message        |
| `0x05`       | `COMMAND_DEBUG_TRACE`   | Sends debug trace message              |
| `0x06`       | `COMMAND_HELLO`         | Hello handshake message                |
| `0x07`       | `COMMAND_RX_AUDIO`      | Sends Rx OPUS audio data (payload required) |
| `0x08`       | `COMMAND_VERSION`       | Sends firmware version information     |

## Command Parameters

### `COMMAND_VERSION` Parameters

```c
struct version {
  uint16_t    ver;               // 2 bytes
  char        radioModuleStatus; // 1 byte
  hw_ver_t    hw;                // 1 byte
  size_t      windowSize;        // 4 bytes
} __attribute__((__packed__));
typedef struct version Version;
```

### `COMMAND_SMETER_REPORT` Parameters

```c
struct rssi {
  uint8_t     rssi; // 1 byte
} __attribute__((__packed__));
typedef struct rssi Rssi;
```

### `COMMAND_HOST_GROUP` Parameters

```c
struct group {
  uint8_t bw;       // 1 byte
  float freq_tx;    // 4 bytes
  float freq_rx;    // 4 bytes
  uint8_t ctcss_tx; // 1 byte
  uint8_t squelch;  // 1 byte
  uint8_t ctcss_rx; // 1 byte
} __attribute__((__packed__));
typedef struct group Group;
```

### `COMMAND_HOST_FILTERS` Parameters

```c
struct filters {
  uint8_t flags;  // 1 byte - Uses bitmask for pre, high, and low
} __attribute__((__packed__));
typedef struct filters Filters;

#define FILTER_PRE  (1 << 0) // Bit 0
#define FILTER_HIGH (1 << 1) // Bit 1
#define FILTER_LOW  (1 << 2) // Bit 2
```

### `COMMAND_HOST_CONFIG` Parameters

```c
struct config {
  uint8_t radioType; // 1 byte
} __attribute__((__packed__));
typedef struct config Config;
```

### `COMMAND_WINDOW_UPDATE` Parameters

```c
struct window_update {
  size_t windowSize; // 4 bytes
} __attribute__((__packed__));
typedef struct window_update WindowUpdate;
```

## Flow Control

A window-based flow control mechanism, inspired by HTTP/2, is used to regulate the amount of data sent from Android to the ESP32:

1. **Window Size Declaration**:
   - The ESP32 sends a "desired" initial window size in the `COMMAND_VERSION` payload.
   - This typically matches the size of its internal USB receive buffer.

2. **Window Consumption**:
   - Each incoming command from Android reduces the remaining window size by the size of the entire packet.
   - All commands, not just `COMMAND_HOST_TX_AUDIO`, are subject to this flow control.

3. **Blocking on Exhaustion**:
   - If the next packet does not fit in the remaining window, Android must wait until space is available.
   - Effectively, this blocks transmission when `windowSize < packet size`.

4. **Window Replenishment**:
   - Once the ESP32 finishes processing a packet, it sends a `COMMAND_WINDOW_UPDATE` message.
   - This increases the window size, allowing Android to resume transmission.

4. **Optional Implementation**:
   - Implementing flow control is optional.
   - A compliant implementation may ignore windowSize and COMMAND_WINDOW_UPDATE messages entirely.
   - This is primarily useful when dealing with fast data sources like APRS modems that can generate large amounts of audio data rapidly.
   - When audio is sourced from the ADC, it is inherently flow-controlled by the sampling hardware, making software flow control less necessary.



## Command Handling Strategy

- Most commands follow a **fire-and-forget** approach.
- Some commands may trigger a reply (indicated in comments).
- There are no explicit response types; responses are sent as separate commands.
- All ESP32 incoming commands are subject to **window-based flow control**.

## Byte Order and Bit Significance
- All multi-byte fields are encoded in little-endian format.
- Bitmask values follow LSB-first ordering (bit 0 is the least significant).

## Example Packets

### Push-to-talk activation

```
[ 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x00, 0x00 ]
```

- `0xDEADBEEF`: Command Delimiter
- `0x01`: `COMMAND_HOST_PTT_DOWN`
- `0x00 0x00`: Parameter Length (no parameters)

### Example Debug Message

```
[ 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x05, 0x00, 'E', 'r', 'r', 'o', 'r' ]
```

- `0xDEADBEEF`: Command Delimiter
- `0x01`: `COMMAND_DEBUG_INFO`
- `0x05 0x00`: Parameter Length (5 bytes)
- `'Error'`: Debug message content
