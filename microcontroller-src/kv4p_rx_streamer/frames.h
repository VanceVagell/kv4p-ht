/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include <time.h>
#include "config.h"

// CRC-checked telegram frames handed from the decoder task to the uplink
// task. Bounded queue, drop-oldest: while the uplink is down the queue is
// the (small) retry buffer.

#define FRAME_RAW_MAX 32     // ffsk telegrams <=21 B, g2 GPS beacons 19-20 B
#define FRAME_QUEUE_DEPTH 16

struct FrameRecord {
  uint8_t proto;             // DataProto -> wire tag "ffsk" / "g2"
  uint32_t uptimeMs;
  time_t tsUnix;             // 0 when wall-clock time is not synced yet
  uint8_t rawLen;
  uint8_t raw[FRAME_RAW_MAX];  // checkable frame incl. CRC/FCS bytes
  char label[24];            // local status display only
  uint8_t repairedBits;      // ffsk only
};

static QueueHandle_t frameQueue = nullptr;

volatile uint32_t stBursts = 0;    // decoder bursts examined
volatile uint32_t stFrames = 0;    // CRC-valid frames decoded
volatile uint32_t stDropped = 0;   // frames dropped (queue full / uplink error)
volatile uint32_t stSent = 0;      // frames handed to the transport
volatile uint32_t stAccepted = 0;  // frames the server acked as accepted
char stLastLabel[24] = "";
volatile uint32_t stLastMs = 0;

bool timeSynced();  // wifiMgr.h

void framesInit() {
  if (!frameQueue) {
    frameQueue = xQueueCreate(FRAME_QUEUE_DEPTH, sizeof(FrameRecord));
  }
}

// Called from the decoder task. Stamps time and drops the oldest queued
// frame when full so fresh telegrams win.
void frameEnqueue(FrameRecord &r) {
  r.uptimeMs = millis();
  r.tsUnix = timeSynced() ? time(nullptr) : 0;
  if (xQueueSend(frameQueue, &r, 0) != pdTRUE) {
    FrameRecord scratch;
    if (xQueueReceive(frameQueue, &scratch, 0) == pdTRUE) {
      stDropped = stDropped + 1;
    }
    xQueueSend(frameQueue, &r, 0);
  }
  stFrames = stFrames + 1;
  strlcpy(stLastLabel, r.label, sizeof(stLastLabel));
  stLastMs = millis();
}
