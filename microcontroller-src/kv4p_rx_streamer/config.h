/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include <Preferences.h>
#include "hardware.h"

#define FIRMWARE_VERSION "0.1.0"

// Stream format: lossless PCM16LE mono. The radio's audio must reach the
// server unshaped (data channels carry VDV-FFSK / NEMO modem signals).
#define CAPTURE_SAMPLE_RATE 48000
#define STREAM_SAMPLE_RATE  16000
#define DECIMATION_RATIO    3
#define FRAME_SAMPLES_48K   720  // 15 ms per frame, divisible by DECIMATION_RATIO
#define FRAME_SAMPLES_16K   (FRAME_SAMPLES_48K / DECIMATION_RATIO)

#define MAX_CHANNELS 32
#define CHANNEL_NAME_LEN 16

// bandwidth: 0 = 12.5 kHz, 1 = 25 kHz (the SA818 only offers these two;
// a 20 kHz channel raster uses the 25 kHz setting)
// chMode: metadata only — the radio is configured identically either way
// (all SA818 filters bypassed, fully flat audio).
enum ChannelMode : uint8_t { CH_MODE_VOICE = 0, CH_MODE_DATA = 1 };

// dataProto: which telegram decoder runs while this data channel is active.
enum DataProto : uint8_t { PROTO_NONE = 0, PROTO_FFSK_VDV = 1, PROTO_NEMO_LIO = 2 };

struct [[gnu::packed]] Channel {
  uint8_t number;
  char name[CHANNEL_NAME_LEN + 1];
  uint32_t freqHz;
  uint8_t bandwidth;
  uint8_t chMode;
  uint8_t dataProto;
  uint8_t used;
};

struct [[gnu::packed]] ChannelTable {
  uint16_t magic;    // 0x4B56 'KV'
  uint8_t version;   // bump on layout change
  uint8_t reserved;
  Channel ch[MAX_CHANNELS];
};

// v1 layout (before dataProto), kept only so stored tables migrate instead
// of being wiped on upgrade.
struct [[gnu::packed]] ChannelV1 {
  uint8_t number;
  char name[CHANNEL_NAME_LEN + 1];
  uint32_t freqHz;
  uint8_t bandwidth;
  uint8_t chMode;
  uint8_t used;
};

struct [[gnu::packed]] ChannelTableV1 {
  uint16_t magic;
  uint8_t version;
  uint8_t reserved;
  ChannelV1 ch[MAX_CHANNELS];
};

#define CHANNEL_TABLE_MAGIC   0x4B56
#define CHANNEL_TABLE_VERSION 2

static_assert(sizeof(ChannelTableV1) == 4 + 25 * MAX_CHANNELS, "v1 blob layout drifted");
static_assert(sizeof(ChannelTable) == 4 + 26 * MAX_CHANNELS, "v2 blob layout drifted");
static_assert(sizeof(ChannelTable) != sizeof(ChannelTableV1), "migration keys on blob size");

struct StreamerConfig {
  String ssid;
  String pass;
  String adminPass;      // if non-empty, HTTP basic auth (user "admin") guards mutating endpoints
  uint16_t streamPort;
  uint8_t volume;        // SA818 volume 1-8 (drives the level into our ADC)
  uint8_t squelch;       // SA818 squelch 0-8 (0 = always open)
  int8_t activeChannel;  // index into channel table, -1 = manual VFO
  uint32_t vfoFreqHz;
  uint8_t vfoBandwidth;
  bool muteWhenClosed;   // zero samples while squelch is closed
  uint8_t vfoDataProto;  // decoder while in VFO mode (DataProto)
  // Node identity, shown in the web UI. The backend places nodes on the map
  // from its own station roster (keyed by the API key) — these are local
  // reference only and are not part of the ingest payload.
  String nodeName;
  float nodeLat;
  float nodeLon;
  // Ingest endpoint base URL. Scheme picks the transport: http(s):// posts
  // batches to /api/v1/ingest, ws(s):// keeps a WebSocket to /ws/ingest.
  // Empty = uplink disabled. uplinkToken = the station API key.
  String uplinkUrl;
  String uplinkToken;
};

