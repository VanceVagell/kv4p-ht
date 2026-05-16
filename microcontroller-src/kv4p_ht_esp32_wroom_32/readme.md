# KV4P-HT Communication Protocol

## Overview

The KV4P-HT protocol defines the communication interface between the microcontroller and external systems. It specifies message structures and command types for data exchange.

## Protocol Version

* **Current Version:** 2.2
* **Changelog:**
  * Serial transport now uses KV4P KISS framing. The old `0xDEADBEEF` delimiter and top-level length field are removed.
  * Standard KISS DATA frames carry AX.25 packets directly.
  * kv4p-specific commands are carried in KISS SETHARDWARE vendor frames with payload prefix `"KV4P"` and protocol version `1`.
  * `COMMAND_HELLO` now carries the version/status payload after firmware radio initialization completes.
  * Android now sends `COMMAND_HOST_DESIRED_STATE` snapshots for radio config, filters, PTT, audio-open, high-power, and RSSI state.
  * Firmware replies with `COMMAND_DEVICE_STATE` snapshots describing applied state.
  * Firmware coalesces state changes through a dirty flag; `deviceStateLoop()` is the single state-report sender.
  * Android retries an unacknowledged desired-state snapshot by resending the same sequence.
  * Legacy one-shot control commands were removed.
 
* **Historical 2.1 changelog:**
  * Initial version with core command set.
  * Parameter length field upgraded from 1 byte to 2 bytes (`uint16_t`).
  * Added `COMMAND_WINDOW_UPDATE` **(ESP32 → Android)**.
  * Version/status payload includes `windowSize`, **`rfModuleType`**, and **`features`**.
  * Audio streams are now OPUS encoded.
  * Window-based flow control implemented for all incoming commands, inspired by HTTP/2.

## Packet Structure

Serial messages use KV4P KISS transport.

### AX.25 KISS DATA Frames

AX.25 packets are sent as standard KISS DATA frames:

```
FEND 0x00 <escaped AX.25 frame bytes> FEND
```

The AX.25 payload does not include a kv4p command byte, decoder ID, or top-level length field.

### KV4P Vendor Frames

Non-AX.25 kv4p commands are sent as KISS SETHARDWARE vendor frames:

```
FEND 0x06 "KV4P" 0x01 <kv4pCommand> <escaped command payload bytes> FEND
```

The payload prefix is ASCII `"KV4P"`, followed by `uint8 protocolVersion = 1`, the existing kv4p command byte, and the existing command payload bytes.

### Escaping

Inside KISS frame payloads, bytes are escaped as follows:

| Byte | Encoded as |
| ---- | ---------- |
| `0xC0` | `0xDB 0xDC` |
| `0xDB` | `0xDB 0xDD` |

All other bytes are written unchanged. The old `0xDEADBEEF` delimiter and top-level `uint16` length field are no longer present on the wire.

## Incoming KISS Frame Types (Android → ESP32)

| KISS Command | Name                   | Description                       |
| ------------ | ---------------------- | --------------------------------- |
| `0x00`       | KISS DATA frame        | Transmit AX.25 packet bytes       |
| `0x06`       | KISS SETHARDWARE frame | Carry a kv4p vendor command frame |

## Incoming KV4P Vendor Commands (Android → ESP32)

| Command Code | Name                    | Description                                                    |
| ------------ | ----------------------- | -------------------------------------------------------------- |
| `0x07`       | `COMMAND_HOST_TX_AUDIO` | Receive Tx OPUS audio data (payload required, flow-controlled) |
| `0x0D`       | `COMMAND_HOST_DESIRED_STATE` | Desired radio/control state snapshot                     |

## Outgoing KISS Frame Types (ESP32 → Android)

| KISS Command | Name                   | Description                         |
| ------------ | ---------------------- | ----------------------------------- |
| `0x00`       | KISS DATA frame        | Received AX.25 packet bytes         |
| `0x06`       | KISS SETHARDWARE frame | Carry a kv4p vendor command frame   |

## Outgoing KV4P Vendor Commands (ESP32 → Android)

