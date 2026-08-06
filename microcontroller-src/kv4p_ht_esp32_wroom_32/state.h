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

#include "protocol.h"

const char RADIO_MODULE_NOT_FOUND = 'x';
const char RADIO_MODULE_FOUND = 'f';

char radioModuleStatus = RADIO_MODULE_NOT_FOUND;
boolean rssiOn = true;
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
