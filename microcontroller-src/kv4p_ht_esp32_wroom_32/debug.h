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
#pragma once

#include <Arduino.h>
#include "globals.h"
#include "protocol.h"

#ifndef RELEASE
#define _LOGE(fmt, ...)           \
  {                               \
    debug_log_printf(COMMAND_DEBUG_ERROR, ARDUHAL_LOG_FORMAT(E, fmt), ##__VA_ARGS__);       \
  }
#define _LOGW(fmt, ...)           \
  {                               \
    debug_log_printf(COMMAND_DEBUG_WARN, ARDUHAL_LOG_FORMAT(W, fmt), ##__VA_ARGS__);       \
  }
#define _LOGI(fmt, ...)           \
  {                               \
    debug_log_printf(COMMAND_DEBUG_INFO, ARDUHAL_LOG_FORMAT(I, fmt), ##__VA_ARGS__);       \
  }
#define _LOGD(fmt, ...)           \
  {                               \
    debug_log_printf(COMMAND_DEBUG_DEBUG, ARDUHAL_LOG_FORMAT(D, fmt), ##__VA_ARGS__);       \
  }
#define _LOGT(fmt, ...)           \
  {                               \
    debug_log_printf(COMMAND_DEBUG_TRACE, ARDUHAL_LOG_FORMAT(T, fmt), ##__VA_ARGS__);       \
  }
  #else
#define _LOGE(fmt, ...)           \
  {                               \
  }
#define _LOGW(fmt, ...)           \
  {                               \
  }
#define _LOGI(fmt, ...)           \
  {                               \
  }
#define _LOGD(fmt, ...)           \
  {                               \
  }
#define _LOGT(fmt, ...)           \
  {                               \
  }
  #endif

int debug_log_printf(SndCommand cmd, const char* format, ...) {
  static char loc_buf[256];
  char* temp = loc_buf;
  int len;
  va_list arg;
  va_list copy;
  va_start(arg, format);
  va_copy(copy, arg);
  len = vsnprintf(NULL, 0, format, arg);
  va_end(copy);
  if (len >= sizeof(loc_buf)) {
    temp = (char*)malloc(len + 1);
    if (temp == NULL) {
      return 0;
    }
  }
  vsnprintf(temp, len + 1, format, arg);
  __sendCmdToHost(cmd, (byte*) temp, len);
  va_end(arg);
  if (len >= sizeof(loc_buf)) {
    free(temp);
  }
  return len;
}

void printHardwareConfig() {
#ifndef RELEASE  
  _LOGI("Hardware Configuration:");
  _LOGI("  PIN_RF_RXD     = %d",   hw.pins.pinRfModuleRxd);
  _LOGI("  PIN_RF_TXD     = %d",   hw.pins.pinRfModuleTxd);
  _LOGI("  PIN_AUDIO_OUT  = %d",   hw.pins.pinAudioOut);
  _LOGI("  PIN_AUDIO_IN   = %d",   hw.pins.pinAudioIn);
  _LOGI("  PIN_PTT        = %d",   hw.pins.pinPtt);
  _LOGI("  PIN_PD         = %d",   hw.pins.pinPd);
  _LOGI("  PIN_SQ         = %d",   hw.pins.pinSq);
  _LOGI("  PIN_PHYS_PTT1  = %d",   hw.pins.pinPttPhys1);
  _LOGI("  PIN_PHYS_PTT2  = %d",   hw.pins.pinPttPhys2);
  _LOGI("  PIN_PIXELS     = %d",   hw.pins.pinPixels);
  _LOGI("  PIN_LED        = %d",   hw.pins.pinLed);
  _LOGI("  ADC_ATTEN      = %d",   hw.adcAttenuation);
  _LOGI("  ADC_BIAS       = %.3f", hw.adcBias);
  _LOGI("  VOLUME         = %d",   hw.volume);
  _LOGI("  RF_MODULE_TYPE = %s",   hw.rfModuleType == RF_SA818_VHF ? "RF_SA818_VHF" : "RF_SA818_UHF");
  _LOGI("  PIN_HL         = %d",   hw.pins.pinHl);
#endif
}

void printEnvironment() {
#ifndef RELEASE
  esp_reset_reason_t reset_reason = esp_reset_reason();
  _LOGI("---");
  switch (reset_reason) {
    case ESP_RST_POWERON:
      _LOGI("Reset Reason: Power On Reset");
      break;
    case ESP_RST_EXT:
      _LOGI("Reset Reason: External Pin Reset");
      break;
    case ESP_RST_SW:
      _LOGI("Reset Reason: Software Reset");
      break;
    case ESP_RST_PANIC:
      _LOGI("Reset Reason: Exception/Panic Reset");
      break;
    case ESP_RST_INT_WDT:
      _LOGI("Reset Reason: Interrupt Watchdog Reset");
      break;
    case ESP_RST_TASK_WDT:
      _LOGI("Reset Reason: Task Watchdog Reset");
      break;
    case ESP_RST_WDT:
      _LOGI("Reset Reason: Other Watchdog Reset");
      break;
    case ESP_RST_DEEPSLEEP:
      _LOGI("Reset Reason: Deep Sleep Reset");
      break;
    case ESP_RST_BROWNOUT:
      _LOGI("Reset Reason: Brownout Reset");
      break;
    case ESP_RST_SDIO:
      _LOGI("Reset Reason: SDIO Reset");
      break;
    default:
      _LOGI("Reset Reason: Unknown");
      break;
  }
  _LOGI("Heap Size: %d", ESP.getHeapSize());
  _LOGI("SDK Version: %s", ESP.getSdkVersion());
  _LOGI("CPU Freq: %d", ESP.getCpuFreqMHz());
  _LOGI("Sketch MD5: %s", ESP.getSketchMD5().c_str());
  _LOGI("Chip model: %s", ESP.getChipModel());
  _LOGI("PSRAM size: %d", ESP.getPsramSize());
  _LOGI("FLASH size: %d", ESP.getFlashChipSize());
  _LOGI("EFUSE mac: 0x%llx", ESP.getEfuseMac());
  _LOGI("Compiled on: %s", __DATE__ " " __TIME__);
  _LOGI("Git Commit: %s", GIT_COMMIT_ID);
  _LOGI("Git branch: %s", GIT_BRANCH);
  _LOGI("Git Commit date: %s", GIT_COMMIT_DATE);
  _LOGI("Git Tag is: %s", GIT_TAG);
  _LOGI("Git is dirty: %d", GIT_DIRTY); 
  _LOGI("---");
#endif  
}

#ifndef RELEASE
extern "C" void _esp_error_check_failed(esp_err_t rc, const char *file, int line, const char *function, const char *expression){
  debug_log_printf(COMMAND_DEBUG_ERROR, "[%6u][E][%s:%u]: %s ==> %s", (unsigned long) (esp_timer_get_time() / 1000ULL), file, line, expression, esp_err_to_name(rc));
  debug_log_printf(COMMAND_DEBUG_ERROR, "[%6u][E][%s:%u]: ESP_ERROR_CHECK failed! Halting.", (unsigned long) (esp_timer_get_time() / 1000ULL), file, line);
  while (true);
}
#endif 

void measureLoopFrequency() {
#ifndef RELEASE
  // Exponential Weighted Moving Average (EWMA) for loop time
  static float avgLoopTime = 0;
  const float alpha = 0.1;  // Smoothing factor (adjust as needed)
  static uint32_t lastTime = 0;
  static uint32_t startTime = 0;
  // Measure time per iteration
  uint32_t now = micros();
  uint32_t duration = now - startTime;
  startTime = now;
  // Apply EWMA filtering
  avgLoopTime = alpha * duration + (1 - alpha) * avgLoopTime;
  // Report every second
  if (now - lastTime >= 1000000) {  // 1,000,000 µs = 1 second
    float frequency = 1e6 / avgLoopTime;  // Convert loop time to frequency
    _LOGI("Loop Time: %.2f µs, Frequency: %.2f Hz", avgLoopTime, frequency);
    lastTime = now;
  }
#endif  
}

void inline debugSetup() {
  printEnvironment();
  printHardwareConfig();
}

void inline debugLoop() {
  measureLoopFrequency();
}
