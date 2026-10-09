#pragma once

#include <cstdint>

// Provisioning credentials are generated per device on the first boot and
// stored in the versioned EEPROM record.  They are deliberately not build-time
// secrets shared by every unit.

namespace smartplug_config {

constexpr char kApiVersion[] = "v1";
constexpr uint8_t kApChannel = 1;
constexpr uint8_t kApMaxClients = 4;

// BL0940 reference profile derived from the SmartPlug schematic:
// Vref = 1.218 V, R15 = 0.5 mOhm, divider bottom = 523 Ohm, divider top =
// R2+R3+R4+R8+R11 = 5 x 390 kOhm.  The UI calibration values are corrections
// to these usable electrical defaults, not a prerequisite for reading values.
constexpr float kVoltageReference = 17596.1871005306F;
constexpr float kCurrentReference = 133006.568144499F;
constexpr float kPowerReference = 365.638500566894F;
constexpr float kEnergyReference = 3138.30042371945F;  // CF counts per kWh
constexpr float kVoltageVoltsPerCode = 1.0F / kVoltageReference;
constexpr float kCurrentAmpsPerCode = 1.0F / kCurrentReference;
constexpr float kPowerWattsPerCode = 1.0F / kPowerReference;
constexpr float kEnergyWattHoursPerCf = 1000.0F / kEnergyReference;
constexpr char kCalibrationProfile[] = "smartplug-r1-derived";

}  // namespace smartplug_config
