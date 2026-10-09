#include "SmartPlugBL0940.h"

namespace smartplug_bl0940 {

namespace {

constexpr uint8_t kWriteCommand = 0xA8;
constexpr uint8_t kInitialization[][5] = {
    // Reset, enable user writes, select 50 Hz / 800 ms RMS update, configure
    // temperature/alarm handling, then select the fast-RMS threshold.
    {0x19, 0x5A, 0x5A, 0x5A, 0x38},
    {0x1A, 0x55, 0x00, 0x00, 0xF0},
    {0x18, 0x00, 0x10, 0x00, 0x37},
    {0x1B, 0xFF, 0x47, 0x00, 0xFE},
    {0x10, 0x1C, 0x18, 0x00, 0x1B},
};

}  // namespace

Driver::Driver(const uint8_t rxPin, const uint8_t txPin)
    : serial_(rxPin, txPin) {}

void Driver::begin() {
  // BL0940 UART is fixed at 4800 baud.  ESP8266's standard 8N1 receiver is
  // accepted by the IC even though its nominal format specifies 1.5 stop bits.
  serial_.begin(4800);
  // Match the established ESPHome BL0940 startup configuration before any
  // read request. Application code never owns these register commands.
  for (const auto& command : kInitialization) {
    serial_.write(kWriteCommand);
    serial_.write(command, sizeof(command));
    delay(1);
  }
  while (serial_.available() > 0) {
    static_cast<void>(serial_.read());
  }
}

bool Driver::requestSample(const unsigned long timeoutMs) {
  if (busy() || pendingResult_ != PollResult::kNone) {
    return false;
  }

  while (serial_.available() > 0) {
    static_cast<void>(serial_.read());
  }

  received_ = 0;
  timeoutMs_ = timeoutMs;
  startedAt_ = millis();
  state_ = State::kReceiving;

  // The command, packet address, framing and checksum are deliberately kept
  // inside this library; the SmartPlug application only requests a sample.
  serial_.write(bl0940_protocol::kReadCommand);
  serial_.write(bl0940_protocol::kFullPacketAddress);
  return true;
}

void Driver::tick() {
  if (!busy()) {
    return;
  }

  while (serial_.available() > 0 && received_ < bl0940_protocol::kPacketSize) {
    const int value = serial_.read();
    if (value >= 0) {
      packet_[received_++] = static_cast<uint8_t>(value);
    }
  }

  if (received_ == bl0940_protocol::kPacketSize) {
    Measurement measurement = {};
    if (!bl0940_protocol::decodePacket(packet_, measurement)) {
      finishInvalid();
      return;
    }

    pendingMeasurement_ = measurement;
    pendingResult_ = PollResult::kValid;
    state_ = State::kIdle;
    ++validPacketCount_;
    return;
  }

  if (millis() - startedAt_ >= timeoutMs_) {
    finishInvalid();
  }
}

Driver::PollResult Driver::takeSample(Measurement& measurement) {
  const PollResult result = pendingResult_;
  if (result == PollResult::kValid) {
    measurement = pendingMeasurement_;
  }
  pendingResult_ = PollResult::kNone;
  return result;
}

bool Driver::busy() const { return state_ == State::kReceiving; }

void Driver::finishInvalid() {
  state_ = State::kIdle;
  pendingResult_ = PollResult::kInvalid;
  ++invalidPacketCount_;
}

uint32_t Driver::validPacketCount() const { return validPacketCount_; }

uint32_t Driver::invalidPacketCount() const { return invalidPacketCount_; }

}  // namespace smartplug_bl0940
