/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include <WiFi.h>
#include "config.h"

// Live HTTP audio streaming (pull model): clients GET /stream.wav and receive
// an endless RIFF/WAV (PCM16LE mono 16 kHz). Playable directly in VLC/ffplay;
// the decoder server consumes the same URL. Runs on its own task (core 0),
// NOT watchdog-registered, because socket writes may block.

#define RING_SIZE 65536  // power of two; ~2 s of 32 kB/s PCM
#define MAX_STREAM_CLIENTS 3
#define STREAM_CHUNK 1400

static uint8_t ring[RING_SIZE];
static volatile uint32_t ringWritePos = 0;  // monotonic; aligned 32-bit store is atomic

volatile uint32_t streamClientCount = 0;
volatile uint32_t streamOverruns = 0;   // client resyncs after falling behind
volatile uint32_t streamBytesOut = 0;
volatile bool otaInProgress = false;

// Single producer: the audio loop on core 1.
void ringWrite(const uint8_t *data, size_t len) {
  uint32_t pos = ringWritePos;
  size_t idx = pos & (RING_SIZE - 1);
  size_t first = min(len, (size_t)(RING_SIZE - idx));
  memcpy(ring + idx, data, first);
  if (len > first) {
    memcpy(ring, data + first, len - first);
  }
  ringWritePos = pos + len;
}

// Seqlock-style read: returns false if the writer lapped the region meanwhile.
static bool ringRead(uint32_t pos, uint8_t *dst, size_t len) {
  size_t idx = pos & (RING_SIZE - 1);
  size_t first = min(len, (size_t)(RING_SIZE - idx));
  memcpy(dst, ring + idx, first);
  if (len > first) {
    memcpy(dst + first, ring, len - first);
  }
  return (ringWritePos - pos) <= RING_SIZE;
}

struct StreamClient {
  WiFiClient client;
  uint32_t readPos;
  bool active = false;
};

static StreamClient streamClients[MAX_STREAM_CLIENTS];
static WiFiServer *streamServer = nullptr;

static void writeWavStreamHeader(WiFiClient &c) {
  uint8_t h[44];
  memcpy(h, "RIFF", 4);
  uint32_t huge = 0xFFFFFFFF;
  memcpy(h + 4, &huge, 4);
  memcpy(h + 8, "WAVEfmt ", 8);
  uint32_t fmtLen = 16;
  memcpy(h + 16, &fmtLen, 4);
  uint16_t fmt = 1, channelsN = 1, align = 2, bits = 16;
  uint32_t rate = STREAM_SAMPLE_RATE, byteRate = STREAM_SAMPLE_RATE * 2;
  memcpy(h + 20, &fmt, 2);
  memcpy(h + 22, &channelsN, 2);
  memcpy(h + 24, &rate, 4);
  memcpy(h + 28, &byteRate, 4);
  memcpy(h + 32, &align, 2);
  memcpy(h + 34, &bits, 2);
  memcpy(h + 36, "data", 4);
  memcpy(h + 40, &huge, 4);
  c.write(h, sizeof(h));
}

static void acceptStreamClient() {
  WiFiClient nc = streamServer->available();
  if (!nc) {
    return;
  }
  nc.setTimeout(1);  // WiFiClient::setTimeout is in SECONDS; bounds blocking writes
  // Minimal HTTP request parse: first line only, drain the rest.
  String reqLine = nc.readStringUntil('\n');
  unsigned long start = millis();
  while (nc.connected() && millis() - start < 500) {
    String line = nc.readStringUntil('\n');
    if (line.length() <= 1) break;  // blank line = end of headers
  }
  if (!reqLine.startsWith("GET /stream")) {
    nc.print("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\nOnly /stream.wav lives here.\n");
    nc.stop();
    return;
  }
  for (int i = 0; i < MAX_STREAM_CLIENTS; i++) {
    if (!streamClients[i].active) {
      nc.setNoDelay(true);
      nc.print("HTTP/1.1 200 OK\r\n"
               "Content-Type: audio/wav\r\n"
               "Cache-Control: no-store\r\n"
               "Access-Control-Allow-Origin: *\r\n"
               "Connection: close\r\n\r\n");
      writeWavStreamHeader(nc);
      streamClients[i].client = nc;
      streamClients[i].readPos = ringWritePos;
      streamClients[i].active = true;
      streamClientCount++;
      return;
    }
  }
  nc.print("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\nToo many stream clients.\n");
  nc.stop();
}

static void dropStreamClient(StreamClient &sc) {
  sc.client.stop();
  sc.active = false;
  streamClientCount--;
}

static void streamerTask(void *) {
  static uint8_t chunk[STREAM_CHUNK];  // single task instance; keep it off the task stack
  for (;;) {
    if (otaInProgress) {
      for (int i = 0; i < MAX_STREAM_CLIENTS; i++) {
        if (streamClients[i].active) dropStreamClient(streamClients[i]);
      }
      vTaskDelay(pdMS_TO_TICKS(200));
      continue;
    }
    if (WiFi.status() == WL_CONNECTED || WiFi.softAPgetStationNum() > 0) {
      acceptStreamClient();
    }
    for (int i = 0; i < MAX_STREAM_CLIENTS; i++) {
      StreamClient &sc = streamClients[i];
      if (!sc.active) continue;
      if (!sc.client.connected()) {
        dropStreamClient(sc);
        continue;
      }
      uint32_t avail = ringWritePos - sc.readPos;
      if (avail > RING_SIZE - 4096) {
        // Laggard (TCP backpressure): skip ahead to near-live instead of
        // stalling the ring; the client hears a glitch, the counter records it.
        sc.readPos = ringWritePos - 8192;
        avail = 8192;
        streamOverruns++;
      }
      if (avail == 0) continue;
      size_t n = min((size_t)avail, (size_t)STREAM_CHUNK);
      if (!ringRead(sc.readPos, chunk, n)) {
        streamOverruns++;
        sc.readPos = ringWritePos - 8192;
        continue;
      }
      size_t written = sc.client.write(chunk, n);
      if (written == 0) {
        dropStreamClient(sc);
        continue;
      }
      sc.readPos += written;
      streamBytesOut += written;
    }
    vTaskDelay(pdMS_TO_TICKS(4));
  }
}

void streamerStart() {
  streamServer = new WiFiServer(cfg.streamPort);
  streamServer->begin();
  streamServer->setNoDelay(true);
  xTaskCreatePinnedToCore(streamerTask, "streamer", 6144, nullptr, 1, nullptr, 0);
}
