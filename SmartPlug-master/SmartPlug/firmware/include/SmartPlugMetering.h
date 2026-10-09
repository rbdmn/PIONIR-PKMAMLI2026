#pragma once

#include <cstdint>
#include <cmath>

#include "Bl0940Protocol.h"

namespace smartplug_metering {

// Smoothing is applied only to instantaneous measurement channels. The CF
// counter remains the newest raw value because it is a monotonically changing
// counter, not an instantaneous sample.
class MovingAverage10 {
 public:
  bl0940_protocol::RawMeasurement update(
      const bl0940_protocol::RawMeasurement& sample) {
    if (count_ < kWindow) {
      ++count_;
    } else {
      subtract(samples_[next_]);
    }
    samples_[next_] = sample;
    add(sample);
    next_ = (next_ + 1U) % kWindow;

    bl0940_protocol::RawMeasurement average = sample;
    average.fastCurrentRms = static_cast<uint32_t>(fastCurrentSum_ / count_);
    average.currentRms = static_cast<uint32_t>(currentSum_ / count_);
    average.voltageRms = static_cast<uint32_t>(voltageSum_ / count_);
    average.activePower = static_cast<int32_t>(activePowerSum_ / count_);
    average.internalTemperature =
        static_cast<uint16_t>(internalTemperatureSum_ / count_);
    average.externalTemperature =
        static_cast<uint16_t>(externalTemperatureSum_ / count_);
    return average;
  }

 private:
  static constexpr uint8_t kWindow = 10;
  void add(const bl0940_protocol::RawMeasurement& s) {
    fastCurrentSum_ += s.fastCurrentRms; currentSum_ += s.currentRms;
    voltageSum_ += s.voltageRms; activePowerSum_ += s.activePower;
    internalTemperatureSum_ += s.internalTemperature;
    externalTemperatureSum_ += s.externalTemperature;
  }
  void subtract(const bl0940_protocol::RawMeasurement& s) {
    fastCurrentSum_ -= s.fastCurrentRms; currentSum_ -= s.currentRms;
    voltageSum_ -= s.voltageRms; activePowerSum_ -= s.activePower;
    internalTemperatureSum_ -= s.internalTemperature;
    externalTemperatureSum_ -= s.externalTemperature;
  }
  bl0940_protocol::RawMeasurement samples_[kWindow] = {};
  uint8_t count_ = 0;
  uint8_t next_ = 0;
  uint64_t fastCurrentSum_ = 0, currentSum_ = 0, voltageSum_ = 0;
  int64_t activePowerSum_ = 0;
  uint32_t internalTemperatureSum_ = 0, externalTemperatureSum_ = 0;
};

struct Calibration {
  float voltsPerCode = 0.0F;
  float ampsPerCode = 0.0F;
  float wattsPerCode = 0.0F;
  // Energy per counter increment: reference Wh delta / CF-count delta over
  // the same measured interval. Never use an absolute lifetime reference.
  float wattHoursPerCf = 0.0F;
};

struct ElectricalSample {
  bool calibrated = false;
  float voltageV = 0.0F;
  float currentA = 0.0F;
  float activePowerW = 0.0F;
  float apparentPowerVa = 0.0F;
  float powerFactor = 0.0F;
  // Compatibility name; this is cumulative when restored from storage/server.
  double energyWhSinceBoot = 0.0;
};

inline bool isCalibrated(const Calibration& calibration) {
  return std::isfinite(calibration.voltsPerCode) && calibration.voltsPerCode > 0.0F &&
         std::isfinite(calibration.ampsPerCode) && calibration.ampsPerCode > 0.0F &&
         std::isfinite(calibration.wattsPerCode) && calibration.wattsPerCode > 0.0F &&
         std::isfinite(calibration.wattHoursPerCf) && calibration.wattHoursPerCf > 0.0F;
}

class EnergyIntegrator {
 public:
  ElectricalSample update(const bl0940_protocol::RawMeasurement& raw,
                          const Calibration& calibration,
                          const uint32_t sampleAtMs,
                          const uint32_t maxSampleGapMs = 5000U) {
    ElectricalSample result = {};
    result.energyWhSinceBoot = energyWhSinceBoot_;
    result.calibrated = isCalibrated(calibration);
    if (!result.calibrated) {
      hasPreviousSample_ = false;
      return result;
    }

    result.voltageV = static_cast<float>(raw.voltageRms) * calibration.voltsPerCode;
    result.currentA = static_cast<float>(raw.currentRms) * calibration.ampsPerCode;
    result.activePowerW = static_cast<float>(raw.activePower) * calibration.wattsPerCode;
    result.apparentPowerVa = result.voltageV * result.currentA;
    if (result.apparentPowerVa > 0.001F) {
      result.powerFactor = result.activePowerW / result.apparentPowerVa;
      if (result.powerFactor > 1.0F) result.powerFactor = 1.0F;
      if (result.powerFactor < -1.0F) result.powerFactor = -1.0F;
    }

    const bool sameScale = calibration.wattHoursPerCf == previousWattHoursPerCf_ &&
                           calibration.wattsPerCode == previousWattsPerCode_;
    if (hasPreviousSample_ && sameScale) {
      const uint32_t elapsedMs = sampleAtMs - previousSampleAtMs_;
      // A repeated timestamp is not a new interval. Keep the old baseline so
      // a later sample can account for the counter delta exactly once.
      if (elapsedMs == 0U) return result;
      if (elapsedMs <= maxSampleGapMs) {
        // CF_CNT is the sole energy source. Adding time-integrated power
        // between CF changes counts the same energy again at the next edge.
        const uint32_t cfDelta = (raw.cfCount - previousCfCount_) & kCounterMask;
        const double deltaWh = static_cast<double>(cfDelta) * calibration.wattHoursPerCf;
        // Bound the advance using the signed 24-bit power register's full
        // scale, not the instantaneous/smoothed load (which can lag a step).
        // 2x headroom plus two counts allows conversion and quantization error.
        // This is a counter-consistency guard, NOT an electrical load rating.
        const double maximumWh = 2.0 * 8388608.0 * calibration.wattsPerCode *
                                 static_cast<double>(elapsedMs) / 3600000.0 +
                                 2.0 * calibration.wattHoursPerCf;
        if (raw.cfCount <= kCounterMask && cfDelta < kCounterHalfRange &&
            deltaWh <= maximumWh) {
          energyWhSinceBoot_ += deltaWh;
        }
        // An implausible forward/backward jump (including a sensor reset)
        // contributes nothing; rebase below and retain the accumulated total.
      }
    }
    // First sample, scale changes and gaps longer than the continuity window
    // establish a baseline only. No unknown interval is estimated from power.
    previousCfCount_ = raw.cfCount & kCounterMask;
    previousWattHoursPerCf_ = calibration.wattHoursPerCf;
    previousWattsPerCode_ = calibration.wattsPerCode;
    previousSampleAtMs_ = sampleAtMs;
    hasPreviousSample_ = raw.cfCount <= kCounterMask;
    result.energyWhSinceBoot = energyWhSinceBoot_;
    return result;
  }

