/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <WebSocketsClient.h>
#include <time.h>
#include "config.h"
#include "frames.h"

// Ingest uplink to the bielefeld-live backend. The configured base URL's
// scheme picks the transport (the paths are fixed by the ingest contract):
//   http(s)://host[:port][/prefix]  -> POST /api/v1/ingest, batched JSON
//   ws(s)://host[:port][/prefix]    -> persistent WebSocket to /ws/ingest
// Auth: Bearer station key. TLS is encrypted but unverified (setInsecure) —
// there is no room for a CA bundle and the endpoint is user-configurable.

enum UplinkMode : uint8_t { UP_OFF = 0, UP_HTTP, UP_HTTPS, UP_WS, UP_WSS };

static const char *UP_MODE_NAMES[] = {"off", "http", "http", "ws", "ws"};

static volatile uint32_t upCfgGen = 1;  // bumped by uplinkReconfigure()
static UplinkMode upMode = UP_OFF;      // fields below owned by the uplink task
static String upHost, upBasePath, upToken;
static uint16_t upPort = 0;
static WebSocketsClient *upWs = nullptr;
static volatile bool upWsConnected = false;
char upLastError[64] = "";
static uint32_t upLastPostMs = 0;

const char *uplinkModeName() { return UP_MODE_NAMES[upMode]; }
bool uplinkConnected() { return upMode == UP_HTTP || upMode == UP_HTTPS ? true : upWsConnected; }

// Called after cfg.uplinkUrl / cfg.uplinkToken change; the task re-parses.
void uplinkReconfigure() {
  upCfgGen = upCfgGen + 1;
}

static bool upParseUrl(const String &url, UplinkMode &mode, String &host, uint16_t &port, String &basePath) {
  String rest;
  if (url.startsWith("https://")) { mode = UP_HTTPS; port = 443; rest = url.substring(8); }
  else if (url.startsWith("http://")) { mode = UP_HTTP; port = 80; rest = url.substring(7); }
  else if (url.startsWith("wss://")) { mode = UP_WSS; port = 443; rest = url.substring(6); }
  else if (url.startsWith("ws://")) { mode = UP_WS; port = 80; rest = url.substring(5); }
  else return false;
  int slash = rest.indexOf('/');
  String hostPort = slash < 0 ? rest : rest.substring(0, slash);
  basePath = slash < 0 ? "" : rest.substring(slash);
  while (basePath.endsWith("/")) basePath.remove(basePath.length() - 1);
  int colon = hostPort.indexOf(':');
  if (colon >= 0) {
    host = hostPort.substring(0, colon);
    port = (uint16_t)hostPort.substring(colon + 1).toInt();
  } else {
    host = hostPort;
  }
  return host.length() > 0;
}

// One burst object per the ingest contract. wsWrap adds the WS envelope.
static int upBuildBurstJson(char *buf, size_t sz, const FrameRecord &r, bool wsWrap) {
  static const char hexd[] = "0123456789abcdef";
  char hexs[FRAME_RAW_MAX * 2 + 1];
  int len = r.rawLen > FRAME_RAW_MAX ? FRAME_RAW_MAX : r.rawLen;
  for (int i = 0; i < len; i++) {
    hexs[i * 2] = hexd[r.raw[i] >> 4];
    hexs[i * 2 + 1] = hexd[r.raw[i] & 0xf];
  }
  hexs[len * 2] = '\0';
  char ts[48] = "";
  if (r.tsUnix > 0) {
    struct tm tmv;
    gmtime_r(&r.tsUnix, &tmv);
    char iso[24];
    strftime(iso, sizeof(iso), "%Y-%m-%dT%H:%M:%SZ", &tmv);
    snprintf(ts, sizeof(ts), ",\"received_at\":\"%s\"", iso);
  }
  const char *proto = r.proto == PROTO_FFSK_VDV ? "ffsk" : "g2";
  if (wsWrap) {
    return snprintf(buf, sz, "{\"type\":\"burst\",\"raw_hex\":\"%s\",\"protocol\":\"%s\"%s}", hexs, proto, ts);
  }
  return snprintf(buf, sz, "{\"raw_hex\":\"%s\",\"protocol\":\"%s\"%s}", hexs, proto, ts);
}

static void upWsEvent(WStype_t type, uint8_t *payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED:
      upWsConnected = true;
      Serial.println("[uplink] ws connected");
      break;
    case WStype_DISCONNECTED:
      upWsConnected = false;
      break;
    case WStype_TEXT: {
      // {"type":"ack","accepted":N} / {"type":"error","reason":"..."}
      const char *txt = (const char *)payload;
      if (strstr(txt, "\"ack\"")) {
        const char *acc = strstr(txt, "\"accepted\":");
        if (acc) stAccepted = stAccepted + (uint32_t)atoi(acc + 11);
      } else if (strstr(txt, "\"error\"")) {
        strlcpy(upLastError, txt, sizeof(upLastError));
      }
      break;
    }
    case WStype_ERROR:
      strlcpy(upLastError, "ws error", sizeof(upLastError));
      break;
    default:
      break;
  }
}

