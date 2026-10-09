#pragma once

#include <Arduino.h>

namespace smartplug_diag {

enum class Event : uint8_t {
  boot, reset_reason, relay_on_queued, relay_off_queued,
  zero_cross_waiting, coil_started, coil_completed, zero_cross_timeout,
  mqtt_connected, mqtt_disconnected, wifi_connected, wifi_disconnected,
  timer_command, schedule_command, failure
};

struct Entry {
  uint32_t uptimeMs;
  uint16_t bootId;
  uint8_t event;
  uint8_t detail;
};

constexpr uint8_t kCapacity = 24;
const char* name(Event event);
void begin(bool filesystemReady);
void record(Event event, uint8_t detail = 0);
void tick();
void clear();
uint8_t count();
Entry newest(uint8_t index);
bool checkpointHealthy();
uint32_t lastCheckpointAgeMs();
}  // namespace smartplug_diag
