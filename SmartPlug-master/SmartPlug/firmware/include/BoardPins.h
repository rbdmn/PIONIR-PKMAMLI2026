#pragma once

#include <Arduino.h>

// Evidence source:
// hardware/easyeda/1-Schematic_smartPlug2Ver2.json
// These mappings are source-derived, not yet validated on physical hardware.
namespace board_pins {

constexpr uint8_t kRelaySet = 5;       // GPIO5 -> R22 -> Q3 -> SET coil
constexpr uint8_t kRelayReset = 4;     // GPIO4 -> R23 -> Q4 -> RESET coil
constexpr uint8_t kMeterRx = 13;       // GPIO13 <- BL0940 TX/SDO
constexpr uint8_t kMeterTx = 15;       // GPIO15 -> BL0940 RX/SDI
constexpr uint8_t kMeterZeroCross = 14;
constexpr uint8_t kMeterCfPulse = 12;
constexpr uint8_t kConfigButton = 0;   // Active-low; ESP8266 boot strap pin
constexpr uint8_t kStatusLed = 2;      // Active-low; ESP8266 boot strap pin

constexpr bool kConfigButtonActiveLow = true;
constexpr bool kStatusLedActiveLow = true;

}  // namespace board_pins

