#include <unity.h>

#include <cstdint>
#include <cmath>
#include <limits>

#ifdef ARDUINO
#include <Arduino.h>
#endif

#include "Bl0940Protocol.h"
#include "SmartPlugAnomaly.h"
#include "SmartPlugMetering.h"

void setUp() {}
void tearDown() {}

namespace {

void setU24(uint8_t* destination, const uint32_t value) {
  destination[0] = static_cast<uint8_t>(value & 0xFFU);
  destination[1] = static_cast<uint8_t>((value >> 8U) & 0xFFU);
  destination[2] = static_cast<uint8_t>((value >> 16U) & 0xFFU);
}

void makeValidPacket(uint8_t* packet) {
  for (std::size_t index = 0; index < bl0940_protocol::kPacketSize; ++index) {
    packet[index] = 0;
  }
  packet[0] = bl0940_protocol::kPacketHeader;
  setU24(&packet[1], 0x010203UL);
  setU24(&packet[4], 0x040506UL);
  setU24(&packet[10], 0x070809UL);
  setU24(&packet[16], 0xFFFFFEUL);
  setU24(&packet[22], 0x0A0B0CUL);
  packet[28] = 0x34;
  packet[29] = 0x12;
  packet[31] = 0x78;
  packet[32] = 0x56;
  packet[34] = bl0940_protocol::packetChecksum(packet);
}

void testDecodeValidPacket() {
  uint8_t packet[bl0940_protocol::kPacketSize] = {};
  makeValidPacket(packet);
  bl0940_protocol::RawMeasurement result = {};

  TEST_ASSERT_TRUE(bl0940_protocol::decodePacket(packet, result));
  TEST_ASSERT_EQUAL_UINT32(0x010203UL, result.fastCurrentRms);
  TEST_ASSERT_EQUAL_UINT32(0x040506UL, result.currentRms);
  TEST_ASSERT_EQUAL_UINT32(0x070809UL, result.voltageRms);
  TEST_ASSERT_EQUAL_INT32(-2, result.activePower);
  TEST_ASSERT_EQUAL_UINT32(0x0A0B0CUL, result.cfCount);
  TEST_ASSERT_EQUAL_UINT16(0x1234U, result.internalTemperature);
  TEST_ASSERT_EQUAL_UINT16(0x5678U, result.externalTemperature);
}

void testRejectsBadChecksum() {
  uint8_t packet[bl0940_protocol::kPacketSize] = {};
  makeValidPacket(packet);
  packet[10] ^= 0x01U;
  bl0940_protocol::RawMeasurement result = {};
  TEST_ASSERT_FALSE(bl0940_protocol::decodePacket(packet, result));
}

void testRejectsBadHeader() {
  uint8_t packet[bl0940_protocol::kPacketSize] = {};
  makeValidPacket(packet);
  packet[0] = 0x00;
  packet[34] = bl0940_protocol::packetChecksum(packet);
  bl0940_protocol::RawMeasurement result = {};
  TEST_ASSERT_FALSE(bl0940_protocol::decodePacket(packet, result));
}

void testUncalibratedSampleDoesNotPublishElectricalUnits() {
  bl0940_protocol::RawMeasurement raw = {};
  raw.voltageRms = 100;
  raw.currentRms = 2;
  raw.activePower = 50;
  smartplug_metering::EnergyIntegrator integrator;
  const smartplug_metering::ElectricalSample result = integrator.update(raw, {}, 0);

  TEST_ASSERT_FALSE(result.calibrated);
  TEST_ASSERT_EQUAL_FLOAT(0.0F, result.energyWhSinceBoot);
}

void testCalibratedSampleCalculatesPfAndEnergySinceBoot() {
  bl0940_protocol::RawMeasurement raw = {};
  raw.voltageRms = 10;
  raw.currentRms = 2;
  raw.activePower = 15;
  const smartplug_metering::Calibration calibration = {1.0F, 1.0F, 1.0F, 1.0F};
  smartplug_metering::EnergyIntegrator integrator;

  const smartplug_metering::ElectricalSample first = integrator.update(raw, calibration, 0);
  raw.cfCount = 15;
  const smartplug_metering::ElectricalSample second = integrator.update(raw, calibration, 1000UL);

  TEST_ASSERT_TRUE(first.calibrated);
  TEST_ASSERT_FLOAT_WITHIN(0.0001F, 20.0F, second.apparentPowerVa);
  TEST_ASSERT_FLOAT_WITHIN(0.0001F, 0.75F, second.powerFactor);
  TEST_ASSERT_FLOAT_WITHIN(0.0001F, 15.0F, second.energyWhSinceBoot);
}

void testStandbyRequiresCalibratedLowPowerDuration() {
  smartplug_metering::ElectricalSample sample = {};
  sample.calibrated = true;
  sample.activePowerW = 1.0F;
  smartplug_metering::StandbyDetector detector;

  detector.update(sample, 2.0F, 300000UL, 0);
  TEST_ASSERT_TRUE(detector.pending());
  TEST_ASSERT_FALSE(detector.detected());
  detector.update(sample, 2.0F, 300000UL, 300000UL);
  TEST_ASSERT_TRUE(detector.detected());
}

void testEnergyDoesNotBridgeLongMeterGap() {
  bl0940_protocol::RawMeasurement raw = {};
  raw.voltageRms = 10;
  raw.currentRms = 2;
  raw.activePower = 15;
  const smartplug_metering::Calibration calibration = {1.0F, 1.0F, 1.0F, 1.0F};
  smartplug_metering::EnergyIntegrator integrator;

  integrator.update(raw, calibration, 0, 5000U);
  raw.cfCount = 3;
  const smartplug_metering::ElectricalSample afterGap =
      integrator.update(raw, calibration, 6000U, 5000U);

  TEST_ASSERT_FLOAT_WITHIN(0.0001F, 0.0F, afterGap.energyWhSinceBoot);
  raw.cfCount = 4;
  TEST_ASSERT_FLOAT_WITHIN(0.0001F, 1.0F,
      integrator.update(raw, calibration, 6500U).energyWhSinceBoot);
}

void testNegativePowerDoesNotDecrementConsumptionEnergy() {
  bl0940_protocol::RawMeasurement raw = {};
  raw.voltageRms = 10;
  raw.currentRms = 2;
  raw.activePower = -15;
  const smartplug_metering::Calibration calibration = {1.0F, 1.0F, 1.0F, 1.0F};
  smartplug_metering::EnergyIntegrator integrator;

  integrator.update(raw, calibration, 0, 5000U);
  const smartplug_metering::ElectricalSample second =
      integrator.update(raw, calibration, 1000U, 5000U);

  TEST_ASSERT_FLOAT_WITHIN(0.0001F, -15.0F, second.activePowerW);
  TEST_ASSERT_FLOAT_WITHIN(0.0001F, 0.0F, second.energyWhSinceBoot);
}

void testEnergyHandlesMillisRollover() {
  bl0940_protocol::RawMeasurement raw = {};
  raw.voltageRms = 10;
  raw.currentRms = 2;
  raw.activePower = 15;
  const smartplug_metering::Calibration calibration = {1.0F, 1.0F, 1.0F, 1.0F};
  smartplug_metering::EnergyIntegrator integrator;

  integrator.update(raw, calibration, 0xFFFFFF00UL, 5000U);
  raw.cfCount = 2;
  const smartplug_metering::ElectricalSample afterRollover =
      integrator.update(raw, calibration, 0x000000F0UL, 5000U);

  TEST_ASSERT_FLOAT_WITHIN(0.0001F, 2.0F,
                           afterRollover.energyWhSinceBoot);
}

void testEnergyRestorePreservesCumulativeValueAndStartsFreshInterval() {
  bl0940_protocol::RawMeasurement raw = {};
  raw.voltageRms = 10;
  raw.currentRms = 2;
  raw.activePower = 15;
  const smartplug_metering::Calibration calibration = {1.0F, 1.0F, 1.0F, 1.0F};
  smartplug_metering::EnergyIntegrator integrator;

  integrator.restoreEnergyWh(123.5F);
  raw.cfCount = 100;
  const smartplug_metering::ElectricalSample first =
      integrator.update(raw, calibration, 1000U, 5000U);
  raw.cfCount = 101;
  const smartplug_metering::ElectricalSample second =
      integrator.update(raw, calibration, 2000U, 5000U);

  TEST_ASSERT_FLOAT_WITHIN(0.0001F, 123.5F, first.energyWhSinceBoot);
  TEST_ASSERT_FLOAT_WITHIN(0.0001F,
                           124.5F,
                           second.energyWhSinceBoot);
  TEST_ASSERT_FLOAT_WITHIN(0.0001F,
                           second.energyWhSinceBoot,
                           integrator.energyWh());
}

void testEnergyDoesNotDoubleCountPowerBetweenCfUpdates() {
  bl0940_protocol::RawMeasurement raw = {};
  raw.activePower = 3600;
  const smartplug_metering::Calibration calibration = {1, 1, 1, 1};
  smartplug_metering::EnergyIntegrator integrator;
  integrator.update(raw, calibration, 0);
  TEST_ASSERT_EQUAL_FLOAT(0, integrator.update(raw, calibration, 500).energyWhSinceBoot);
  raw.cfCount = 1;
  TEST_ASSERT_EQUAL_FLOAT(1, integrator.update(raw, calibration, 1000).energyWhSinceBoot);
  TEST_ASSERT_EQUAL_FLOAT(1, integrator.update(raw, calibration, 1500).energyWhSinceBoot);
  raw.cfCount = 2;
  TEST_ASSERT_EQUAL_FLOAT(2, integrator.update(raw, calibration, 2000).energyWhSinceBoot);
}

void testEnergyMatchesCounterAcrossDifferentPollingRates() {
  // 3600 W with 1 Wh/CF: each second contains exactly one counter increment.
  const smartplug_metering::Calibration calibration = {1, 1, 1, 1};
  const uint32_t periods[] = {100U, 500U, 1000U, 2500U};
  for (const uint32_t period : periods) {
    smartplug_metering::EnergyIntegrator integrator;
    bl0940_protocol::RawMeasurement raw = {};
    raw.activePower = 3600;
    for (uint32_t ms = 0; ms <= 10000; ms += period) {
      raw.cfCount = ms / 1000;
      integrator.update(raw, calibration, ms);
    }
    TEST_ASSERT_FLOAT_WITHIN(0.0001F, 10.0F, integrator.energyWh());
  }
}

void testEnergyCounterRolloverAddsOnlyWrappedDelta() {
  bl0940_protocol::RawMeasurement raw = {};
  const smartplug_metering::Calibration calibration = {1, 1, 1, 0.25F};
  smartplug_metering::EnergyIntegrator integrator;
  raw.cfCount = 0x00FFFFFEUL;
  integrator.update(raw, calibration, 0);
  raw.cfCount = 1;
  TEST_ASSERT_EQUAL_FLOAT(0.75F,
      integrator.update(raw, calibration, 500).energyWhSinceBoot);
}

void testEnergyCounterResetRebasesWithoutHugeJump() {
  bl0940_protocol::RawMeasurement raw = {};
  const smartplug_metering::Calibration calibration = {1, 1, 1, 0.25F};
  smartplug_metering::EnergyIntegrator integrator;
  integrator.restoreEnergyWh(123);
  raw.cfCount = 1000;
  integrator.update(raw, calibration, 0);
  raw.cfCount = 0;
  TEST_ASSERT_EQUAL_FLOAT(123, integrator.update(raw, calibration, 500).energyWhSinceBoot);
  raw.cfCount = 2;
  TEST_ASSERT_EQUAL_FLOAT(123.5F, integrator.update(raw, calibration, 1000).energyWhSinceBoot);
}

void testEnergyRejectsImpossibleForwardCounterJump() {
  bl0940_protocol::RawMeasurement raw = {};
  const smartplug_metering::Calibration calibration = {1, 1, 0.003F, 0.3F};
  smartplug_metering::EnergyIntegrator integrator;
  raw.cfCount = 100;
  integrator.update(raw, calibration, 0);
  raw.cfCount = 1000000;
  TEST_ASSERT_EQUAL_FLOAT(0, integrator.update(raw, calibration, 500).energyWhSinceBoot);
  raw.cfCount = 101;  // Recovery from a corrupt-but-checksummed sample.
  TEST_ASSERT_EQUAL_FLOAT(0, integrator.update(raw, calibration, 1000).energyWhSinceBoot);
  raw.cfCount = 102;
  TEST_ASSERT_FLOAT_WITHIN(0.00001F, 0.3F,
      integrator.update(raw, calibration, 1500).energyWhSinceBoot);
}

void testEnergyDoesNotInventConsumptionWhenCfStaysZero() {
  bl0940_protocol::RawMeasurement raw = {};
  raw.activePower = 10;
  const smartplug_metering::Calibration calibration = {1, 1, 1, 1};
  smartplug_metering::EnergyIntegrator integrator;
  for (uint32_t ms = 0; ms <= 10000; ms += 500) integrator.update(raw, calibration, ms);
  TEST_ASSERT_EQUAL_FLOAT(0, integrator.energyWh());
}

void testEnergyCalibrationChangeStartsFreshBaseline() {
  bl0940_protocol::RawMeasurement raw = {};
  smartplug_metering::Calibration calibration = {1, 1, 1, 1};
  smartplug_metering::EnergyIntegrator integrator;
  integrator.update(raw, calibration, 0);
  raw.cfCount = 1;
  integrator.update(raw, calibration, 500);
  calibration.wattHoursPerCf = 2;
  raw.cfCount = 2;
  TEST_ASSERT_EQUAL_FLOAT(1, integrator.update(raw, calibration, 1000).energyWhSinceBoot);
  raw.cfCount = 3;
  TEST_ASSERT_EQUAL_FLOAT(3, integrator.update(raw, calibration, 1500).energyWhSinceBoot);
}

void testEnergyInvalidCalibrationPreservesTotalAndRebases() {
  bl0940_protocol::RawMeasurement raw = {};
  smartplug_metering::Calibration calibration = {1, 1, 1, 1};
  smartplug_metering::EnergyIntegrator integrator;
  integrator.restoreEnergyWh(10);
  integrator.update(raw, calibration, 0);
  calibration.wattHoursPerCf = std::numeric_limits<float>::quiet_NaN();
  raw.cfCount = 100;
  TEST_ASSERT_FALSE(integrator.update(raw, calibration, 500).calibrated);
  TEST_ASSERT_EQUAL_FLOAT(10, integrator.energyWh());
  calibration.wattHoursPerCf = 1;
  TEST_ASSERT_EQUAL_FLOAT(10, integrator.update(raw, calibration, 1000).energyWhSinceBoot);
  raw.cfCount = 101;
  TEST_ASSERT_EQUAL_FLOAT(11, integrator.update(raw, calibration, 1500).energyWhSinceBoot);
}

void testEnergyRejectsInvalidRestoreWithoutLosingTotal() {
  smartplug_metering::EnergyIntegrator integrator;
  integrator.restoreEnergyWh(42);
  integrator.restoreEnergyWh(-1);
  TEST_ASSERT_EQUAL_FLOAT(42, integrator.energyWh());
  integrator.restoreEnergyWh(std::numeric_limits<float>::infinity());
  TEST_ASSERT_EQUAL_FLOAT(42, integrator.energyWh());
  integrator.restoreEnergyWh(std::numeric_limits<float>::quiet_NaN());
  TEST_ASSERT_EQUAL_FLOAT(42, integrator.energyWh());
}

void testEnergyDuplicateTimestampDoesNotConsumeCounterDelta() {
  bl0940_protocol::RawMeasurement raw = {};
  const smartplug_metering::Calibration calibration = {1, 1, 1, 1};
  smartplug_metering::EnergyIntegrator integrator;
  integrator.update(raw, calibration, 1000);
  raw.cfCount = 1;
  TEST_ASSERT_EQUAL_FLOAT(0, integrator.update(raw, calibration, 1000).energyWhSinceBoot);
  raw.cfCount = 2;
  TEST_ASSERT_EQUAL_FLOAT(2, integrator.update(raw, calibration, 1500).energyWhSinceBoot);
}

void testEnergyRequiresFiniteCompleteCalibration() {
  smartplug_metering::Calibration calibration = {1, 1, 1, 0};
  TEST_ASSERT_FALSE(smartplug_metering::isCalibrated(calibration));
  calibration.wattHoursPerCf = 1;
  TEST_ASSERT_TRUE(smartplug_metering::isCalibrated(calibration));
  calibration.wattsPerCode = std::numeric_limits<float>::infinity();
  TEST_ASSERT_FALSE(smartplug_metering::isCalibrated(calibration));
}

void testEnergyKeepsSmallIncrementsAtLargeTotal() {
  bl0940_protocol::RawMeasurement raw = {};
  const smartplug_metering::Calibration calibration = {1, 1, 1, 0.001F};
  smartplug_metering::EnergyIntegrator integrator;
  integrator.restoreEnergyWh(1000000);
  integrator.update(raw, calibration, 0);
  for (uint32_t i = 1; i <= 100; ++i) {
    raw.cfCount = i;
    integrator.update(raw, calibration, i * 500);
  }
  TEST_ASSERT_TRUE(std::fabs(integrator.energyWh() - 1000000.1) < 0.000001);
}

void testMovingAverageDoesNotSmoothCumulativeCounter() {
  bl0940_protocol::RawMeasurement raw = {};
  smartplug_metering::MovingAverage10 average;
  raw.activePower = 10;
  raw.cfCount = 0x00FFFFFFUL;
  average.update(raw);
  raw.activePower = 20;
  raw.cfCount = 0;
  const auto sample = average.update(raw);
  TEST_ASSERT_EQUAL_INT32(15, sample.activePower);
  TEST_ASSERT_EQUAL_UINT32(0, sample.cfCount);
}

void testStandbyResetsWhenPowerExceedsThreshold() {
  smartplug_metering::ElectricalSample lowPower = {};
  lowPower.calibrated = true;
  lowPower.activePowerW = 1.0F;
  smartplug_metering::ElectricalSample highPower = lowPower;
  highPower.activePowerW = 3.0F;
  smartplug_metering::StandbyDetector detector;

  detector.update(lowPower, 2.0F, 300000UL, 0);
  detector.update(highPower, 2.0F, 300000UL, 1000U);

  TEST_ASSERT_FALSE(detector.pending());
  TEST_ASSERT_FALSE(detector.detected());
}

void testVoltageAnomalyRequiresCalibrationAndClassifiesBounds() {
  smartplug_anomaly::VoltageDetector detector;
  smartplug_metering::ElectricalSample sample = {};

  detector.update(sample, 198.0F, 242.0F);
  TEST_ASSERT_EQUAL_STRING("unavailable_not_calibrated", detector.stateText());

  sample.calibrated = true;
  sample.voltageV = 197.9F;
  detector.update(sample, 198.0F, 242.0F);
  TEST_ASSERT_EQUAL_STRING("undervoltage", detector.stateText());
  sample.voltageV = 220.0F;
  detector.update(sample, 198.0F, 242.0F);
  TEST_ASSERT_EQUAL_STRING("normal", detector.stateText());
  sample.voltageV = 242.1F;
  detector.update(sample, 198.0F, 242.0F);
  TEST_ASSERT_EQUAL_STRING("overvoltage", detector.stateText());
}

}  // namespace