static void upTeardownWs() {
  if (upWs) {
    upWs->disconnect();
    delete upWs;
    upWs = nullptr;
  }
  upWsConnected = false;
}

// (Re)apply cfg.uplinkUrl/cfg.uplinkToken. Runs in the uplink task.
static void upApplyConfig() {
  upTeardownWs();
  upToken = cfg.uplinkToken;
  if (!cfg.uplinkUrl.length() || !upParseUrl(cfg.uplinkUrl, upMode, upHost, upPort, upBasePath)) {
    upMode = UP_OFF;
    if (cfg.uplinkUrl.length()) strlcpy(upLastError, "bad uplink url", sizeof(upLastError));
    return;
  }
  upLastError[0] = '\0';
  if (upMode == UP_WS || upMode == UP_WSS) {
    upWs = new WebSocketsClient();
    String path = upBasePath + "/ws/ingest";
    if (upToken.length()) {
      String hdr = "Authorization: Bearer " + upToken;
      upWs->setExtraHeaders(hdr.c_str());
    }
    if (upMode == UP_WSS) {
      upWs->beginSSL(upHost.c_str(), upPort, path.c_str());  // no CA -> unverified TLS
    } else {
      upWs->begin(upHost.c_str(), upPort, path.c_str());
    }
    upWs->onEvent(upWsEvent);
    upWs->setReconnectInterval(5000);
    upWs->enableHeartbeat(15000, 3000, 2);
  }
  Serial.printf("[uplink] mode=%s host=%s port=%u\n", uplinkModeName(), upHost.c_str(), upPort);
}

// POST a drained batch to <base>/api/v1/ingest. Records are dropped on
// failure — the bounded queue is the only retry buffer (v1 behavior).
static void upPostBatch(FrameRecord *batch, int n) {
  static WiFiClientSecure *tls = nullptr;
  String body = "{\"bursts\":[";
  char obj[192];
  for (int i = 0; i < n; i++) {
    upBuildBurstJson(obj, sizeof(obj), batch[i], false);
    if (i) body += ',';
    body += obj;
  }
  body += "]}";

  HTTPClient http;
  http.setConnectTimeout(4000);
  http.setTimeout(6000);
  String url = String(upMode == UP_HTTPS ? "https://" : "http://") + upHost + ":" + upPort + upBasePath + "/api/v1/ingest";
  bool ok;
  if (upMode == UP_HTTPS) {
    if (!tls) {
      tls = new WiFiClientSecure();
      tls->setInsecure();
    }
    ok = http.begin(*tls, url);
  } else {
    ok = http.begin(url);
  }
  if (!ok) {
    stDropped = stDropped + n;
    strlcpy(upLastError, "http begin failed", sizeof(upLastError));
    return;
  }
  http.addHeader("Content-Type", "application/json");
  if (upToken.length()) http.addHeader("Authorization", "Bearer " + upToken);
  int code = http.POST(body);
  if (code >= 200 && code < 300) {
    stSent = stSent + n;
    String resp = http.getString();  // {"accepted":N,"rejected":M}
    int idx = resp.indexOf("\"accepted\":");
    if (idx >= 0) stAccepted = stAccepted + (uint32_t)resp.substring(idx + 11).toInt();
    upLastError[0] = '\0';
  } else {
    stDropped = stDropped + n;
    snprintf(upLastError, sizeof(upLastError), "http %d", code);
  }
  http.end();
}

static void uplinkTask(void *) {
  uint32_t appliedGen = 0;
  static FrameRecord batch[FRAME_QUEUE_DEPTH];
  for (;;) {
    if (appliedGen != upCfgGen) {
      appliedGen = upCfgGen;
      upApplyConfig();
    }
    if (upMode == UP_OFF || WiFi.status() != WL_CONNECTED) {
      vTaskDelay(pdMS_TO_TICKS(250));
      continue;
    }

    if (upMode == UP_WS || upMode == UP_WSS) {
      upWs->loop();
      if (upWsConnected) {
        FrameRecord r;
        char msg[224];
        while (xQueueReceive(frameQueue, &r, 0) == pdTRUE) {
          upBuildBurstJson(msg, sizeof(msg), r, true);
          if (upWs->sendTXT(msg)) {
            stSent = stSent + 1;
          } else {
            stDropped = stDropped + 1;
          }
          upWs->loop();
        }
      }
      vTaskDelay(pdMS_TO_TICKS(20));
      continue;
    }

    // HTTP(S): batch — flush when several frames are waiting or the oldest
    // has waited ~1 s.
    UBaseType_t waiting = uxQueueMessagesWaiting(frameQueue);
    if (waiting >= 8 || (waiting > 0 && millis() - upLastPostMs > 1000)) {
      int n = 0;
      while (n < FRAME_QUEUE_DEPTH && xQueueReceive(frameQueue, &batch[n], 0) == pdTRUE) n++;
      if (n > 0) {
        upPostBatch(batch, n);
        upLastPostMs = millis();
      }
    }
    vTaskDelay(pdMS_TO_TICKS(50));
  }
}

void uplinkStart() {
  framesInit();
  xTaskCreatePinnedToCore(uplinkTask, "uplink", 12288, nullptr, 1, nullptr, 0);
}