  void reset() {
    hasPreviousSample_ = false;
    previousSampleAtMs_ = 0;
    energyWhSinceBoot_ = 0.0;
    previousCfCount_ = 0;
    previousWattHoursPerCf_ = 0.0F;
    previousWattsPerCode_ = 0.0F;
  }

  // The cumulative counter is intentionally separate from the BL0940 CF
  // snapshot.  A power cycle restarts the chip's CF counter, so restoration
  // starts a fresh integration interval on the first valid sample.
  double energyWh() const { return energyWhSinceBoot_; }

  // Server high-water mark adjusts only the accumulated total. Keep the CF
  // baseline so a reconnect cannot discard the next valid sensor interval.
  bool syncEnergyWh(const double energyWh) {
    if (!std::isfinite(energyWh) || energyWh <= energyWhSinceBoot_) return false;
    energyWhSinceBoot_ = energyWh;
    return true;
  }

  void restoreEnergyWh(const double energyWh) {
    if (!std::isfinite(energyWh) || energyWh < 0.0) return;
    energyWhSinceBoot_ = energyWh;
    hasPreviousSample_ = false;
  }

 private:
  static constexpr uint32_t kCounterMask = 0x00FFFFFFUL;
  static constexpr uint32_t kCounterHalfRange = 0x00800000UL;
  bool hasPreviousSample_ = false;
  uint32_t previousSampleAtMs_ = 0;
  // Double avoids losing small valid increments as the cumulative total grows.
  double energyWhSinceBoot_ = 0.0;
  uint32_t previousCfCount_ = 0;
  float previousWattHoursPerCf_ = 0.0F;
  float previousWattsPerCode_ = 0.0F;
};

class StandbyDetector {
 public:
  void update(const ElectricalSample& sample, const float thresholdW,
              const uint32_t durationMs, const uint32_t nowMs) {
    standbyDetected_ = false;
    if (!sample.calibrated || thresholdW < 0.0F || durationMs == 0U) {
      hasCandidate_ = false;
      return;
    }
    const float magnitudeW = sample.activePowerW >= 0.0F
                                 ? sample.activePowerW
                                 : -sample.activePowerW;
    if (magnitudeW > thresholdW) {
      hasCandidate_ = false;
      return;
    }
    if (!hasCandidate_) {
      hasCandidate_ = true;
      candidateSinceMs_ = nowMs;
      return;
    }
    standbyDetected_ = nowMs - candidateSinceMs_ >= durationMs;
  }

  bool detected() const { return standbyDetected_; }
  bool pending() const { return hasCandidate_ && !standbyDetected_; }

 private:
  bool hasCandidate_ = false;
  bool standbyDetected_ = false;
  uint32_t candidateSinceMs_ = 0;
};

}  // namespace smartplug_metering
