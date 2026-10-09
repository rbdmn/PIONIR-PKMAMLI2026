#pragma once
#include <cmath>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cstddef>

// Portable, host-tested primitives. No Arduino or filesystem dependency.
namespace smartplug_reliability {
constexpr double kMaximumEnergyWh = 1.0e12;
constexpr uint32_t kFreshSampleMs = 5000U;
inline bool validEnergy(double value) {
  return std::isfinite(value) && value >= 0.0 && value <= kMaximumEnergyWh;
}
inline bool fresh(bool hasSample, uint32_t now, uint32_t captured) {
  return hasSample && uint32_t(now - captured) <= kFreshSampleMs;
}
inline uint32_t crc32(const uint8_t* bytes, size_t size) {
  uint32_t crc = 0xffffffffU;
  for (size_t i = 0; i < size; ++i) {
    crc ^= bytes[i];
    for (uint8_t b = 0; b < 8; ++b)
      crc = (crc >> 1) ^ ((crc & 1U) ? 0xedb88320U : 0U);
  }
  return ~crc;
}
// Fixed byte layout: magic, schema, sequence, binary64 energy, CRC32.
// ESP8266 and host tests are little endian. No struct padding is persisted.
struct EnergyRecord { uint8_t bytes[24]; };
static_assert(sizeof(double) == 8, "Energy record requires binary64 double");
inline EnergyRecord energyRecord(uint32_t sequence, double energy) {
  EnergyRecord record = {};
  const uint32_t magic = 0x53504532U, schema = 1U;
  std::memcpy(record.bytes, &magic, 4);
  std::memcpy(record.bytes + 4, &schema, 4);
  std::memcpy(record.bytes + 8, &sequence, 4);
  std::memcpy(record.bytes + 12, &energy, 8);
  const uint32_t crc = crc32(record.bytes, 20);
  std::memcpy(record.bytes + 20, &crc, 4);
  return record;
}
inline bool decode(const EnergyRecord& record, uint32_t& sequence, double& energy) {
  uint32_t magic, schema, expected;
  std::memcpy(&magic, record.bytes, 4);
  std::memcpy(&schema, record.bytes + 4, 4);
  std::memcpy(&expected, record.bytes + 20, 4);
  if (magic != 0x53504532U || schema != 1U || expected != crc32(record.bytes, 20)) return false;
  std::memcpy(&sequence, record.bytes + 8, 4);
  std::memcpy(&energy, record.bytes + 12, 8);
  return validEnergy(energy);
}
inline bool newer(uint32_t candidate, uint32_t previous) {
  const uint32_t delta = candidate - previous;
  return delta != 0 && delta < 0x80000000U;
}
inline int newestSlot(bool validA, uint32_t a, bool validB, uint32_t b) {
  if (!validA) return validB ? 1 : -1;
  return validB && newer(b, a) ? 1 : 0;
}
inline bool positiveNumber(const char* input, double& result) {
  if (!input || !*input) return false;
  char* end = nullptr;
  result = std::strtod(input, &end);
  return end != input && *end == '\0' && std::isfinite(result) && result > 0;
}
// Only identical, consecutive valid observations resolve the boot policy.
// This chooses a command, not a physical-contact feedback state.
class BootRelayDecision {
 public:
  void invalid() { samples_ = 0; }
  int observe(bool currentPresent) {
    if (!samples_ || currentPresent != present_) { present_ = currentPresent; samples_ = 1; }
    else if (samples_ < 3) ++samples_;
    return samples_ >= 3 ? (present_ ? 1 : 0) : -1;
  }
 private:
  uint8_t samples_ = 0;
  bool present_ = false;
};
}  // namespace smartplug_reliability
