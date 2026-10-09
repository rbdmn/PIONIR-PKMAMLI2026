#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

#include "SmartPlugReliability.h"

// Portable representation of the temporary handoff used only when migrating
// cumulative energy from the legacy 64 KiB LittleFS layout to the 32 KiB
// layout. EEPROM I/O is deliberately kept outside this header so CRC and
// phase transitions can be host-tested without Arduino dependencies.
namespace smartplug_migration {

constexpr uint32_t kMagic = 0x53504D47UL;  // SPMG
constexpr uint16_t kVersion = 1U;

enum class Phase : uint16_t { kPrepared = 1U, kImported = 2U };

struct Record {
  uint8_t bytes[24];
};

static_assert(sizeof(Record) == 24U,
              "Migration handoff layout must remain byte-stable.");

constexpr size_t kMagicOffset = 0U;
constexpr size_t kVersionOffset = 4U;
constexpr size_t kPhaseOffset = 6U;
constexpr size_t kSequenceOffset = 8U;
constexpr size_t kEnergyOffset = 12U;
constexpr size_t kCrcOffset = 20U;

inline uint32_t recordCrc(const Record& record) {
  return smartplug_reliability::crc32(record.bytes, kCrcOffset);
}

inline uint32_t magic(const Record& record) {
  uint32_t value = 0U;
  std::memcpy(&value, record.bytes + kMagicOffset, sizeof(value));
  return value;
}

inline uint16_t version(const Record& record) {
  uint16_t value = 0U;
  std::memcpy(&value, record.bytes + kVersionOffset, sizeof(value));
  return value;
}

inline Phase phase(const Record& record) {
  uint16_t value = 0U;
  std::memcpy(&value, record.bytes + kPhaseOffset, sizeof(value));
  return static_cast<Phase>(value);
}

inline uint32_t energySequence(const Record& record) {
  uint32_t value = 0U;
  std::memcpy(&value, record.bytes + kSequenceOffset, sizeof(value));
  return value;
}

inline double energyWh(const Record& record) {
  double value = 0.0;
  std::memcpy(&value, record.bytes + kEnergyOffset, sizeof(value));
  return value;
}

inline uint32_t storedCrc(const Record& record) {
  uint32_t value = 0U;
  std::memcpy(&value, record.bytes + kCrcOffset, sizeof(value));
  return value;
}

inline Record makeRecord(const Phase recordPhase, const uint32_t sequence,
                         const double energy) {
  Record record = {};
  std::memcpy(record.bytes + kMagicOffset, &kMagic, sizeof(kMagic));
  std::memcpy(record.bytes + kVersionOffset, &kVersion, sizeof(kVersion));
  const uint16_t phaseValue = static_cast<uint16_t>(recordPhase);
  std::memcpy(record.bytes + kPhaseOffset, &phaseValue, sizeof(phaseValue));
  std::memcpy(record.bytes + kSequenceOffset, &sequence, sizeof(sequence));
  std::memcpy(record.bytes + kEnergyOffset, &energy, sizeof(energy));
  const uint32_t crc = recordCrc(record);
  std::memcpy(record.bytes + kCrcOffset, &crc, sizeof(crc));
  return record;
}

inline bool valid(const Record& record) {
  const Phase recordPhase = phase(record);
  return magic(record) == kMagic && version(record) == kVersion &&
         (recordPhase == Phase::kPrepared || recordPhase == Phase::kImported) &&
         smartplug_reliability::validEnergy(energyWh(record)) &&
         storedCrc(record) == recordCrc(record);
}

}  // namespace smartplug_migration