| Command Code | Name                    | Description                                 |
| ------------ | ----------------------- | ------------------------------------------- |
| `0x01`       | `COMMAND_DEBUG_INFO`    | Sends debug info message                    |
| `0x02`       | `COMMAND_DEBUG_ERROR`   | Sends debug error message                   |
| `0x03`       | `COMMAND_DEBUG_WARN`    | Sends debug warning message                 |
| `0x04`       | `COMMAND_DEBUG_DEBUG`   | Sends debug debug-level message             |
| `0x05`       | `COMMAND_DEBUG_TRACE`   | Sends debug trace message                   |
| `0x06`       | `COMMAND_HELLO`         | Hello handshake message with version/status and initial device state |
| `0x07`       | `COMMAND_RX_AUDIO`      | Sends Rx OPUS audio data (payload required) |
| `0x09`       | `COMMAND_WINDOW_UPDATE` | Updates available receive window            |
| `0x0B`       | `COMMAND_DEVICE_STATE`  | Applied radio/control state snapshot         |

## Command Parameters

### `COMMAND_HELLO` Parameters

```c
struct version {
  uint16_t     ver;               // 2 bytes
  char         radioModuleStatus; // 1 byte
  uint32_t     windowSize;        // 4 bytes
  uint8_t      rfModuleType;      // 1 byte (enum)
  float        minRadioFreq;      // 4 bytes
  float        maxRadioFreq;      // 4 bytes
  uint8_t      features;          // 1 byte (bitmask)
} __attribute__((__packed__));
typedef struct version Version;

// features bitmask
#define FEATURE_HAS_HL      (1 << 0)
#define FEATURE_HAS_PHY_PTT (1 << 1)
#define FEATURE_HAS_ESP32_AFSK (1 << 2)

struct hello {
  Version     version;
  DeviceState deviceState;
} __attribute__((__packed__));
typedef struct hello Hello;
```

Firmware sends `COMMAND_HELLO(Hello)` after boot-time radio initialization. Android validates the version/status fields, adopts `minRadioFreq`/`maxRadioFreq` as the module's usable RX range, and seeds its initial radio-module state from the appended `DeviceState`.

### `COMMAND_HOST_DESIRED_STATE` Parameters

```c
struct host_desired_state {
  uint32_t sequence;
  int32_t  memoryId; // -1 means VFO/no memory
  uint16_t flags;
  uint8_t  bw;
  float    freq_tx;
  float    freq_rx;
  uint8_t  ctcss_tx;
  uint8_t  squelch;
  uint8_t  ctcss_rx;
} __attribute__((__packed__));
typedef struct host_desired_state HostDesiredState;

#define HOST_STATE_RADIO_CONFIG_VALID (1 << 0)
#define HOST_STATE_PTT_REQUESTED      (1 << 1)
#define HOST_STATE_RX_AUDIO_OPEN      (1 << 2)
#define HOST_STATE_HIGH_POWER         (1 << 3)
#define HOST_STATE_RSSI_ENABLED       (1 << 4)
#define HOST_STATE_FILTER_PRE         (1 << 5)
#define HOST_STATE_FILTER_HIGH        (1 << 6)
#define HOST_STATE_FILTER_LOW         (1 << 7)
```

Android sends the full desired-state snapshot whenever one field changes. Firmware applies changed radio/filter/control fields, derives its mode, and marks device state dirty so `deviceStateLoop()` can report the result with `COMMAND_DEVICE_STATE`.

Android treats `DeviceState.appliedSequence` as the acknowledgement for the latest desired-state snapshot. If received device state does not match the last sent desired snapshot, Android may retry the exact same `HostDesiredState` with the same `sequence`. Retries are bounded; they are not new logical state changes and must not increment `sequence`.

### `COMMAND_DEVICE_STATE` Parameters

```c
struct device_state {
  uint32_t appliedSequence;
  int32_t  memoryId; // -1 means VFO/no memory
  uint16_t flags;
  uint8_t  bw;
  float    freq_tx;
  float    freq_rx;
  uint8_t  ctcss_tx;
  uint8_t  squelch;
  uint8_t  ctcss_rx;
  char     radioModuleStatus;
  uint8_t  mode;
  uint8_t  lastError;
  uint8_t  latestRssi;
} __attribute__((__packed__));
typedef struct device_state DeviceState;

#define DEVICE_STATE_PHYS_PTT_DOWN (1 << 8)
#define DEVICE_STATE_TX_ACTIVE     (1 << 9)
#define DEVICE_STATE_SQUELCHED     (1 << 10)
```

