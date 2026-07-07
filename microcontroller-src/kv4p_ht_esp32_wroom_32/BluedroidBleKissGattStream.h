/*
KV4P-HT (see http://kv4p.com)
Copyright (C) 2026 Vance Vagell

Bluedroid-backed BLE KISS GATT Stream transport.
BLE KISS API spec:
https://github.com/hessu/aprs-specs/blob/master/BLE-KISS-API.md

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

#pragma once

#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace blekiss {

static constexpr const char *BLE_KISS_SERVICE_UUID = "00000001-ba2a-46c9-ae49-01b0961f68bb";
static constexpr const char *BLE_KISS_TX_CHAR_UUID = "00000002-ba2a-46c9-ae49-01b0961f68bb";
static constexpr const char *BLE_KISS_RX_CHAR_UUID = "00000003-ba2a-46c9-ae49-01b0961f68bb";

} // namespace blekiss

template <
    size_t INCOMING_STREAM_SIZE = 1024,
    size_t OUTGOING_CHUNK_SIZE = 768,
    size_t OUTGOING_QUEUE_DEPTH = 4,
    size_t NOTIFY_WORK_BUFFER_SIZE = 244>
class BluedroidBleKissGattStream : public Stream {
public:
  static_assert(INCOMING_STREAM_SIZE > 0, "INCOMING_STREAM_SIZE must be > 0");
  static_assert(OUTGOING_CHUNK_SIZE > 0, "OUTGOING_CHUNK_SIZE must be > 0");
  static_assert(OUTGOING_QUEUE_DEPTH > 0, "OUTGOING_QUEUE_DEPTH must be > 0");
  static_assert(NOTIFY_WORK_BUFFER_SIZE > 0, "NOTIFY_WORK_BUFFER_SIZE must be > 0");

  using Print::write;

  struct Config {
    const char *deviceName = nullptr;
    uint16_t preferredMtu = 185;
    const char *serviceUuid = blekiss::BLE_KISS_SERVICE_UUID;
    const char *txCharUuid = blekiss::BLE_KISS_TX_CHAR_UUID;
    const char *rxCharUuid = blekiss::BLE_KISS_RX_CHAR_UUID;
    bool autoStartAdvertising = true;
    bool restartAdvertisingOnDisconnect = true;
    bool requireNotifySubscription = true;
    uint8_t maxNotifyChunksPerLoop = 1;
    uint16_t minNotifyIntervalMs = 12;
    uint16_t notifyFailureBackoffMs = 100;
    uint16_t writeQueueWaitMs = 0;
    uint32_t subscribeTimeoutMs = 10000;
    uint16_t connMinInterval = 6;
    uint16_t connMaxInterval = 12;
    uint16_t connLatency = 0;
    uint16_t connTimeout = 400;
    esp_power_level_t txPower = ESP_PWR_LVL_P7;
  };

  struct Stats {
    uint32_t rxBytes = 0;
    uint32_t rxChunks = 0;
    uint32_t rxIncomingOverflowDrops = 0;

    uint32_t txBytesQueued = 0;
    uint32_t txBytesSent = 0;
    uint32_t txChunksQueued = 0;
    uint32_t txChunksSent = 0;
    uint32_t txNotifyChunks = 0;
    uint32_t txQueueFullDrops = 0;
    uint32_t txNotifyFailures = 0;
    uint32_t subscribeTimeoutDisconnects = 0;
  };

  explicit BluedroidBleKissGattStream(const Config &config = Config()) : _config(config) {}

  BluedroidBleKissGattStream(const BluedroidBleKissGattStream &) = delete;
  BluedroidBleKissGattStream &operator=(const BluedroidBleKissGattStream &) = delete;

  bool begin() {
    if (_begun) {
      return true;
    }
    if (instanceSlot() != nullptr && instanceSlot() != this) {
      return false;
    }

    resetRuntimeState();
    instanceSlot() = this;

    BLEDevice::init(_config.deviceName != nullptr ? _config.deviceName : "");
    if (!BLEDevice::getInitialized()) {
      instanceSlot() = nullptr;
      return false;
    }
    BLEDevice::setMTU((_config.preferredMtu < 23) ? 23 : _config.preferredMtu);
    BLEDevice::setPower(_config.txPower);

    _server = BLEDevice::createServer();
    if (_server == nullptr) {
      instanceSlot() = nullptr;
      return false;
    }
    _server->setCallbacks(&_serverCallbacks);

    BLEService *service = _server->createService(_config.serviceUuid);
    if (service == nullptr) {
      instanceSlot() = nullptr;
      return false;
    }

    _rxChar = service->createCharacteristic(
        _config.rxCharUuid,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    _txChar = service->createCharacteristic(
        _config.txCharUuid,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    if (_rxChar == nullptr || _txChar == nullptr) {
      instanceSlot() = nullptr;
      return false;
    }

    _notifyDescriptor = new BLE2902();
    if (_notifyDescriptor == nullptr) {
      instanceSlot() = nullptr;
      return false;
    }
    _rxChar->addDescriptor(_notifyDescriptor);
    _rxChar->setCallbacks(&_rxCallbacks);
    _txChar->setCallbacks(&_txCallbacks);

    service->start();
    _begun = true;

    if (_config.autoStartAdvertising) {
      startAdvertising();
    }
    return true;
  }

  void end() {
    if (!_begun) {
      return;
    }

    BLEDevice::stopAdvertising();
    resetConnectionState();
    clearIncomingStream();
    clearQueue();

    _server = nullptr;
    _txChar = nullptr;
    _rxChar = nullptr;
    _notifyDescriptor = nullptr;
    _begun = false;

    if (instanceSlot() == this) {
      instanceSlot() = nullptr;
    }
  }

  void loop() {
    disconnectUnsubscribedClientIfNeeded();
    (void)drainOutgoing(_config.maxNotifyChunksPerLoop);
  }

  void startAdvertising() {
    BLEAdvertising *adv = BLEDevice::getAdvertising();
    if (adv == nullptr) {
      return;
    }
    if (!_advertisingConfigured) {
      adv->addServiceUUID(_config.serviceUuid);
      adv->setScanResponse(true);
      _advertisingConfigured = true;
    }
    BLEDevice::startAdvertising();
  }

  void setMaxNotifyChunksPerLoop(uint8_t chunks) {
    _config.maxNotifyChunksPerLoop = (chunks == 0) ? 1 : chunks;
  }

  bool isBegun() const { return _begun; }
  bool isConnected() const { return _connected; }
  bool isNotifySubscribed() const { return _notifySubscribed; }

  void disconnectClient() {
    if (!_connected) {
      return;
    }
    _LOGW("BLE KISS forcing client disconnect: conn_id=%u notify_subscribed=%u",
          _hasConnId ? _connId : 0,
          _notifySubscribed ? 1 : 0);
    if (_server != nullptr && _hasConnId) {
      _server->disconnect(_connId);
    }
    resetConnectionState();

    if (_config.restartAdvertisingOnDisconnect) {
      startAdvertising();
    }
  }

  bool canSend() {
    if (!_connected || _rxChar == nullptr) {
      return false;
    }
    if (_config.requireNotifySubscription && !refreshNotifySubscribed()) {
      return false;
    }
    return true;
  }

  uint16_t getMtu() const { return _mtu; }

  int available() override {
    portENTER_CRITICAL(&_incomingMux);
    const size_t count = _incomingCount;
    portEXIT_CRITICAL(&_incomingMux);
    return (count > static_cast<size_t>(INT_MAX)) ? INT_MAX : static_cast<int>(count);
  }

  int read() override {
    uint8_t b = 0;
    if (!popIncomingByte(b)) {
      return -1;
    }
    return b;
  }

  int peek() override {
    portENTER_CRITICAL(&_incomingMux);
    if (_incomingCount == 0) {
      portEXIT_CRITICAL(&_incomingMux);
      return -1;
    }
    const uint8_t b = _incomingBuf[_incomingHead];
    portEXIT_CRITICAL(&_incomingMux);
    return b;
  }

  void flush() override {
    while (drainOutgoing(1) == 1) {
    }
  }

  size_t write(uint8_t b) override {
    return write(&b, 1);
  }

  size_t write(const uint8_t *data, size_t len) override {
    if (sendBytes(data, len)) {
      return len;
    }
    return 0;
  }

  bool sendBytes(const uint8_t *data, size_t len) {
    if (data == nullptr && len != 0) {
      return false;
    }
    if (len == 0) {
      return true;
    }

    const size_t chunksNeeded = (len + OUTGOING_CHUNK_SIZE - 1) / OUTGOING_CHUNK_SIZE;
    if (chunksNeeded > OUTGOING_QUEUE_DEPTH) {
      ++_stats.txQueueFullDrops;
      return false;
    }

    const uint32_t waitStartMs = millis();
    while (outgoingQueueFree() < chunksNeeded) {
      if (_config.writeQueueWaitMs == 0 || (uint32_t)(millis() - waitStartMs) >= _config.writeQueueWaitMs) {
        ++_stats.txQueueFullDrops;
        return false;
      }

      size_t drained = drainOutgoing(_config.maxNotifyChunksPerLoop);
      if (drained == 0) {
        delay(1);
      } else {
        yield();
      }
    }

    portENTER_CRITICAL(&_queueMux);
    if (chunksNeeded > (OUTGOING_QUEUE_DEPTH - _queueCount)) {
      ++_stats.txQueueFullDrops;
      portEXIT_CRITICAL(&_queueMux);
      return false;
    }

    size_t offset = 0;
    while (offset < len) {
      QueueSlot &slot = _queue[_queueTail];
      const size_t remaining = len - offset;
      const size_t chunkLen = (remaining < OUTGOING_CHUNK_SIZE) ? remaining : OUTGOING_CHUNK_SIZE;
      memcpy(slot.data, data + offset, chunkLen);
      slot.len = chunkLen;
      _queueTail = (_queueTail + 1) % OUTGOING_QUEUE_DEPTH;
      ++_queueCount;
      ++_stats.txChunksQueued;
      offset += chunkLen;
    }
    _stats.txBytesQueued += static_cast<uint32_t>(len);
    portEXIT_CRITICAL(&_queueMux);
    return true;
  }

  size_t drainOutgoing(size_t maxChunks = 1) {
    if (maxChunks == 0) {
      return 0;
    }

    size_t sentChunks = 0;
    while (sentChunks < maxChunks) {
      if (!flushOneOutgoingChunk()) {
        break;
      }
      ++sentChunks;
    }
    return sentChunks;
  }

  size_t outgoingQueueCount() const {
    portENTER_CRITICAL(&_queueMux);
    const size_t count = _queueCount;
    portEXIT_CRITICAL(&_queueMux);
    return count;
  }

  size_t outgoingQueueCapacity() const { return OUTGOING_QUEUE_DEPTH; }
  size_t outgoingQueueFree() const { return OUTGOING_QUEUE_DEPTH - outgoingQueueCount(); }
  bool outgoingQueueEmpty() const { return outgoingQueueCount() == 0; }
  bool outgoingQueueFull() const { return outgoingQueueCount() >= OUTGOING_QUEUE_DEPTH; }

  const Stats &stats() const { return _stats; }
  void clearStats() { _stats = Stats{}; }

private:
  struct QueueSlot {
    uint8_t data[OUTGOING_CHUNK_SIZE];
    size_t len = 0;
  };

  class InternalServerCallbacks : public BLEServerCallbacks {
  public:
    void onConnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
      (void)server;
      if (instanceSlot() != nullptr) {
        instanceSlot()->handleConnect(param);
      }
    }

    void onDisconnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
      (void)server;
      if (instanceSlot() != nullptr) {
        instanceSlot()->handleDisconnect(param);
      }
    }

    void onMtuChanged(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
      (void)server;
      if (instanceSlot() != nullptr && param != nullptr) {
        instanceSlot()->handleMtuChange(param->mtu.mtu);
      }
    }
  };

  class InternalTxCallbacks : public BLECharacteristicCallbacks {
  public:
    void onWrite(BLECharacteristic *c, esp_ble_gatts_cb_param_t *param) override {
      if (instanceSlot() == nullptr || c == nullptr) {
        return;
      }

      if (param != nullptr && param->write.value != nullptr && param->write.len > 0) {
        instanceSlot()->enqueueIncomingBytes(param->write.value, param->write.len);
        return;
      }

      const std::string value = c->getValue();
      if (!value.empty()) {
        instanceSlot()->enqueueIncomingBytes(
            reinterpret_cast<const uint8_t *>(value.data()), value.size());
      }
    }
  };

  class InternalRxCallbacks : public BLECharacteristicCallbacks {
  public:
    void onStatus(BLECharacteristic *c, Status s, uint32_t code) override {
      (void)c;
      (void)code;
      if (instanceSlot() == nullptr) {
        return;
      }
      if (s == ERROR_NOTIFY_DISABLED) {
        instanceSlot()->_notifySubscribed = false;
      } else if (s == ERROR_GATT || s == ERROR_NO_CLIENT) {
        ++instanceSlot()->_stats.txNotifyFailures;
        instanceSlot()->_lastNotifySucceeded = false;
        instanceSlot()->_lastNotifyStatusSeen = true;
      } else if (s == SUCCESS_NOTIFY) {
        instanceSlot()->_lastNotifySucceeded = true;
        instanceSlot()->_lastNotifyStatusSeen = true;
      }
    }
  };

  static BluedroidBleKissGattStream *&instanceSlot() {
    static BluedroidBleKissGattStream *instance = nullptr;
    return instance;
  }

  Config _config;
  Stats _stats{};

  BLEServer *_server = nullptr;
  BLECharacteristic *_txChar = nullptr;
  BLECharacteristic *_rxChar = nullptr;
  BLE2902 *_notifyDescriptor = nullptr;

  InternalServerCallbacks _serverCallbacks;
  InternalTxCallbacks _txCallbacks;
  InternalRxCallbacks _rxCallbacks;

  bool _begun = false;
  bool _connected = false;
  bool _notifySubscribed = false;
  bool _hasConnId = false;
  bool _advertisingConfigured = false;
  bool _lastNotifySucceeded = false;
  bool _lastNotifyStatusSeen = false;
  uint16_t _connId = 0;
  uint16_t _mtu = 23;
  uint32_t _connectedAtMs = 0;
  uint32_t _lastNotifyAttemptMs = 0;
  uint32_t _notifyBackoffUntilMs = 0;

  uint8_t _incomingBuf[INCOMING_STREAM_SIZE];
  size_t _incomingHead = 0;
  size_t _incomingTail = 0;
  size_t _incomingCount = 0;
  mutable portMUX_TYPE _incomingMux = portMUX_INITIALIZER_UNLOCKED;

  QueueSlot _queue[OUTGOING_QUEUE_DEPTH];
  size_t _queueHead = 0;
  size_t _queueTail = 0;
  size_t _queueCount = 0;
  size_t _currentChunkOffset = 0;
  mutable portMUX_TYPE _queueMux = portMUX_INITIALIZER_UNLOCKED;
  uint8_t _notifyWorkBuf[NOTIFY_WORK_BUFFER_SIZE];

  void resetRuntimeState() {
    resetConnectionState();
    clearIncomingStream();
    clearQueue();
  }

  void resetConnectionState() {
    _connected = false;
    _notifySubscribed = false;
    resetNotifyDescriptor();
    _hasConnId = false;
    _connId = 0;
    _mtu = 23;
    _connectedAtMs = 0;
    _lastNotifyAttemptMs = 0;
    _notifyBackoffUntilMs = 0;
    _lastNotifySucceeded = false;
    _lastNotifyStatusSeen = false;
  }

  void clearIncomingStream() {
    portENTER_CRITICAL(&_incomingMux);
    _incomingHead = 0;
    _incomingTail = 0;
    _incomingCount = 0;
    portEXIT_CRITICAL(&_incomingMux);
  }

  bool pushIncomingByteLocked(uint8_t b) {
    if (_incomingCount >= INCOMING_STREAM_SIZE) {
      return false;
    }

    _incomingBuf[_incomingTail] = b;
    _incomingTail = (_incomingTail + 1) % INCOMING_STREAM_SIZE;
    ++_incomingCount;
    return true;
  }

  bool popIncomingByte(uint8_t &b) {
    portENTER_CRITICAL(&_incomingMux);
    if (_incomingCount == 0) {
      portEXIT_CRITICAL(&_incomingMux);
      return false;
    }

    b = _incomingBuf[_incomingHead];
    _incomingHead = (_incomingHead + 1) % INCOMING_STREAM_SIZE;
    --_incomingCount;
    portEXIT_CRITICAL(&_incomingMux);
    return true;
  }

  void enqueueIncomingBytes(const uint8_t *data, size_t len) {
    if (data == nullptr || len == 0) {
      return;
    }

    portENTER_CRITICAL(&_incomingMux);
    for (size_t i = 0; i < len; ++i) {
      if (!pushIncomingByteLocked(data[i])) {
        ++_stats.rxIncomingOverflowDrops;
        continue;
      }
      ++_stats.rxBytes;
    }
    ++_stats.rxChunks;
    portEXIT_CRITICAL(&_incomingMux);
  }

  void popQueueHeadLocked() {
    if (_queueCount == 0) {
      return;
    }

    _queue[_queueHead].len = 0;
    _queueHead = (_queueHead + 1) % OUTGOING_QUEUE_DEPTH;
    --_queueCount;
    _currentChunkOffset = 0;
  }

  void advanceQueueLocked(size_t len) {
    while (len > 0 && _queueCount > 0) {
      QueueSlot &slot = _queue[_queueHead];
      if (_currentChunkOffset >= slot.len) {
        popQueueHeadLocked();
        continue;
      }

      const size_t remaining = slot.len - _currentChunkOffset;
      if (len < remaining) {
        _currentChunkOffset += len;
        len = 0;
      } else {
        len -= remaining;
        ++_stats.txChunksSent;
        popQueueHeadLocked();
      }
    }
  }

  void clearQueue() {
    portENTER_CRITICAL(&_queueMux);
    for (size_t i = 0; i < OUTGOING_QUEUE_DEPTH; ++i) {
      _queue[i].len = 0;
    }
    _queueHead = 0;
    _queueTail = 0;
    _queueCount = 0;
    _currentChunkOffset = 0;
    portEXIT_CRITICAL(&_queueMux);
  }

  bool refreshNotifySubscribed() {
    bool subscribed = (_notifyDescriptor != nullptr && _notifyDescriptor->getNotifications());
    if (subscribed != _notifySubscribed) {
      _LOGI("BLE KISS notify subscription %s", subscribed ? "enabled" : "disabled");
    }
    _notifySubscribed = subscribed;
    return _notifySubscribed;
  }

  void resetNotifyDescriptor() {
    if (_notifyDescriptor != nullptr) {
      _notifyDescriptor->setNotifications(false);
      _notifyDescriptor->setIndications(false);
    }
  }

  bool flushOneOutgoingChunk() {
    if (_rxChar == nullptr || !_connected) {
      return false;
    }
    if (_config.requireNotifySubscription && !refreshNotifySubscribed()) {
      return false;
    }

    const uint32_t now = millis();
    if (_notifyBackoffUntilMs != 0 && (int32_t)(now - _notifyBackoffUntilMs) < 0) {
      return false;
    }

    const uint16_t intervalMs = _config.minNotifyIntervalMs;
    if (intervalMs != 0 && (uint32_t)(now - _lastNotifyAttemptMs) < intervalMs) {
      return false;
    }

    const size_t attPayload = (_mtu > 3) ? static_cast<size_t>(_mtu - 3) : 20;
    const size_t maxNotifyLen = (attPayload < NOTIFY_WORK_BUFFER_SIZE) ? attPayload : NOTIFY_WORK_BUFFER_SIZE;
    if (maxNotifyLen == 0) {
      return false;
    }

    size_t notifyLen = 0;
    size_t scanHead = 0;
    size_t scanCount = 0;
    size_t scanOffset = 0;
    portENTER_CRITICAL(&_queueMux);
    if (_queueCount == 0) {
      portEXIT_CRITICAL(&_queueMux);
      return false;
    }

    scanHead = _queueHead;
    scanCount = _queueCount;
    scanOffset = _currentChunkOffset;
    while (notifyLen < maxNotifyLen && scanCount > 0) {
      QueueSlot &slot = _queue[scanHead];
      if (scanOffset >= slot.len) {
        scanHead = (scanHead + 1) % OUTGOING_QUEUE_DEPTH;
        --scanCount;
        scanOffset = 0;
        continue;
      }

      const size_t remainingSlot = slot.len - scanOffset;
      const size_t remainingNotify = maxNotifyLen - notifyLen;
      const size_t copyLen = (remainingSlot < remainingNotify) ? remainingSlot : remainingNotify;
      memcpy(_notifyWorkBuf + notifyLen, slot.data + scanOffset, copyLen);
      notifyLen += copyLen;
      scanOffset += copyLen;
    }
    portEXIT_CRITICAL(&_queueMux);

    if (notifyLen == 0) {
      return false;
    }

    _rxChar->setValue(_notifyWorkBuf, notifyLen);
    _lastNotifyAttemptMs = now;
    _lastNotifySucceeded = false;
    _lastNotifyStatusSeen = false;
    _rxChar->notify();

    if (_lastNotifyStatusSeen && !_lastNotifySucceeded) {
      _notifyBackoffUntilMs = millis() + _config.notifyFailureBackoffMs;
      return false;
    }
    _notifyBackoffUntilMs = 0;

    portENTER_CRITICAL(&_queueMux);
    advanceQueueLocked(notifyLen);
    _stats.txBytesSent += static_cast<uint32_t>(notifyLen);
    ++_stats.txNotifyChunks;
    portEXIT_CRITICAL(&_queueMux);

    return true;
  }

  void handleConnect(esp_ble_gatts_cb_param_t *param) {
    _connected = true;
    _notifySubscribed = false;
    resetNotifyDescriptor();
    _mtu = 23;
    _connectedAtMs = millis();
    _lastNotifyAttemptMs = 0;
    _notifyBackoffUntilMs = 0;
    _lastNotifySucceeded = false;
    _lastNotifyStatusSeen = false;
    if (_server != nullptr && param != nullptr) {
      _connId = param->connect.conn_id;
      _hasConnId = true;
      _LOGI("BLE KISS client connected: conn_id=%u", _connId);
      _server->updateConnParams(
          param->connect.remote_bda,
          _config.connMinInterval,
          _config.connMaxInterval,
          _config.connLatency,
          _config.connTimeout);
    } else {
      _LOGI("BLE KISS client connected");
    }
  }

  void handleDisconnect(esp_ble_gatts_cb_param_t *param) {
    if (param != nullptr) {
      _LOGI("BLE KISS client disconnected: conn_id=%u reason=0x%02x",
            param->disconnect.conn_id,
            param->disconnect.reason);
    } else {
      _LOGI("BLE KISS client disconnected");
    }
    resetConnectionState();
    clearIncomingStream();
    clearQueue();

    if (_config.restartAdvertisingOnDisconnect) {
      startAdvertising();
    }
  }

  void handleMtuChange(uint16_t mtu) {
    _mtu = (mtu < 23) ? 23 : mtu;
  }

  void disconnectUnsubscribedClientIfNeeded() {
    if (!_connected) {
      return;
    }

    if (_config.requireNotifySubscription && _config.subscribeTimeoutMs != 0) {
      refreshNotifySubscribed();
      if (!_notifySubscribed && (uint32_t)(millis() - _connectedAtMs) >= _config.subscribeTimeoutMs) {
        ++_stats.subscribeTimeoutDisconnects;
        disconnectClient();
        return;
      }
    }
  }
};
