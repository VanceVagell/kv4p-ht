# kv4p RX streamer

An **RX-only network receiver** firmware for the [kv4p-ht](https://kv4p.com) ham radio hardware (ESP32-WROOM-32 + SA818). Instead of pairing with the Android app, the board runs standalone: it serves its received audio as a **live HTTP stream** on your network and is configured entirely through a **built-in web interface**.

This is a fork of [VanceVagell/kv4p-ht](https://github.com/VanceVagell/kv4p-ht). The Android app, KISS protocol, Bluetooth, and TX support are intentionally removed — the radio is permanently in receive mode. The Android app, 3D-printed case, and website sources have been dropped from this branch; the PCB sources and the stock firmware are kept for hardware reference and upstream comparison.

## Features

- **Live audio over HTTP** — `http://<device>:8000/stream.wav`, an endless WAV (PCM16 mono, 16 kHz, lossless). Open it directly in VLC (*Media → Open Network Stream*), `ffplay`, `curl`, or any HTTP client. Up to 3 simultaneous listeners.
- **Web UI** on port 80 (`http://kv4p-rx.local/` via mDNS):
  - **Channel table** — up to 32 channels with number, name, frequency, bandwidth (12.5 vs 20/25 kHz), and voice/data type
  - Active-channel selector or manual VFO tuning
  - Volume, squelch level, squelch muting
  - WiFi and network settings
  - **Browser-based OTA firmware updates** — no USB access needed once mounted
- **Flat audio path** — the SA818's pre/de-emphasis and voice-band filters are bypassed on every channel, so data channels (FFSK and similar modem signals) reach the server unshaped. A channel's voice/data type is metadata for downstream consumers; apply de-emphasis in the player if voice sounds trebly.
- **Default channel plan** — first boot seeds German **Freenet** (VHF module) or **PMR446** (UHF module) channels.
- **Zero-touch provisioning** — with no WiFi configured (or if joining fails for 30 s), the device raises an open setup AP `kv4p-rx-setup`; browse to `192.168.4.1` and enter your network. The device keeps retrying your WiFi in the background.
- Works on all kv4p-ht PCB revisions (v1.x strap detection, v2.0c/d, and boards with an NVS `hwconfig` blob).

## Quick start

```sh
git clone https://github.com/ttimpe/kv4p-ht -b rx-streamer
cd kv4p-ht/microcontroller-src/kv4p_rx_streamer
pio run -t upload        # requires PlatformIO
pio device monitor       # optional: plain-text logs at 115200 baud
```

1. Join the `kv4p-rx-setup` WiFi network and open `http://192.168.4.1/`.
2. Enter your WiFi credentials under **Network** — the device reboots and joins.
3. Open `http://kv4p-rx.local/`, pick a channel, and open the stream URL shown there in VLC.

## HTTP API

| Endpoint | Description |
|---|---|
| `GET :8000/stream.wav` | Live audio stream (endless WAV, PCM16 mono 16 kHz) |
| `GET /api/status` | JSON: tuned frequency, active channel, squelch state, WiFi RSSI, stream clients, heap, firmware version |
| `GET /api/channels` / `PUT /api/channels` | Read / replace the channel table |
| `GET /api/config` / `POST /api/config` | Read / change tuning, audio, and network settings |
| `POST /update` | OTA firmware upload (`.bin` from `pio run`) |

Setting an admin password in the web UI protects all mutating endpoints with HTTP basic auth (user `admin`). Recommended before mounting the device somewhere hard to reach — and note the update endpoint is otherwise open to anyone on your LAN.

## Repository layout

- `microcontroller-src/kv4p_rx_streamer/` — **this firmware**
- `microcontroller-src/kv4p_ht_esp32_wroom_32/` — stock upstream firmware (unmodified, for reference)
- `pcb/` — kv4p-ht hardware design files (upstream)

## License

GPL v3, like the upstream project it derives from. Hardware, PCB design, and the original firmware are by [Vance Vagell (KV4P)](https://kv4p.com) and contributors.
