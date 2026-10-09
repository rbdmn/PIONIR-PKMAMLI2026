#include "SmartPlugDiagnostics.h"
#include <ESP8266WiFi.h>
#include <LittleFS.h>
#include <stddef.h>
#include <string.h>
#include "SmartPlugReliability.h"

namespace smartplug_diag {
namespace {
constexpr uint32_t kMagic = 0x53504447U;  // SPDG
constexpr uint32_t kSchema = 1U;
constexpr uint32_t kRtcOffsetWords = 32U;  // preserve SDK/eboot's first 128 B
constexpr uint32_t kCheckpointMs = 30000UL;
constexpr char kFileA[] = "/diag-a.bin";
constexpr char kFileB[] = "/diag-b.bin";

struct Snapshot {
  uint32_t magic;
  uint32_t schema;
  uint32_t sequence;
  uint16_t bootId;
  uint8_t head;
  uint8_t used;
  Entry entries[kCapacity];
  uint32_t crc;
};
static_assert(sizeof(Snapshot) <= 384 && sizeof(Snapshot) % 4 == 0,
              "diagnostic RTC record must fit reserved user RTC memory");

Snapshot state = {};
bool fsReady = false;
bool dirty = false;
bool checkpointOk = false;
uint32_t lastCheckpointAt = 0;
uint32_t lastAttemptAt = 0;

void seal(Snapshot& value) {
  value.crc = smartplug_reliability::crc32(
      reinterpret_cast<const uint8_t*>(&value), offsetof(Snapshot, crc));
}
bool valid(const Snapshot& value) {
  return value.magic == kMagic && value.schema == kSchema &&
         value.head < kCapacity && value.used <= kCapacity &&
         value.crc == smartplug_reliability::crc32(
             reinterpret_cast<const uint8_t*>(&value), offsetof(Snapshot, crc));
}
bool readFile(const char* path, Snapshot& result) {
  File file = LittleFS.open(path, "r");
  if (!file) return false;
  const bool good = file.size() == sizeof(result) &&
      file.readBytes(reinterpret_cast<char*>(&result), sizeof(result)) == sizeof(result);
  file.close();
  return good && valid(result);
}
bool checkpoint() {
  if (!fsReady) return false;
  const char* path = (state.sequence & 1U) ? kFileA : kFileB;
  File file = LittleFS.open(path, "w");
  if (!file) return false;
  const bool written = file.write(reinterpret_cast<const uint8_t*>(&state),
                                  sizeof(state)) == sizeof(state);
  file.flush();
  file.close();
  Snapshot verified = {};
  return written && readFile(path, verified) &&
         verified.sequence == state.sequence;
}
void saveRtc() {
  seal(state);
  ESP.rtcUserMemoryWrite(kRtcOffsetWords,
                         reinterpret_cast<uint32_t*>(&state), sizeof(state));
}
}  // namespace

const char* name(Event event) {
  switch (event) {
    case Event::boot: return "boot";
    case Event::reset_reason: return "reset_reason";
    case Event::relay_on_queued: return "relay_on_queued";
    case Event::relay_off_queued: return "relay_off_queued";
    case Event::zero_cross_waiting: return "zero_cross_waiting";
    case Event::coil_started: return "coil_pulse_started";
    case Event::coil_completed: return "coil_pulse_completed";
    case Event::zero_cross_timeout: return "zero_cross_timeout";
    case Event::mqtt_connected: return "mqtt_reconnect";
    case Event::mqtt_disconnected: return "mqtt_disconnect";
    case Event::wifi_connected: return "wifi_reconnect";
    case Event::wifi_disconnected: return "wifi_disconnect";
    case Event::timer_command: return "timer_command";
    case Event::schedule_command: return "schedule_command";
    case Event::failure: return "failure";
  }
  return "unknown";
}

void begin(bool filesystemReady) {
  fsReady = filesystemReady;
  Snapshot rtc = {}, a = {}, b = {};
  const bool rtcGood = ESP.rtcUserMemoryRead(
      kRtcOffsetWords, reinterpret_cast<uint32_t*>(&rtc), sizeof(rtc)) && valid(rtc);
  const bool aGood = fsReady && readFile(kFileA, a);
  const bool bGood = fsReady && readFile(kFileB, b);
  state = {};
  state.magic = kMagic;
  state.schema = kSchema;
  if (aGood) state = a;
  if (bGood && (!aGood || smartplug_reliability::newer(b.sequence, state.sequence))) state = b;
  if (rtcGood && (!aGood && !bGood ||
      smartplug_reliability::newer(rtc.sequence, state.sequence))) state = rtc;
  ++state.bootId;
  checkpointOk = aGood || bGood;
  dirty = true;
  lastAttemptAt = millis();
  saveRtc();
}

void record(Event event, uint8_t detail) {
  state.entries[state.head] = {millis(), state.bootId,
                               static_cast<uint8_t>(event), detail};
  state.head = (state.head + 1U) % kCapacity;
  if (state.used < kCapacity) ++state.used;
  ++state.sequence;
  dirty = true;
  saveRtc();  // RAM-backed RTC: no flash write per event.
}

void tick() {
  if (!dirty || !fsReady || millis() - lastAttemptAt < kCheckpointMs) return;
  lastAttemptAt = millis();
  checkpointOk = checkpoint();
  if (checkpointOk) {
    dirty = false;
    lastCheckpointAt = millis();
  }
}

void clear() {
  state = {};
  state.magic = kMagic;
  state.schema = kSchema;
  saveRtc();
  dirty = false;
  checkpointOk = false;
}

uint8_t count() { return state.used; }
Entry newest(uint8_t index) {
  if (index >= state.used) return {};
  return state.entries[(state.head + kCapacity - 1U - index) % kCapacity];
}
bool checkpointHealthy() { return checkpointOk; }
uint32_t lastCheckpointAgeMs() {
  return lastCheckpointAt ? millis() - lastCheckpointAt : 0U;
}
}  // namespace smartplug_diag
