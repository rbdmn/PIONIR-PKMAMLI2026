#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "SmartPlugReliability.h"

// Portable (no Arduino/filesystem) logic for "restore the last relay state when
// power returns". Host-tested; main.cpp only supplies LittleFS I/O and the clock.
//
// Direct mode: every settled relay change is saved; at boot the saved state is
// executed once after kRestoreDelayMs. Server mode does NOT use this record to
// drive the relay: the server reconciles with an ordinary signed relay command.
namespace smartplug_power_restore {

constexpr uint32_t kRestoreDelayMs = 3000U;   // let supply/Wi-Fi settle before switching a load
constexpr uint32_t kSaveDebounceMs = 1500U;   // avoid flash wear on rapid toggles

// Fixed layout: magic, schema, sequence, state (1=on, 0=off), 3 pad bytes, CRC32.
struct StateRecord { uint8_t bytes[20]; };
constexpr uint32_t kMagic = 0x53505231U;  // "SPR1"

inline StateRecord encode(uint32_t sequence, bool on) {
  StateRecord record = {};
  const uint32_t schema = 1U;
  std::memcpy(record.bytes, &kMagic, 4);
  std::memcpy(record.bytes + 4, &schema, 4);
  std::memcpy(record.bytes + 8, &sequence, 4);
  record.bytes[12] = on ? 1U : 0U;
  const uint32_t crc = smartplug_reliability::crc32(record.bytes, 16);
  std::memcpy(record.bytes + 16, &crc, 4);
  return record;
}

inline bool decode(const StateRecord& record, uint32_t& sequence, bool& on) {
  uint32_t magic, schema, expected;
  std::memcpy(&magic, record.bytes, 4);
  std::memcpy(&schema, record.bytes + 4, 4);
  std::memcpy(&expected, record.bytes + 16, 4);
  if (magic != kMagic || schema != 1U ||
      expected != smartplug_reliability::crc32(record.bytes, 16)) return false;
  if (record.bytes[12] > 1U || record.bytes[13] || record.bytes[14] || record.bytes[15]) return false;
  std::memcpy(&sequence, record.bytes + 8, 4);
  on = record.bytes[12] == 1U;
  return true;
}

// Which of two alternating slots to use after a power cut: a torn write only
// ever damages the slot being written, so the other one stays valid.
struct LoadResult {
  bool valid = false;
  bool on = false;
  uint32_t sequence = 0;
  int slot = -1;   // slot that holds the chosen record; the next write goes to the other one
};

inline LoadResult chooseNewest(bool validA, uint32_t seqA, bool onA,
                               bool validB, uint32_t seqB, bool onB) {
  LoadResult result;
  const int slot = smartplug_reliability::newestSlot(validA, seqA, validB, seqB);
  if (slot < 0) return result;
  result.valid = true;
  result.slot = slot;
  result.sequence = slot == 0 ? seqA : seqB;
  result.on = slot == 0 ? onA : onB;
  return result;
}

inline int nextSlot(int currentSlot) { return currentSlot == 0 ? 1 : 0; }

// Fail-safe: only a valid record that says ON turns the load on. A missing or
// corrupt record is treated as OFF.
inline bool shouldRestoreOn(bool recordValid, bool lastOn) { return recordValid && lastOn; }

// Restore executes at most once per boot, after the settle delay.
class RestoreGate {
 public:
  // Returns true exactly once, when the delay has elapsed and the caller should switch ON.
  bool due(uint32_t nowMs, bool restoreOn) {
    if (done_) return false;
    if (!restoreOn) { done_ = true; return false; }
    if (nowMs < kRestoreDelayMs) return false;  // millis() counts from boot; no wrap this early
    done_ = true;
    return true;
  }
  bool done() const { return done_; }
  void markDone() { done_ = true; }
  // The action was refused (e.g. relay cooldown) but may succeed later: allow another attempt.
  void retry() { done_ = false; }
 private:
  bool done_ = false;
};

// Writes only after the state has stayed unchanged for kSaveDebounceMs and differs from what is
// already stored.
class SaveDebouncer {
 public:
  void setPersisted(bool known, bool on) { persistedKnown_ = known; persistedOn_ = on; }
  // observedKnown=false (e.g. relay "unknown"/"transitioning") resets the stability timer.
  // Returns true when a save of `on` should be performed now.
  bool observe(bool observedKnown, bool on, uint32_t nowMs) {
    if (!observedKnown) { stable_ = false; return false; }
    if (!stable_ || on != candidateOn_) { stable_ = true; candidateOn_ = on; since_ = nowMs; return false; }
    if (persistedKnown_ && persistedOn_ == on) return false;
    return uint32_t(nowMs - since_) >= kSaveDebounceMs;
  }
  void saved(bool on) { persistedKnown_ = true; persistedOn_ = on; }
 private:
  bool persistedKnown_ = false;
  bool persistedOn_ = false;
  bool stable_ = false;
  bool candidateOn_ = false;
  uint32_t since_ = 0;
};

}  // namespace smartplug_power_restore
