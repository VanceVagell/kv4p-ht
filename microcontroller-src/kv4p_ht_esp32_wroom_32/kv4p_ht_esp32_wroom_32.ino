/*
KV4P-HT (see http://kv4p.com)
Copyright (C) 2025 Vance Vagell

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

#include <Arduino.h>
#include <BluetoothSerial.h>
#include <DRA818.h>
#include <esp_task_wdt.h>
#include "globals.h"
#include "debug.h"
#include "led.h"
#include "protocol.h"
#include "rxAudio.h"
#include "txAudio.h"
#include "buttons.h"
#include "utils.h"
#include "board.h"

const uint16_t FIRMWARE_VER = 17;

const uint32_t RSSI_REPORT_INTERVAL_MS = 100;
const uint32_t DEVICE_STATE_REPORT_INTERVAL_MS = 500;
const uint16_t USB_BUFFER_SIZE = 1024*2;

DRA818 sa818_vhf(&Serial2, SA818_VHF);
DRA818 sa818_uhf(&Serial2, SA818_UHF);
DRA818 &sa818 = sa818_vhf;
BluetoothSerial SerialBT;
bool bluetoothProtocolConnected = false;
KissParser bluetoothParser(protocolBtSession, &handleCommands, &handleAx25Data);

// Were we able to communicate with the radio module during setup()?
const char RADIO_MODULE_NOT_FOUND = 'x';
const char RADIO_MODULE_FOUND     = 'f';
char radioModuleStatus            = RADIO_MODULE_NOT_FOUND;

boolean rssiOn = true; // true if RSSI is enabled
HostDesiredState desiredState = {
  .sequence = 0,
  .memoryId = -1,
  .flags = HOST_STATE_HIGH_POWER | HOST_STATE_RSSI_ENABLED,
  .bw = DRA818_25K,
  .freq_tx = 0.0f,
  .freq_rx = 0.0f,
  .ctcss_tx = 0,
  .squelch = 0,
  .ctcss_rx = 0,
};
HostDesiredState appliedState = {};
HostDesiredState persistedState = {};
bool radioConfigApplied = false;
bool filtersApplied = false;
uint8_t lastDeviceStateError = DEVICE_STATE_ERROR_NONE;
uint8_t latestRssi = 0;
bool deviceStateDirty = false;

Debounce squelchDebounce(100);

float moduleMinRadioFreq() {
  return hw.rfModuleType == RF_SA818_UHF ? 400.0f : 134.0f;
}

float moduleMaxRadioFreq() {
  return hw.rfModuleType == RF_SA818_UHF ? 480.0f : 174.0f;
}

float moduleDefaultRadioFreq() {
  return hw.rfModuleType == RF_SA818_UHF ? 435.0f : 144.0f;
}

float clampModuleRadioFreq(float freq) {
  return min(max(freq, moduleMinRadioFreq()), moduleMaxRadioFreq());
}

bool isModuleRadioFreq(float freq) {
  return freq >= moduleMinRadioFreq() && freq <= moduleMaxRadioFreq();
}

void loadPersistedRadioState() {
  prefs.begin("radio", true);
  uint16_t flags = desiredState.flags;
  flags &= ~(HOST_STATE_RADIO_CONFIG_VALID | HOST_STATE_HIGH_POWER | HOST_STATE_RSSI_ENABLED | HOST_STATE_FILTER_PRE | HOST_STATE_FILTER_HIGH | HOST_STATE_FILTER_LOW | HOST_STATE_TX_ALLOWED);
  flags |= HOST_STATE_RADIO_CONFIG_VALID;
  desiredState.bw = prefs.getUChar("bw", DRA818_25K);
  desiredState.freq_tx = prefs.getFloat("freq_tx", moduleDefaultRadioFreq());
  desiredState.freq_rx = prefs.getFloat("freq_rx", moduleDefaultRadioFreq());
  desiredState.ctcss_tx = prefs.getUChar("ctcss_tx", 0);
  desiredState.squelch = prefs.getUChar("squelch", 0);
  desiredState.ctcss_rx = prefs.getUChar("ctcss_rx", 0);
  desiredState.memoryId = prefs.getInt("memory_id", -1);
  if (!isModuleRadioFreq(desiredState.freq_tx) || !isModuleRadioFreq(desiredState.freq_rx)) {
    desiredState.freq_tx = clampModuleRadioFreq(desiredState.freq_tx);
    desiredState.freq_rx = clampModuleRadioFreq(desiredState.freq_rx);
    desiredState.memoryId = -1;
  }
  if (prefs.getBool("high", true)) {
    flags |= HOST_STATE_HIGH_POWER;
  }
  if (prefs.getBool("rssi", true)) {
    flags |= HOST_STATE_RSSI_ENABLED;
  }
  if (prefs.getBool("flt_pre", false)) {
    flags |= HOST_STATE_FILTER_PRE;
  }
  if (prefs.getBool("flt_high", false)) {
    flags |= HOST_STATE_FILTER_HIGH;
  }
  if (prefs.getBool("flt_low", false)) {
    flags |= HOST_STATE_FILTER_LOW;
  }
  if (prefs.getBool("tx_allowed", false)) {
    flags |= HOST_STATE_TX_ALLOWED;
  }
  desiredState.flags = flags;
  desiredState.sequence = 0;
  desiredState.flags &= ~(HOST_STATE_PTT_REQUESTED | HOST_STATE_SESSION_FLAG_MASK);
  appliedState.memoryId = desiredState.memoryId;
  persistedState = desiredState;
  prefs.end();
}

uint16_t persistedStateFlags(uint16_t flags) {
  return flags & (HOST_STATE_RADIO_CONFIG_VALID | HOST_STATE_HIGH_POWER | HOST_STATE_RSSI_ENABLED | HOST_STATE_FILTER_PRE | HOST_STATE_FILTER_HIGH | HOST_STATE_FILTER_LOW | HOST_STATE_TX_ALLOWED);
}

bool persistedRadioStateMatchesDesired() {
  return persistedState.bw == desiredState.bw
    && persistedState.freq_tx == desiredState.freq_tx
    && persistedState.freq_rx == desiredState.freq_rx
    && persistedState.ctcss_tx == desiredState.ctcss_tx
    && persistedState.squelch == desiredState.squelch
    && persistedState.ctcss_rx == desiredState.ctcss_rx
    && persistedState.memoryId == desiredState.memoryId
    && persistedStateFlags(persistedState.flags) == persistedStateFlags(desiredState.flags);
}

void savePersistedRadioStateIfChanged() {
  if (persistedRadioStateMatchesDesired()) {
    return;
  }
  prefs.begin("radio", false);
  prefs.putUChar("bw", desiredState.bw);
  prefs.putFloat("freq_tx", desiredState.freq_tx);
  prefs.putFloat("freq_rx", desiredState.freq_rx);
  prefs.putUChar("ctcss_tx", desiredState.ctcss_tx);
  prefs.putUChar("squelch", desiredState.squelch);
  prefs.putUChar("ctcss_rx", desiredState.ctcss_rx);
  prefs.putInt("memory_id", desiredState.memoryId);
  prefs.putBool("high", (desiredState.flags & HOST_STATE_HIGH_POWER) != 0);
  prefs.putBool("rssi", (desiredState.flags & HOST_STATE_RSSI_ENABLED) != 0);
  prefs.putBool("flt_pre", (desiredState.flags & HOST_STATE_FILTER_PRE) != 0);
  prefs.putBool("flt_high", (desiredState.flags & HOST_STATE_FILTER_HIGH) != 0);
  prefs.putBool("flt_low", (desiredState.flags & HOST_STATE_FILTER_LOW) != 0);
  prefs.putBool("tx_allowed", (desiredState.flags & HOST_STATE_TX_ALLOWED) != 0);
  prefs.end();
  persistedState = desiredState;
}

uint8_t getFirmwareFeatures() {
  return (hw.features.hasHL ? FEATURE_HAS_HL : 0)
    | (hw.features.hasPhysPTT ? FEATURE_HAS_PHY_PTT : 0)
    | FEATURE_HAS_ESP32_AFSK;
}

Mode rxIdleMode() {
  return protocolAnySessionFlag(HOST_STATE_RX_AUDIO_OPEN) ? MODE_RX : MODE_STOPPED;
}

uint16_t desiredFilterFlags() {
  return desiredState.flags & (HOST_STATE_FILTER_PRE | HOST_STATE_FILTER_HIGH | HOST_STATE_FILTER_LOW);
}

bool txAllowedByHost() {
  return desiredState.flags & HOST_STATE_TX_ALLOWED;
}

uint16_t deviceStateFlags(uint16_t sessionFlags) {
  uint16_t flags = desiredState.flags;
  flags |= sessionFlags & HOST_STATE_SESSION_FLAG_MASK;
  if (isPhysPttDown) {
    flags |= DEVICE_STATE_PHYS_PTT_DOWN;
  }
  if (mode == MODE_TX) {
    flags |= DEVICE_STATE_TX_ACTIVE;
  }
  if (squelched) {
    flags |= DEVICE_STATE_SQUELCHED;
  }
  return flags;
}

uint8_t deviceMode() {
  switch (mode) {
    case MODE_TX:
      return DEVICE_MODE_TX;
    case MODE_RX:
      return DEVICE_MODE_RX;
    case MODE_STOPPED:
    default:
      return DEVICE_MODE_STOPPED;
  }
}

DeviceState currentDeviceState(uint16_t sessionFlags = 0) {
  return {
    .appliedSequence = desiredState.sequence,
    .memoryId = appliedState.memoryId,
    .flags = deviceStateFlags(sessionFlags),
    .bw = appliedState.bw,
    .freq_tx = appliedState.freq_tx,
    .freq_rx = appliedState.freq_rx,
    .ctcss_tx = appliedState.ctcss_tx,
    .squelch = appliedState.squelch,
    .ctcss_rx = appliedState.ctcss_rx,
    .radioModuleStatus = radioModuleStatus,
    .mode = deviceMode(),
    .lastError = lastDeviceStateError,
    .latestRssi = latestRssi,
  };
}

void sendCurrentDeviceState() {
  bool sent = false;
  if (protocolSessionConnected(protocolUsbSession) && (protocolUsbSession.flags & HOST_STATE_ENABLE_STATUS_REPORTS)) {
    sendDeviceState(*protocolUsbSession.stream, currentDeviceState(protocolUsbSession.flags));
    sent = true;
  }
  if (protocolHasBtSession() && (protocolBtSession.flags & HOST_STATE_ENABLE_STATUS_REPORTS)) {
    sendDeviceState(*protocolBtSession.stream, currentDeviceState(protocolBtSession.flags));
    sent = true;
  }
  if (sent) {
    deviceStateDirty = false;
  }
}

void markDeviceStateDirty() {
  deviceStateDirty = true;
}

bool radioConfigChanged() {
  return !radioConfigApplied
    || appliedState.bw != desiredState.bw
    || appliedState.freq_tx != desiredState.freq_tx
    || appliedState.freq_rx != desiredState.freq_rx
    || appliedState.ctcss_tx != desiredState.ctcss_tx
    || appliedState.squelch != desiredState.squelch
    || appliedState.ctcss_rx != desiredState.ctcss_rx
    || appliedState.memoryId != desiredState.memoryId;
}

void drainRadioSerial() {
  while (Serial2.available()) {
    Serial2.read();
  }
}

void reconcileDesiredState(bool sendReport = true) {
  lastDeviceStateError = DEVICE_STATE_ERROR_NONE;
  bool wantHigh = desiredState.flags & HOST_STATE_HIGH_POWER;
  if (hw.features.hasHL) {
    digitalWrite(hw.pins.pinHl, wantHigh ? LOW : HIGH);
  }
  rssiOn = desiredState.flags & HOST_STATE_RSSI_ENABLED;

  uint16_t filterFlags = desiredFilterFlags();
  uint16_t appliedFilterFlags = appliedState.flags & (HOST_STATE_FILTER_PRE | HOST_STATE_FILTER_HIGH | HOST_STATE_FILTER_LOW);
  if (!filtersApplied || filterFlags != appliedFilterFlags) {
    drainRadioSerial();
    while (!sa818.filters((filterFlags & HOST_STATE_FILTER_PRE), (filterFlags & HOST_STATE_FILTER_HIGH), (filterFlags & HOST_STATE_FILTER_LOW))) {
      lastDeviceStateError = DEVICE_STATE_ERROR_FILTERS_FAILED;
      esp_task_wdt_reset();
    }
    appliedState.flags = (appliedState.flags & ~(HOST_STATE_FILTER_PRE | HOST_STATE_FILTER_HIGH | HOST_STATE_FILTER_LOW)) | filterFlags;
    filtersApplied = true;
  }

  if ((desiredState.flags & HOST_STATE_RADIO_CONFIG_VALID) && radioConfigChanged()) {
    drainRadioSerial();
    while (!sa818.group(desiredState.bw, desiredState.freq_tx, desiredState.freq_rx, desiredState.ctcss_tx, desiredState.squelch, desiredState.ctcss_rx)) {
      lastDeviceStateError = DEVICE_STATE_ERROR_RADIO_CONFIG_FAILED;
      esp_task_wdt_reset();
    }
    appliedState.bw = desiredState.bw;
    appliedState.freq_tx = desiredState.freq_tx;
    appliedState.freq_rx = desiredState.freq_rx;
    appliedState.ctcss_tx = desiredState.ctcss_tx;
    appliedState.squelch = desiredState.squelch;
    appliedState.ctcss_rx = desiredState.ctcss_rx;
    appliedState.memoryId = desiredState.memoryId;
    appliedState.flags |= HOST_STATE_RADIO_CONFIG_VALID;
    radioConfigApplied = true;
  }

  if ((desiredState.flags & HOST_STATE_PTT_REQUESTED) && txAllowedByHost()) {
    setMode(MODE_TX);
  } else {
    setMode(rxIdleMode());
  }
  savePersistedRadioStateIfChanged();
  if (sendReport) {
    markDeviceStateDirty();
  }
}

void setMode(Mode newMode) {
  if (mode == newMode) {
    return;
  }
  mode = newMode;
  markDeviceStateDirty();
  switch (mode) {
    case MODE_STOPPED:
      _LOGI("MODE_STOPPED");
      digitalWrite(hw.pins.pinPtt, HIGH);
      endI2STx();
      initI2SRx();
    break;
    case MODE_RX:
      _LOGI("MODE_RX");
      digitalWrite(hw.pins.pinPtt, HIGH);
      squelchDebounce.forceState(true);
      endI2STx();
      initI2SRx();
    break;
    case MODE_TX:
      _LOGI("MODE_TX");
      txStartTime = millis();
      digitalWrite(hw.pins.pinPtt, LOW);
      endI2SRx();
      initI2STx();
    break;
  }
}

void setup() {
  boardSetup();
  loadPersistedRadioState();
  // Communication with Android via USB cable
  Serial.setRxBufferSize(USB_BUFFER_SIZE);
  Serial.setTxBufferSize(USB_BUFFER_SIZE);
  protocolUsbSession.windowSize = USB_BUFFER_SIZE;
  Serial.begin(115200);
  Serial.println();
  Serial.println("===== kv4p serial output =====");
  Serial.println("This port will emit binary data using the kv4p protocol.");
  Serial.println("You will see watchdog resets — this is expected behavior.");
  Serial.println("Use `logcat` or a kv4p decoder to view readable logs.");
  Serial.println("More info: https://github.com/VanceVagell/kv4p-ht/blob/main/microcontroller-src/kv4p_ht_esp32_wroom_32/readme.md");
  Serial.println("==============================");
  char bluetoothDeviceName[12];
  formatBluetoothDeviceName(bluetoothDeviceName, sizeof(bluetoothDeviceName));
  SerialBT.begin(bluetoothDeviceName);
  protocolBtSession.stream = &SerialBT;
  protocolBtSession.windowSize = USB_BUFFER_SIZE;
  // Configure watch dog timer (WDT), which will reset the system if it gets stuck somehow.
  esp_task_wdt_init(10, true);  // Reboot if locked up for a bit
  esp_task_wdt_add(NULL);       // Add the current task to WDT watch
  buttonsSetup();
  // Set up radio module defaults
  pinMode(hw.pins.pinPd, OUTPUT);
  digitalWrite(hw.pins.pinPd, HIGH);  // Power on
  pinMode(hw.pins.pinSq, INPUT);
  pinMode(hw.pins.pinPtt, OUTPUT);
  digitalWrite(hw.pins.pinPtt, HIGH);  // Rx
  if (hw.features.hasHL) {
    pinMode(hw.pins.pinHl, OUTPUT);
    digitalWrite(hw.pins.pinHl, LOW);  // High power
  }
  // Communication with DRA818V radio module via GPIO pins
  Serial2.begin(9600, SERIAL_8N1, hw.pins.pinRfModuleRxd, hw.pins.pinRfModuleTxd);
  Serial2.setTimeout(10);  // Very short so we don't tie up rx audio while reading from radio module (responses are tiny so this is ok)
  //
  debugSetup();
  // Begin in STOPPED mode
  squelched = (digitalRead(hw.pins.pinSq) == HIGH);
  setMode(MODE_STOPPED);
  initI2SRx();
  ledSetup();
  initRadio((desiredState.flags & HOST_STATE_HIGH_POWER) != 0);
  if (radioModuleStatus == RADIO_MODULE_FOUND) {
    reconcileDesiredState(false);
  }
  sendHello(protocolUsbSession, FIRMWARE_VER, radioModuleStatus, hw.rfModuleType, moduleMinRadioFreq(), moduleMaxRadioFreq(), getFirmwareFeatures(), currentDeviceState());
  _LOGI("Setup is finished");
}

void initRadio(bool isHigh) {
  if (hw.rfModuleType == RF_SA818_UHF) {
    sa818 = sa818_uhf;
  } else {
    sa818 = sa818_vhf;
  }
  if (hw.features.hasHL) {
    digitalWrite(hw.pins.pinHl, isHigh ? LOW : HIGH);
  }
  radioModuleStatus = RADIO_MODULE_NOT_FOUND;
  // The sa818.handshake() has 3 retries internally with 2 seconds between each attempt.
  // We have 3 retries on top of that, so total wait time is up to 20 seconds.
  // This should allow the radio module to power up and respond.
  for (int i = 0; i < 3; i++) {
    esp_task_wdt_reset();
    if (sa818.handshake()) { //Check if radio responded to handshake attempt
      radioModuleStatus = RADIO_MODULE_FOUND;
      sa818.volume(hw.volume);
      sa818.filters(false, false, false);
      break;
    }
  }
}

void handleCommands(ProtocolSession &session, RcvCommand command, uint8_t *params, size_t param_len) {
  switch (command) {
    case COMMAND_HOST_TX_AUDIO:
      if (mode == MODE_TX) {
        processTxAudio(params, param_len);
        esp_task_wdt_reset();
      }
      break;
    case COMMAND_HOST_DESIRED_STATE:
      if (param_len == sizeof(HostDesiredState)) {
        HostDesiredState incomingState;
        memcpy(&incomingState, params, sizeof(HostDesiredState));
        uint16_t oldSessionFlags = session.flags;
        session.flags = incomingState.flags & HOST_STATE_SESSION_FLAG_MASK;
        bool sessionFlagsChanged = oldSessionFlags != session.flags;
        bool globalStateChanged = incomingState.sequence > desiredState.sequence;
        if (globalStateChanged) {
          desiredState = incomingState;
          desiredState.flags &= HOST_STATE_GLOBAL_FLAG_MASK;
        }
        if (sessionFlagsChanged || globalStateChanged) {
          reconcileDesiredState();
        }
        esp_task_wdt_reset();
      }
      break;
  }
}

void handleAx25Data(uint8_t *ax25, size_t ax25_len) {
  if (ax25_len > 0 && ax25_len <= PROTO_MTU && txAllowedByHost()) {
    setMode(MODE_TX);
    sendCurrentDeviceState();
    pulseAprsTxLED();
    processTxAx25(ax25, ax25_len);
    setMode(rxIdleMode());
    sendCurrentDeviceState();
    esp_task_wdt_reset();
  }
}

void rssiLoop() {
  if (rssiOn && mode == MODE_RX) {
    EVERY_N_MILLISECONDS(RSSI_REPORT_INTERVAL_MS) {
      // TODO fix the dra818 library's implementation of rssi(). Right now it just drops the
      // return value from the module, and just tells us success/fail.
      // int rssi = dra->rssi();
      Serial2.println("RSSI?");
      String rssiResponse = Serial2.readString();
      if (rssiResponse.length() > 7) {
        String rssiStr = rssiResponse.substring(5);
        int rssiInt    = rssiStr.toInt();
        if (rssiInt >= 0 && rssiInt <= 255) {
          uint8_t rssi = (uint8_t)rssiInt;
          if (latestRssi != rssi) {
            latestRssi = rssi;
            markDeviceStateDirty();
          }
        }
      }
    }
    END_EVERY_N_MILLISECONDS();
  }
}

void deviceStateLoop() {
  if (!protocolAnySessionFlag(HOST_STATE_ENABLE_STATUS_REPORTS)) {
    return;
  }

  bool sent = false;
  if (deviceStateDirty) {
    sendCurrentDeviceState();
    sent = true;
  }
  EVERY_N_MILLISECONDS(DEVICE_STATE_REPORT_INTERVAL_MS) {
    if (!sent) {
      sendCurrentDeviceState();
    }
  }
  END_EVERY_N_MILLISECONDS();
}

void bluetoothLoop() {
  bool connected = SerialBT.hasClient();
  if (connected && !bluetoothProtocolConnected) {
    bluetoothProtocolConnected = true;
    protocolBtSession.connected = true;
    bluetoothParser.reset();
    sendHello(protocolBtSession, FIRMWARE_VER, radioModuleStatus, hw.rfModuleType, moduleMinRadioFreq(), moduleMaxRadioFreq(), getFirmwareFeatures(), currentDeviceState(protocolBtSession.flags));
  } else if (!connected && bluetoothProtocolConnected) {
    bluetoothProtocolConnected = false;
    protocolBtSession.connected = false;
    uint16_t oldSessionFlags = protocolBtSession.flags;
    protocolBtSession.flags = 0;
    bluetoothParser.reset();
    if (oldSessionFlags != 0) {
      reconcileDesiredState();
    }
  }

  if (bluetoothProtocolConnected) {
    bluetoothParser.loop();
  }
}

void squelchLoop() {
  bool nextSquelched = squelchDebounce.debounce((digitalRead(hw.pins.pinSq) == HIGH));
  if (nextSquelched != squelched) {
    squelched = nextSquelched;
    markDeviceStateDirty();
  }
}

void loop() {
  squelchLoop();
  debugLoop();
  ledLoop();
  buttonsLoop();
  protocolLoop();
  bluetoothLoop();
  rxAudioLoop();
  txAudioLoop();
  rssiLoop();
  deviceStateLoop();
}
