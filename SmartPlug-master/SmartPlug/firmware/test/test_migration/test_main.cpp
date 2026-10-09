#include <unity.h>

#include <cmath>
#include <cstdint>
#include <limits>

#ifdef ARDUINO
#include <Arduino.h>
#endif

#include "SmartPlugMigration.h"

void setUp() {}
void tearDown() {}

namespace {

void testPreparedHandoffRoundTripsExactEnergy() {
  const auto record = smartplug_migration::makeRecord(
      smartplug_migration::Phase::kPrepared, 0xFFFFFFFEU, 12345678.125);
  TEST_ASSERT_TRUE(smartplug_migration::valid(record));
  TEST_ASSERT_EQUAL_UINT32(0xFFFFFFFEU,
                           smartplug_migration::energySequence(record));
  TEST_ASSERT_TRUE(smartplug_migration::energyWh(record) == 12345678.125);
}

void testEveryHandoffByteIsCoveredByCrc() {
  const auto record = smartplug_migration::makeRecord(
      smartplug_migration::Phase::kPrepared, 7U, 42.5);
  const uint8_t* bytes = record.bytes;
  for (size_t index = 0; index < sizeof(record); ++index) {
    auto corrupt = record;
    corrupt.bytes[index] = bytes[index] ^ 0x01U;
    TEST_ASSERT_FALSE(smartplug_migration::valid(corrupt));
  }
}

void testImportedPhaseRequiresFreshCrc() {
  auto record = smartplug_migration::makeRecord(
      smartplug_migration::Phase::kPrepared, 9U, 0.0);
  const auto imported = smartplug_migration::makeRecord(
      smartplug_migration::Phase::kImported, 9U, 0.0);
  auto corrupt = record;
  corrupt.bytes[smartplug_migration::kPhaseOffset] =
      static_cast<uint8_t>(smartplug_migration::Phase::kImported);
  TEST_ASSERT_FALSE(smartplug_migration::valid(corrupt));
  TEST_ASSERT_TRUE(smartplug_migration::valid(imported));
  TEST_ASSERT_EQUAL_UINT16(
      static_cast<uint16_t>(smartplug_migration::Phase::kImported),
      static_cast<uint16_t>(smartplug_migration::phase(imported)));
}

void testInvalidEnergyCannotBecomeMigrationRecord() {
  const double invalid[] = {
      -1.0, std::numeric_limits<double>::infinity(),
      std::numeric_limits<double>::quiet_NaN(), 1.0e12 + 1.0};
  for (const double energy : invalid) {
    const auto record = smartplug_migration::makeRecord(
        smartplug_migration::Phase::kPrepared, 1U, energy);
    TEST_ASSERT_FALSE(smartplug_migration::valid(record));
  }
}

}  // namespace

int runMigrationTests() {
  UNITY_BEGIN();
  RUN_TEST(testPreparedHandoffRoundTripsExactEnergy);
  RUN_TEST(testEveryHandoffByteIsCoveredByCrc);
  RUN_TEST(testImportedPhaseRequiresFreshCrc);
  RUN_TEST(testInvalidEnergyCannotBecomeMigrationRecord);
  return UNITY_END();
}

#ifdef ARDUINO
void setup() {
  delay(100);
  (void)runMigrationTests();
}
void loop() {}
#else
int main(int, char**) { return runMigrationTests(); }
#endif
