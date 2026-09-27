/*
KV4P-HT (see http://kv4p.com)
Copyright (C) 2026 Vance Vagell

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
#include <string.h>

#include "globals.h"

static constexpr uint16_t KISS_PARAMETER_UNIT_MS = 10;
static constexpr uint8_t DEFAULT_KISS_TXDELAY = 65;
static constexpr uint8_t DEFAULT_KISS_PERSIST = 63;
static constexpr uint8_t DEFAULT_KISS_SLOTTIME = 10;
static constexpr uint8_t AX25_TX_QUEUE_SIZE = 2;

struct [[gnu::packed]] Ax25TxOverride { float freqTx; uint8_t bw; uint8_t ctcssTx; };
struct Ax25TxJob {
  uint16_t len;
  uint8_t data[AX25_MAX_KISS_DATA_LEN];
  bool hasTxOverride;
  Ax25TxOverride txOverride;
};

/** Allocation-free two-frame KISS AX.25 FIFO and p-persistent CSMA policy. */
class Ax25TxScheduler {
public:
  bool enqueue(const uint8_t *frame, size_t len, const Ax25TxOverride *txOverride = nullptr) {
    if (frame == nullptr || len == 0 || len > AX25_MAX_KISS_DATA_LEN || _count == AX25_TX_QUEUE_SIZE) {
      return false;
    }
    bool wasEmpty = _count == 0;
    Ax25TxJob &job = _jobs[_tail];
    job.len = (uint16_t)len;
    memcpy(job.data, frame, len);
    job.hasTxOverride = txOverride != nullptr;
    if (txOverride) job.txOverride = *txOverride;
    _tail = (_tail + 1) % AX25_TX_QUEUE_SIZE;
    _count++;
    if (wasEmpty) _slotPending = false;
    return true;
  }

  bool ready(uint32_t now, bool channelClear, uint8_t randomValue) {
    if (_count == 0) return false;
    if (!channelClear) {
      _slotPending = false;
      return false;
    }
    if (!_slotPending) {
      if (randomValue <= _persist) {
        return true;
      }
      _slotAt = now + slotTimeMs();
      _slotPending = true;
      return false;
    }
    if ((int32_t)(now - _slotAt) < 0) return false;
    if (randomValue <= _persist) {
      _slotPending = false;
      return true;
    }
    _slotAt = now + slotTimeMs();
    return false;
  }

  const Ax25TxJob *head() const { return _count ? &_jobs[_head] : nullptr; }
  const Ax25TxJob *next() const { return _count > 1 ? &_jobs[(_head + 1) % AX25_TX_QUEUE_SIZE] : nullptr; }
  uint8_t count() const { return _count; }

  void complete() {
    if (_count == 0) return;
    _head = (_head + 1) % AX25_TX_QUEUE_SIZE;
    _count--;
    _slotPending = false;
  }

  void setTxDelay(uint8_t value) { _txDelay = value; }
  void setPersist(uint8_t value) { _persist = value; }
  void setSlotTime(uint8_t value) { _slotTime = value; }

  uint16_t txDelayMs() const {
    return (uint16_t)_txDelay * KISS_PARAMETER_UNIT_MS;
  }
  uint16_t slotTimeMs() const { return (uint16_t)_slotTime * KISS_PARAMETER_UNIT_MS; }
  uint8_t persist() const { return _persist; }

private:
  Ax25TxJob _jobs[AX25_TX_QUEUE_SIZE] = {};
  uint8_t _head = 0, _tail = 0, _count = 0;
  uint32_t _slotAt = 0;
  uint8_t _txDelay = DEFAULT_KISS_TXDELAY;
  uint8_t _persist = DEFAULT_KISS_PERSIST;
  uint8_t _slotTime = DEFAULT_KISS_SLOTTIME;
  bool _slotPending = false;
};
