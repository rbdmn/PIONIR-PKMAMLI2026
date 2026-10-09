#pragma once

#include <Arduino.h>
#include <SoftwareSerial.h>

#include <SmartPlugBL0940Protocol.h>

// PlatformIO library boundary for the BL0940 UART protocol.  Application code
// consumes this driver and never writes protocol bytes or decodes frames.
namespace smartplug_bl0940 {

using Measurement = bl0940_protocol::RawMeasurement;

class Driver {
 public:
  enum class PollResult : uint8_t { kNone, kValid, kInvalid };

  Driver(uint8_t rxPin, uint8_t txPin);

  void begin();
  bool requestSample(unsigned long timeoutMs = 180UL);
  void tick();
  PollResult takeSample(Measurement& measurement);
  bool busy() const;
  uint32_t validPacketCount() const;
  uint32_t invalidPacketCount() const;

 private:
  enum class State : uint8_t { kIdle, kReceiving };

  void finishInvalid();

  SoftwareSerial serial_;
  State state_ = State::kIdle;
  uint8_t packet_[bl0940_protocol::kPacketSize] = {};
  size_t received_ = 0;
  unsigned long startedAt_ = 0;
  unsigned long timeoutMs_ = 0;
  PollResult pendingResult_ = PollResult::kNone;
  Measurement pendingMeasurement_ = {};
  uint32_t validPacketCount_ = 0;
  uint32_t invalidPacketCount_ = 0;
};

}  // namespace smartplug_bl0940
