/*
kv4p RX streamer — RX-only audio streaming firmware for kv4p-ht hardware.
Derived from KV4P-HT (see http://kv4p.com), Copyright (C) 2026 Vance Vagell.
Licensed under the GNU General Public License v3 or later.
*/
#pragma once

#include <Arduino.h>
#include <WiFi.h>
#include <ESPmDNS.h>
#include "config.h"

// Station mode with saved credentials; if none are saved (or the join hasn't
// succeeded after 30 s) an open setup AP comes up alongside so the device is
// always reachable for provisioning — it may be mounted out of physical reach.

#define SETUP_AP_SSID "kv4p-rx-setup"
#define STA_FALLBACK_MS 30000

static bool apStarted = false;
static bool mdnsStarted = false;
static bool ntpStarted = false;
static unsigned long wifiStartMs = 0;

// SNTP kicks off on the first STA connect; until it converges, uploaded
// frames omit received_at (the server stamps them on receipt instead).
bool timeSynced() {
  return time(nullptr) > 1700000000;  // any post-2023 date = SNTP has run
}

static void startSetupAP() {
  WiFi.mode(cfg.ssid.length() ? WIFI_AP_STA : WIFI_AP);
  WiFi.softAP(SETUP_AP_SSID);
  apStarted = true;
  Serial.printf("[wifi] setup AP '%s' up at %s\n", SETUP_AP_SSID, WiFi.softAPIP().toString().c_str());
}

void wifiSetup() {
  WiFi.persistent(false);  // we keep credentials in our own NVS namespace
  WiFi.setSleep(false);    // no modem-sleep latency spikes in the audio stream
  wifiStartMs = millis();
  if (cfg.ssid.length()) {
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.begin(cfg.ssid.c_str(), cfg.pass.c_str());
    Serial.printf("[wifi] joining '%s'...\n", cfg.ssid.c_str());
  } else {
    startSetupAP();
  }
}

// Called from loop().
void wifiLoop() {
  if (!apStarted && cfg.ssid.length() && WiFi.status() != WL_CONNECTED && millis() - wifiStartMs > STA_FALLBACK_MS) {
    Serial.println("[wifi] STA join timed out, raising setup AP (STA keeps retrying)");
    startSetupAP();
  }
  if (!mdnsStarted && WiFi.status() == WL_CONNECTED) {
    mdnsStarted = MDNS.begin("kv4p-rx");
    if (mdnsStarted) {
      MDNS.addService("http", "tcp", 80);
      Serial.printf("[wifi] connected, ip=%s (kv4p-rx.local)\n", WiFi.localIP().toString().c_str());
    }
  }
  if (!ntpStarted && WiFi.status() == WL_CONNECTED) {
    configTime(0, 0, "pool.ntp.org");
    ntpStarted = true;
  }
}