StreamerConfig cfg;
ChannelTable channels;
Preferences cfgPrefs;

void resetChannelTable() {
  memset(&channels, 0, sizeof(channels));
  channels.magic = CHANNEL_TABLE_MAGIC;
  channels.version = CHANNEL_TABLE_VERSION;
}

static void addDefaultChannel(int idx, uint32_t freqHz, const char *prefix) {
  Channel &c = channels.ch[idx];
  c.number = idx + 1;
  snprintf(c.name, sizeof(c.name), "%s %d", prefix, idx + 1);
  c.freqHz = freqHz;
  c.bandwidth = 0;  // both band plans use 12.5 kHz
  c.chMode = CH_MODE_VOICE;
  c.dataProto = PROTO_NONE;
  c.used = 1;
}

// First-boot channel table: the license-free band plan matching the module.
// VHF: German Freenet, 6 channels around 149 MHz. UHF: PMR446, 16 channels.
void populateDefaultChannels() {
  if (hw.rfModuleType == RF_SA818_UHF) {
    for (int i = 0; i < 16; i++) {
      addDefaultChannel(i, 446006250 + (uint32_t)i * 12500, "PMR");
    }
  } else {
    static const uint32_t freenetHz[6] = {
      149025000, 149037500, 149050000, 149087500, 149100000, 149112500,
    };
    for (int i = 0; i < 6; i++) {
      addDefaultChannel(i, freenetHz[i], "Freenet");
    }
  }
}

void saveChannelTable();

void loadConfig() {
  cfgPrefs.begin("rxstream", true);
  cfg.ssid = cfgPrefs.getString("ssid", "");
  cfg.pass = cfgPrefs.getString("pass", "");
  cfg.adminPass = cfgPrefs.getString("webpass", "");
  cfg.streamPort = cfgPrefs.getUShort("port", 8000);
  cfg.volume = cfgPrefs.getUChar("volume", hw.volume);
  cfg.squelch = cfgPrefs.getUChar("squelch", 2);
  cfg.activeChannel = cfgPrefs.getChar("active", -1);
  cfg.vfoFreqHz = cfgPrefs.getUInt("vfoFreq", 146520000);
  cfg.vfoBandwidth = cfgPrefs.getUChar("vfoBw", 0);
  cfg.muteWhenClosed = cfgPrefs.getBool("muteSq", true);
  cfg.vfoDataProto = cfgPrefs.getUChar("vfoProto", PROTO_NONE);
  cfg.nodeName = cfgPrefs.getString("nodeName", "");
  cfg.nodeLat = cfgPrefs.getFloat("nodeLat", 0.0f);
  cfg.nodeLon = cfgPrefs.getFloat("nodeLon", 0.0f);
  cfg.uplinkUrl = cfgPrefs.getString("upUrl", "");
  cfg.uplinkToken = cfgPrefs.getString("upTok", "");

  // Channel table: load the current layout, migrate a v1 blob (keyed on the
  // exact blob size), or fall back to the defaults. Never wipe silently on
  // a mere version bump.
  bool migrated = false;
  size_t blobLen = cfgPrefs.getBytesLength("channels");
  if (blobLen == sizeof(ChannelTable)) {
    cfgPrefs.getBytes("channels", &channels, sizeof(channels));
    if (channels.magic != CHANNEL_TABLE_MAGIC || channels.version != CHANNEL_TABLE_VERSION) {
      resetChannelTable();
      populateDefaultChannels();
    }
  } else if (blobLen == sizeof(ChannelTableV1)) {
    ChannelTableV1 old;
    cfgPrefs.getBytes("channels", &old, sizeof(old));
    resetChannelTable();
    if (old.magic == CHANNEL_TABLE_MAGIC && old.version == 1) {
      for (int i = 0; i < MAX_CHANNELS; i++) {
        Channel &c = channels.ch[i];
        c.number = old.ch[i].number;
        memcpy(c.name, old.ch[i].name, sizeof(c.name));
        c.freqHz = old.ch[i].freqHz;
        c.bandwidth = old.ch[i].bandwidth;
        c.chMode = old.ch[i].chMode;
        c.dataProto = PROTO_NONE;
        c.used = old.ch[i].used;
      }
      migrated = true;
    } else {
      populateDefaultChannels();
    }
  } else {
    resetChannelTable();
    populateDefaultChannels();
  }
  cfgPrefs.end();
  if (migrated) {
    saveChannelTable();
    Serial.println("[config] channel table migrated v1 -> v2");
  }
}