Firmware sends `COMMAND_DEVICE_STATE` from `deviceStateLoop()`. State producers such as desired-state reconciliation, RSSI changes, squelch changes, and physical PTT changes set a dirty flag instead of writing serial frames directly. The next loop pass flushes dirty state, so change reports are effectively immediate but still async and coalesced through one sender. Firmware also sends `COMMAND_DEVICE_STATE` every 500 ms as a heartbeat/state refresh. RSSI/S-meter data is reported through `latestRssi`; there is no separate S-meter report command.

Scanning remains Android-owned because Android owns the memory list, memory groups, skip-during-scan flags, offsets, and UI selection. Firmware reports the current squelch state with `DEVICE_STATE_SQUELCHED`; Android uses that received state to decide when to advance to the next memory.

Firmware persists stable radio settings in NVS and restores them on startup before sending `COMMAND_HELLO`: memory id, bandwidth, TX/RX frequencies, TX/RX tones, squelch level, high-power preference, RSSI preference, and filter flags. If persisted TX/RX frequencies are outside the configured RF module's range, firmware clamps them into range and clears `memoryId` to `-1` before applying radio config. Transient state is not restored: sequence, PTT requested, and RX audio open always start clear.

### `COMMAND_WINDOW_UPDATE` Parameters **(ESP32 → Android)**

```c
struct window_update {
  uint32_t windowSize; // 4 bytes
} __attribute__((__packed__));
typedef struct window_update WindowUpdate;
```

## Flow Control

A window-based flow control mechanism, inspired by HTTP/2, is used to regulate the amount of data sent from Android to the ESP32:

1. **Window Size Declaration**:

   * The ESP32 sends a "desired" initial window size in the `COMMAND_HELLO` payload.
   * This typically matches the size of its internal USB receive buffer.

2. **Window Consumption**:

   * Each incoming command from Android reduces the remaining window size by the size of the entire packet.
   * All commands, not just `COMMAND_HOST_TX_AUDIO`, are subject to this flow control.

3. **Blocking on Exhaustion**:

   * If the next packet does not fit in the remaining window, Android must wait until space is available.
   * Effectively, this blocks transmission when `windowSize < packet size`.

4. **Window Replenishment**:

   * Once the ESP32 finishes processing a packet, it sends a `COMMAND_WINDOW_UPDATE` message.
   * This increases the window size, allowing Android to resume transmission.

5. **Optional Implementation**:

   * Implementing flow control is optional.
   * A compliant implementation may ignore windowSize and COMMAND_WINDOW_UPDATE messages entirely.
   * This is primarily useful when dealing with fast data sources like APRS modems that can generate large amounts of audio data rapidly.
   * When audio is sourced from the ADC, it is inherently flow-controlled by the sampling hardware, making software flow control less necessary.

## Command Handling Strategy

* Android sends complete `COMMAND_HOST_DESIRED_STATE` snapshots instead of separate one-shot control commands.
* Firmware reports the applied runtime state through `COMMAND_DEVICE_STATE` snapshots.
* Firmware state producers mark device state dirty; only `deviceStateLoop()` sends `COMMAND_DEVICE_STATE`.
* Android compares desired and device state. If the device does not acknowledge the latest desired snapshot, Android may resend the same snapshot/sequence a small number of times.
* KISS DATA frames carry AX.25 packets directly, outside the kv4p vendor command namespace.
* Android-to-firmware vendor commands are subject to **window-based flow control**.

## Byte Order and Bit Significance

* All multi-byte fields are encoded in little-endian format.
* Bitmask values follow LSB-first ordering (bit 0 is the least significant).

## Example Packets

### Desired-state update

```
[ 0xC0, 0x06, 'K', 'V', '4', 'P', 0x01, 0x0D, ...state bytes..., 0xC0 ]
```

* `0xC0`: KISS FEND
* `0x06`: KISS SETHARDWARE
* `"KV4P" 0x01`: KV4P vendor prefix and protocol version
* `0x0D`: `COMMAND_HOST_DESIRED_STATE`

### Example Debug Message

```
[ 0xC0, 0x06, 'K', 'V', '4', 'P', 0x01, 0x01, 'E', 'r', 'r', 'o', 'r', 0xC0 ]
```

* `0x06`: KISS SETHARDWARE
* `"KV4P" 0x01`: KV4P vendor prefix and protocol version
* `0x01`: `COMMAND_DEBUG_INFO`
* `'Error'`: Debug message content
