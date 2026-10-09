#pragma once

#ifndef SMARTPLUG_ALLOW_RELAY_ACTUATION
#define SMARTPLUG_ALLOW_RELAY_ACTUATION 0
#endif

#ifndef SMARTPLUG_ENABLE_BUTTON
#define SMARTPLUG_ENABLE_BUTTON 0
#endif

#ifndef SMARTPLUG_ENABLE_LED
#define SMARTPLUG_ENABLE_LED 0
#endif

#ifndef SMARTPLUG_RELAY_PULSE_MS
#define SMARTPLUG_RELAY_PULSE_MS 50
#endif

#ifndef SMARTPLUG_RELAY_COOLDOWN_MS
#define SMARTPLUG_RELAY_COOLDOWN_MS 500
#endif

// 0 = original dual-coil latching relay. 1 = external AC SSR driven through
// Q3/SET1: GPIO5 HIGH keeps the SSR input asserted; GPIO5 LOW releases it.
// Keep the production default on the verified latching-relay behavior.
#ifndef SMARTPLUG_RELAY_MODE_SSR
#define SMARTPLUG_RELAY_MODE_SSR 0
#endif

#ifndef SMARTPLUG_ENABLE_LOCAL_API
#define SMARTPLUG_ENABLE_LOCAL_API 0
#endif

#ifndef SMARTPLUG_ENABLE_MQTT
#define SMARTPLUG_ENABLE_MQTT 0
#endif

// Migration images are intentionally separate from the normal product image.
// They provide a two-step, CRC-verified transfer of legacy 64 KiB LittleFS
// energy to the 32 KiB production layout without changing normal operation.
#ifndef SMARTPLUG_LEGACY_FS_MIGRATION_STAGE
#define SMARTPLUG_LEGACY_FS_MIGRATION_STAGE 0
#endif

#ifndef SMARTPLUG_LITTLEFS_32K
#define SMARTPLUG_LITTLEFS_32K 0
#endif

// This is the single release identifier shown by the dashboard, API, serial
// startup report, and upload report.  Bump it for every firmware release.
#define SMARTPLUG_RELEASE_VERSION "R3.10.11"

#ifndef SMARTPLUG_STANDBY_THRESHOLD_W
#define SMARTPLUG_STANDBY_THRESHOLD_W 2.0F
#endif

#ifndef SMARTPLUG_STANDBY_DURATION_MS
#define SMARTPLUG_STANDBY_DURATION_MS 300000UL
#endif

// A new valid BL0940 sample after this gap establishes a fresh counter baseline.
// Retain the cumulative total, but do not infer energy across an uncertain
// interval (a sensor reset during that interval cannot always be observed).
#ifndef SMARTPLUG_MAX_ENERGY_SAMPLE_GAP_MS
#define SMARTPLUG_MAX_ENERGY_SAMPLE_GAP_MS 5000UL
#endif

// Nominal SmartPlug target installation is 220 VAC. These limits implement a basic
// reporting policy only; they are not electrical protection thresholds.
#ifndef SMARTPLUG_UNDERVOLTAGE_V
#define SMARTPLUG_UNDERVOLTAGE_V 198.0F
#endif

#ifndef SMARTPLUG_OVERVOLTAGE_V
#define SMARTPLUG_OVERVOLTAGE_V 242.0F
#endif

#if SMARTPLUG_RELAY_PULSE_MS < 10 || SMARTPLUG_RELAY_PULSE_MS > 200
#error "Relay pulse must remain inside the reviewable 10..200 ms guard range."
#endif

#if SMARTPLUG_RELAY_COOLDOWN_MS < 250
#error "Relay coil cooldown must be at least 250 ms."
#endif

namespace build_config {

constexpr bool kRelayActuationAllowed = SMARTPLUG_ALLOW_RELAY_ACTUATION == 1;
constexpr bool kButtonEnabled = SMARTPLUG_ENABLE_BUTTON == 1;
constexpr bool kLedEnabled = SMARTPLUG_ENABLE_LED == 1;
constexpr unsigned long kRelayPulseMs = SMARTPLUG_RELAY_PULSE_MS;
constexpr unsigned long kRelayCooldownMs = SMARTPLUG_RELAY_COOLDOWN_MS;
constexpr bool kRelayModeSsr = SMARTPLUG_RELAY_MODE_SSR == 1;
constexpr bool kLocalApiEnabled = SMARTPLUG_ENABLE_LOCAL_API == 1;
constexpr bool kMqttEnabled = SMARTPLUG_ENABLE_MQTT == 1;
#if SMARTPLUG_LEGACY_FS_MIGRATION_STAGE
constexpr char kFirmwareVersion[] = SMARTPLUG_RELEASE_VERSION "-migrate64";
#elif SMARTPLUG_RELAY_MODE_SSR
constexpr char kFirmwareVersion[] = SMARTPLUG_RELEASE_VERSION "-ssr";
#elif SMARTPLUG_LITTLEFS_32K
constexpr char kFirmwareVersion[] = SMARTPLUG_RELEASE_VERSION "-32k";
#elif defined(SMARTPLUG_FACTORY_PROVISIONED)
constexpr char kFirmwareVersion[] = SMARTPLUG_RELEASE_VERSION "-factory";
#elif defined(SMARTPLUG_RUNTIME_MODE)
constexpr char kFirmwareVersion[] = SMARTPLUG_RELEASE_VERSION "-unified";
#elif SMARTPLUG_ENABLE_MQTT
constexpr char kFirmwareVersion[] = SMARTPLUG_RELEASE_VERSION "-mqtt";
#else
constexpr char kFirmwareVersion[] = SMARTPLUG_RELEASE_VERSION "-rest";
#endif
constexpr float kStandbyThresholdW = SMARTPLUG_STANDBY_THRESHOLD_W;
constexpr unsigned long kStandbyDurationMs = SMARTPLUG_STANDBY_DURATION_MS;
constexpr unsigned long kMaxEnergySampleGapMs =
    SMARTPLUG_MAX_ENERGY_SAMPLE_GAP_MS;
constexpr float kUndervoltageV = SMARTPLUG_UNDERVOLTAGE_V;
constexpr float kOvervoltageV = SMARTPLUG_OVERVOLTAGE_V;

static_assert(kUndervoltageV > 0.0F,
              "Undervoltage threshold must be positive.");
static_assert(kOvervoltageV > kUndervoltageV,
              "Overvoltage threshold must exceed undervoltage threshold.");

}  // namespace build_config