void saveConfig() {
  cfgPrefs.begin("rxstream", false);
  cfgPrefs.putString("ssid", cfg.ssid);
  cfgPrefs.putString("pass", cfg.pass);
  cfgPrefs.putString("webpass", cfg.adminPass);
  cfgPrefs.putUShort("port", cfg.streamPort);
  cfgPrefs.putUChar("volume", cfg.volume);
  cfgPrefs.putUChar("squelch", cfg.squelch);
  cfgPrefs.putChar("active", cfg.activeChannel);
  cfgPrefs.putUInt("vfoFreq", cfg.vfoFreqHz);
  cfgPrefs.putUChar("vfoBw", cfg.vfoBandwidth);
  cfgPrefs.putBool("muteSq", cfg.muteWhenClosed);
  cfgPrefs.putUChar("vfoProto", cfg.vfoDataProto);
  cfgPrefs.putString("nodeName", cfg.nodeName);
  cfgPrefs.putFloat("nodeLat", cfg.nodeLat);
  cfgPrefs.putFloat("nodeLon", cfg.nodeLon);
  cfgPrefs.putString("upUrl", cfg.uplinkUrl);
  cfgPrefs.putString("upTok", cfg.uplinkToken);
  cfgPrefs.end();
}

// Wipe all user settings (WiFi, admin password, channels, tuning) and reboot.
// Leaves the "hwconfig" namespace alone — that's the board description.
void factoryReset() {
  cfgPrefs.begin("rxstream", false);
  cfgPrefs.clear();
  cfgPrefs.end();
  ESP.restart();
}

void saveChannelTable() {
  cfgPrefs.begin("rxstream", false);
  cfgPrefs.putBytes("channels", &channels, sizeof(channels));
  cfgPrefs.end();
}

// The channel (or VFO) the radio should currently be tuned to.
void activeTuning(uint32_t &freqHz, uint8_t &bandwidth) {
  if (cfg.activeChannel >= 0 && cfg.activeChannel < MAX_CHANNELS && channels.ch[cfg.activeChannel].used) {
    freqHz = channels.ch[cfg.activeChannel].freqHz;
    bandwidth = channels.ch[cfg.activeChannel].bandwidth;
  } else {
    freqHz = cfg.vfoFreqHz;
    bandwidth = cfg.vfoBandwidth;
  }
}

// Which telegram decoder should run right now: the active data channel's
// protocol, or the VFO setting when no channel is selected.
uint8_t activeDataProto() {
  if (cfg.activeChannel >= 0 && cfg.activeChannel < MAX_CHANNELS && channels.ch[cfg.activeChannel].used) {
    const Channel &c = channels.ch[cfg.activeChannel];
    return c.chMode == CH_MODE_DATA ? c.dataProto : (uint8_t)PROTO_NONE;
  }
  return cfg.vfoDataProto;
}

bool freqInModuleRange(uint32_t freqHz) {
  float mhz = freqHz / 1e6f;
  return mhz >= moduleMinFreqMHz() && mhz <= moduleMaxFreqMHz();
}