int runProtocolAndMeteringTests() {
  UNITY_BEGIN();
  RUN_TEST(testDecodeValidPacket);
  RUN_TEST(testRejectsBadChecksum);
  RUN_TEST(testRejectsBadHeader);
  RUN_TEST(testUncalibratedSampleDoesNotPublishElectricalUnits);
  RUN_TEST(testCalibratedSampleCalculatesPfAndEnergySinceBoot);
  RUN_TEST(testStandbyRequiresCalibratedLowPowerDuration);
  RUN_TEST(testEnergyDoesNotBridgeLongMeterGap);
  RUN_TEST(testNegativePowerDoesNotDecrementConsumptionEnergy);
  RUN_TEST(testEnergyHandlesMillisRollover);
  RUN_TEST(testEnergyRestorePreservesCumulativeValueAndStartsFreshInterval);
  RUN_TEST(testEnergyDoesNotDoubleCountPowerBetweenCfUpdates);
  RUN_TEST(testEnergyMatchesCounterAcrossDifferentPollingRates);
  RUN_TEST(testEnergyCounterRolloverAddsOnlyWrappedDelta);
  RUN_TEST(testEnergyCounterResetRebasesWithoutHugeJump);
  RUN_TEST(testEnergyRejectsImpossibleForwardCounterJump);
  RUN_TEST(testEnergyDoesNotInventConsumptionWhenCfStaysZero);
  RUN_TEST(testEnergyCalibrationChangeStartsFreshBaseline);
  RUN_TEST(testEnergyInvalidCalibrationPreservesTotalAndRebases);
  RUN_TEST(testEnergyRejectsInvalidRestoreWithoutLosingTotal);
  RUN_TEST(testEnergyDuplicateTimestampDoesNotConsumeCounterDelta);
  RUN_TEST(testEnergyRequiresFiniteCompleteCalibration);
  RUN_TEST(testEnergyKeepsSmallIncrementsAtLargeTotal);
  RUN_TEST(testMovingAverageDoesNotSmoothCumulativeCounter);
  RUN_TEST(testStandbyResetsWhenPowerExceedsThreshold);
  RUN_TEST(testVoltageAnomalyRequiresCalibrationAndClassifiesBounds);
  return UNITY_END();
}

#ifdef ARDUINO
void setup() {
  delay(100);
  (void)runProtocolAndMeteringTests();
}

void loop() {}
#else
int main(int, char**) {
  return runProtocolAndMeteringTests();
}
#endif
