#pragma once

#include <cstddef>
#include <cstdint>

namespace bl0940_protocol {

constexpr std::size_t kPacketSize = 35;
constexpr uint8_t kReadCommand = 0x58;
constexpr uint8_t kFullPacketAddress = 0xAA;
constexpr uint8_t kPacketHeader = 0x55;

struct RawMeasurement {
  uint32_t fastCurrentRms;
  uint32_t currentRms;
  uint32_t voltageRms;
  int32_t activePower;
  uint32_t cfCount;
  uint16_t internalTemperature;
  uint16_t externalTemperature;
};

inline uint32_t readU24Le(const uint8_t* bytes) {
  return static_cast<uint32_t>(bytes[0]) |
         (static_cast<uint32_t>(bytes[1]) << 8U) |
         (static_cast<uint32_t>(bytes[2]) << 16U);
}

inline int32_t signExtend24(const uint32_t value) {
  const uint32_t masked = value & 0x00FFFFFFUL;
  return (masked & 0x00800000UL) != 0U
             ? static_cast<int32_t>(masked | 0xFF000000UL)
             : static_cast<int32_t>(masked);
}

inline uint8_t packetChecksum(const uint8_t* packet) {
  uint16_t sum = kReadCommand;
  for (std::size_t offset = 0; offset < kPacketSize - 1; ++offset) {
    sum = static_cast<uint16_t>(sum + packet[offset]);
  }
  return static_cast<uint8_t>(~static_cast<uint8_t>(sum & 0xFFU));
}

inline bool decodePacket(const uint8_t* packet, RawMeasurement& result) {
  if (packet == nullptr || packet[0] != kPacketHeader ||
      packetChecksum(packet) != packet[kPacketSize - 1]) {
    return false;
  }

  result.fastCurrentRms = readU24Le(&packet[1]);
  result.currentRms = readU24Le(&packet[4]);
  result.voltageRms = readU24Le(&packet[10]);
  result.activePower = signExtend24(readU24Le(&packet[16]));
  result.cfCount = readU24Le(&packet[22]);
  result.internalTemperature =
      static_cast<uint16_t>(packet[28]) |
      static_cast<uint16_t>(static_cast<uint16_t>(packet[29]) << 8U);
  result.externalTemperature =
      static_cast<uint16_t>(packet[31]) |
      static_cast<uint16_t>(static_cast<uint16_t>(packet[32]) << 8U);
  return true;
}

}  // namespace bl0940_protocol
