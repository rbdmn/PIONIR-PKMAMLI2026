#pragma once

#include <cstdint>

#include "SmartPlugMetering.h"

namespace smartplug_anomaly {

enum class VoltageState : uint8_t {
  kUnavailable,
  kNormal,
  kUndervoltage,
  kOvervoltage,
};

class VoltageDetector {
 public:
  void update(const smartplug_metering::ElectricalSample& sample,
              const float underVoltageV, const float overVoltageV) {
    if (!sample.calibrated || underVoltageV <= 0.0F ||
        overVoltageV <= underVoltageV) {
      state_ = VoltageState::kUnavailable;
      return;
    }
    if (sample.voltageV < underVoltageV) {
      state_ = VoltageState::kUndervoltage;
    } else if (sample.voltageV > overVoltageV) {
      state_ = VoltageState::kOvervoltage;
    } else {
      state_ = VoltageState::kNormal;
    }
  }

  VoltageState state() const { return state_; }

  const char* stateText() const {
    switch (state_) {
      case VoltageState::kNormal:
        return "normal";
      case VoltageState::kUndervoltage:
        return "undervoltage";
      case VoltageState::kOvervoltage:
        return "overvoltage";
      case VoltageState::kUnavailable:
      default:
        return "unavailable_not_calibrated";
    }
  }

 private:
  VoltageState state_ = VoltageState::kUnavailable;
};

}  // namespace smartplug_anomaly
