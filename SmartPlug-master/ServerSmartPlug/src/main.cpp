#include <Arduino.h>
#include <ArduinoJson.h>
#include <FS.h>
#include <ESPmDNS.h>
#include <PicoMQTT.h>
#include <Preferences.h>
#include <SD.h>
#include <SPI.h>
#include <WebServer.h>
#include <WiFi.h>
#include <mbedtls/md.h>
#include <time.h>

// ServerSmartPlug R3.8.14
//
// This intentionally is one PlatformIO source file.  It is the complete
// ESP32-side server for the SmartPlug MQTT profile: commissioning AP, a small
// MQTT 3.1.1 broker, SD-card storage, and the application REST API.

namespace {

constexpr char kServerVersion[] = SERVER_SMARTPLUG_VERSION;
constexpr char kSetupApSsid[] = "ServerSmartPlug-Setup";
constexpr char kSetupApPassword[] = "SmartPlugSetup";
constexpr char kSetupAdminUsername[] = "admin";
// The browser portal is protected separately from the WPA2 setup AP.  This
// factory credential deliberately matches the documented setup AP password so
// the Android onboarding client can authenticate on a factory-reset server.
// It can be replaced from the authenticated portal after onboarding.
constexpr char kFactorySetupAdminPassword[] = "SmartPlugSetup";
// Keep commissioning radio parameters aligned with the SmartPlug provisioning AP.
// A fixed 2.4 GHz channel avoids an AP+STA default-channel change while Android is
// negotiating its WifiNetworkSpecifier connection.
constexpr uint8_t kSetupApChannel = 1;
constexpr uint8_t kSetupApMaxClients = 4;
constexpr uint16_t kHttpPort = 80;
constexpr uint16_t kMqttPort = 1883;
constexpr uint8_t kMaxDevices = 12;
constexpr uint8_t kMaxDailySchedules = 8;
constexpr size_t kScheduleEventChars = 25;
constexpr uint32_t kCommandTimeoutMs = 5000UL;
// Timer expiry is safety-relevant: keep its deadline until a matching OFF ACK
// arrives, but do not retry an indefinitely failing actuator command forever.
constexpr uint8_t kTimerExpiryMaxAttempts = 3U;
constexpr uint32_t kTimerExpiryRetryDelayMs = 2000UL;
// PicoMQTT deliberately does not implement MQTT Last Will.  SmartPlug sends
// complete telemetry every 500 ms, so five seconds is a conservative boundary
// before a silent Wi-Fi/client loss becomes offline in the REST/app view.
constexpr uint32_t kDeviceOfflineTimeoutMs = 5000UL;
constexpr uint32_t kIndexFlushIntervalMs = 10000UL;
constexpr uint32_t kSnapshotFlushIntervalMs = 1000UL;
constexpr uint32_t kScheduleCatchUpSeconds = 300UL;
constexpr size_t kMqttAuthSecretMinChars = 32U;
constexpr size_t kMqttAuthSecretMaxChars = 96U;
constexpr char kIndexPath[] = "/smartplug/devices.csv";
constexpr char kIndexTemporaryPath[] = "/smartplug/devices.new";
constexpr char kIndexBackupPath[] = "/smartplug/devices.bak";
constexpr char kSchedulePath[] = "/smartplug/schedules.csv";
constexpr char kScheduleTemporaryPath[] = "/smartplug/schedules.new";
constexpr char kScheduleBackupPath[] = "/smartplug/schedules.bak";
constexpr char kHistoryPath[] = "/smartplug/history.csv";
constexpr char kEnergyResetAuditPath[] = "/smartplug/energy_resets.csv";
constexpr char kSnapshotPath[] = "/smartplug/snapshots.csv";
constexpr char kSnapshotTemporaryPath[] = "/smartplug/snapshots.new";
constexpr char kSnapshotBackupPath[] = "/smartplug/snapshots.bak";

struct ServerSettings {
  char magic[4];
  uint16_t revision;
  char wifiSsid[33];
  char wifiPassword[65];
  char apiToken[65];
  char brokerUsername[33];
  char brokerPassword[65];
  uint32_t crc;
};

struct DeviceRecord {
  bool used = false;
  char id[20] = {};
  bool online = false;
  bool calibrated = false;
  uint32_t lastSeenMs = 0;
  uint32_t lastSeenUtc = 0;
  float voltageV = 0.0F;
  float currentA = 0.0F;
  float activePowerW = 0.0F;
  float apparentPowerVa = 0.0F;
  float powerFactor = 0.0F;
  float energyWh = 0.0F;
  char relayState[8] = "unknown";
  uint32_t aggregateWindowUtc = 0;
  float sumVoltageV = 0.0F;
  float sumCurrentA = 0.0F;
  float sumActivePowerW = 0.0F;
  float sumApparentPowerVa = 0.0F;
  float sumPowerFactor = 0.0F;
  uint16_t aggregateCount = 0;
  char commandId[28] = {};
  char commandState[4] = {};
  char commandStatus[24] = "none";
  uint32_t commandCreatedAtMs = 0;
  uint32_t commandResolvedAtMs = 0;
  uint32_t timerDeadlineUtc = 0;
  uint32_t timerDurationSeconds = 0;
  // Persisted bounded-retry state for a timer that has reached its deadline.
  // `timerExpiryPending` is runtime-only because a server reboot cannot know
  // whether the previous MQTT publish reached the SmartPlug; it safely retries
  // from the retained deadline instead.
  uint8_t timerExpiryAttempts = 0;
  bool timerExpiryFailed = false;
  bool timerExpiryPending = false;
  uint32_t timerExpiryLastAttemptMs = 0;
  uint32_t lastEnergyResetUtc = 0;
  // A reset is not considered complete merely because the QoS0 downlink was
  // emitted.  This flag is durably stored in the device index and is cleared
  // only by a fresh physical counter value at (or very near) zero.
  bool awaitingEnergyReset = false;
  bool scheduleEnabled = false;
  int16_t timezoneOffsetMinutes = 0;
  struct DailyScheduleEntry {
    uint8_t hour = 0;
    uint8_t minute = 0;
    bool turnOn = false;
    char event[kScheduleEventChars] = {};
  } schedules[kMaxDailySchedules];
  uint8_t scheduleCount = 0;
  int64_t lastScheduleMinuteUtc = -1;
  uint32_t pendingScheduleDueUtc = 0;
  bool pendingScheduleTurnOn = false;
  // Runtime replay state for SPMQTT2. A fresh firmware boot epoch resets the
  // monotonic nonce only after the previous live epoch has expired.
  bool mqttV2Seen = false;
  uint32_t mqttV2Boot = 0U;
  uint32_t mqttV2LastNonce = 0U;
  uint32_t mqttV2DownNonce = 0U;
  // Runtime-only accumulator for the per-second ("fine") history.
  uint32_t fineWindowUtc = 0U;
  float fineSumV = 0.0F, fineSumA = 0.0F, fineSumW = 0.0F, fineSumVa = 0.0F, fineSumPf = 0.0F;
  uint16_t fineCount = 0U;
  // Runtime-only "reconcile after the SmartPlug rebooted" state. `restoreTarget` is the relay
  // state this server last knew before the reboot; see serviceBootRestore().
  char restoreTarget[4] = {};
  bool restorePending = false;
  bool restoreStateSeen = false;
  uint32_t restoreDetectedMs = 0U;
};

ServerSettings settings{};
DeviceRecord devices[kMaxDevices];
WebServer http(kHttpPort);
Preferences preferences;
SPIClass sdSpi(VSPI);

bool settingsReady = false;
bool sdReady = false;
bool mdnsStarted = false;
uint32_t lastSdMountAttemptMs = 0;
// Persisted files are authoritative only while RAM has not yet accepted any
// runtime state.  In particular, an SD card reinserted after a write failure
// must not replay an older relay/timer/index/snapshot over newer MQTT or REST
// state already held in RAM.
bool storageRecoveryHandled = false;
bool indexDirty = false;
uint32_t lastIndexFlushAtMs = 0;
bool snapshotDirty = false;
uint32_t lastSnapshotFlushAtMs = 0;
// Snapshot persistence is intentionally observable.  A successful broker
// session must not be mistaken for durable recovery data when a card has been
// removed, become read-only, or developed a transient FAT error.
uint32_t snapshotWriteAttempts = 0;
uint32_t snapshotWriteSuccesses = 0;
uint32_t snapshotWriteFailures = 0;
uint32_t lastSnapshotWriteSuccessMs = 0;
uint32_t lastSnapshotWriteSuccessUtc = 0;
uint32_t lastSnapshotWriteFailureMs = 0;
uint32_t lastSnapshotWriteCadenceMs = 0;
// `/health` is polled by the Android app/support tools. Keep it non-blocking:
// this is the size of the last snapshot successfully loaded or written, not a
// fresh SD-card stat on every health request.
uint32_t lastKnownSnapshotFileBytes = 0;
uint32_t lastBrokerHeartbeatAtMs = 0;
bool factoryResetPending = false;
uint32_t factoryResetAtMs = 0;
uint32_t commandCounter = 0;
// Runtime-only broker counters. They make transport failures observable without
// exposing any credential or MQTT payload through the unauthenticated health API.
// Counts application MQTT publishes accepted by the broker.  It is not a
// connection/session counter: one stable SmartPlug deliberately sends frequent
// telemetry messages.
uint32_t mqttAcceptedMessages = 0;
uint32_t mqttPublishPackets = 0;
uint32_t mqttDeniedPublishes = 0;
// These counters establish the active boot-recovery contract without exposing
// device data or credentials through /health.
uint32_t mqttSyncRequests = 0;
uint32_t mqttSyncSnapshotsPublished = 0;
char lastMqttRejectReason[32] = {};
char lastMqttRejectTopicKind[40] = {};
uint16_t lastMqttRejectPayloadBytes = 0U;

void recordMqttReject(const char* reason, const char* topicKind = nullptr,
                      const uint16_t payloadBytes = 0U) {
  ++mqttDeniedPublishes;
  snprintf(lastMqttRejectReason, sizeof(lastMqttRejectReason), "%s",
           reason == nullptr ? "unknown" : reason);
  if (topicKind != nullptr) {
    snprintf(lastMqttRejectTopicKind, sizeof(lastMqttRejectTopicKind), "%s", topicKind);
    lastMqttRejectPayloadBytes = payloadBytes;
  }
}

uint32_t crc32(const uint8_t* bytes, const size_t length) {
  uint32_t value = 0xFFFFFFFFUL;
  for (size_t i = 0; i < length; ++i) {
    value ^= bytes[i];
    for (uint8_t bit = 0; bit < 8; ++bit) {
      value = (value >> 1U) ^ (0xEDB88320UL & (0U - (value & 1U)));
    }
  }
  return ~value;
}

template <size_t N>
void copyText(char (&destination)[N], const String& source) {
  const size_t length = min(source.length(), N - 1U);
  memcpy(destination, source.c_str(), length);
  destination[length] = '\0';
}

template <size_t N>
void copyLiteral(char (&destination)[N], const char* source) {
  const size_t length = min(strlen(source), N - 1U);
  memcpy(destination, source, length);
  destination[length] = '\0';
}

bool constantTimeEquals(const String& first, const char* second) {
  const size_t secondLength = strlen(second);
  const size_t maximum = max(first.length(), secondLength);
  uint8_t difference = static_cast<uint8_t>(first.length() ^ secondLength);
  for (size_t i = 0; i < maximum; ++i) {
    const char left = i < first.length() ? first[i] : 0;
    const char right = i < secondLength ? second[i] : 0;
    difference |= static_cast<uint8_t>(left ^ right);
  }
  return difference == 0;
}

bool validDeviceId(const String& id) {
  if (!id.startsWith("SP-") || id.length() != 15) return false;
  for (size_t i = 3; i < id.length(); ++i) {
    const char c = id[i];
    if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return false;
  }
  return true;
}

bool validCredentialText(const String& value, const size_t minimum,
                         const size_t maximum) {
  if (value.length() < minimum || value.length() > maximum) return false;
  for (size_t i = 0; i < value.length(); ++i) {
    const uint8_t c = static_cast<uint8_t>(value[i]);
    if (c < 0x21U || c > 0x7EU) return false;
  }
  return true;
}

bool validScheduleEvent(const String& value) {
  if (value.length() > kScheduleEventChars - 1U) return false;
  for (size_t i = 0; i < value.length(); ++i) {
    const char c = value[i];
    if (c < 0x20 || c > 0x7E || c == ',' || c == ':' || c == '"' || c == '\\') return false;
  }
  return true;
}

uint32_t currentUtc() {
  const time_t now = time(nullptr);
  return now >= 1700000000 ? static_cast<uint32_t>(now) : 0U;
}

bool timeIsSynchronized() { return currentUtc() != 0U; }

bool mqttCredentialsConfigured() {
  return strlen(settings.brokerUsername) >= 3 && strlen(settings.brokerPassword) >= 8;
}

bool applicationApiConfigured() { return strlen(settings.apiToken) >= 16; }

String savedSetupAdminPassword() {
  preferences.begin("setup-auth", true);
  const String stored = preferences.getString("admin_password", "");
  preferences.end();
  return stored.isEmpty() ? String(kFactorySetupAdminPassword) : stored;
}

bool requestIsSetupAdmin() {
  const String password = savedSetupAdminPassword();
  if (http.authenticate(kSetupAdminUsername, password.c_str())) return true;
  http.requestAuthentication(BASIC_AUTH, "ServerSmartPlug setup");
  return false;
}

bool changeSetupAdminPassword(const String& password) {
  if (!validCredentialText(password, 12, 64)) return false;
  preferences.begin("setup-auth", false);
  const size_t written = preferences.putString("admin_password", password);
  preferences.end();
  return written == password.length();
}

void eraseMqttV2Secrets();

void resetSettings() {
  memset(&settings, 0, sizeof(settings));
  memcpy(settings.magic, "SPSV", 4);
  settings.revision = 1;
}

bool saveSettings() {
  memcpy(settings.magic, "SPSV", 4);
  settings.revision = 1;
  settings.crc = crc32(reinterpret_cast<const uint8_t*>(&settings),
                       offsetof(ServerSettings, crc));
  preferences.begin("smartplug-srv", false);
  const size_t written = preferences.putBytes("settings", &settings, sizeof(settings));
  preferences.end();
  return written == sizeof(settings);
}

void loadSettings() {
  resetSettings();
  preferences.begin("smartplug-srv", true);
  const size_t stored = preferences.getBytesLength("settings");
  if (stored == sizeof(settings)) preferences.getBytes("settings", &settings, sizeof(settings));
  preferences.end();
  const uint32_t expected = crc32(reinterpret_cast<const uint8_t*>(&settings),
                                  offsetof(ServerSettings, crc));
  if (stored != sizeof(settings) || memcmp(settings.magic, "SPSV", 4) != 0 ||
      settings.revision != 1 || settings.crc != expected) {
    resetSettings();
    settingsReady = false;
    return;
  }
  settings.wifiSsid[sizeof(settings.wifiSsid) - 1] = '\0';
  settings.wifiPassword[sizeof(settings.wifiPassword) - 1] = '\0';
  settings.apiToken[sizeof(settings.apiToken) - 1] = '\0';
  settings.brokerUsername[sizeof(settings.brokerUsername) - 1] = '\0';
  settings.brokerPassword[sizeof(settings.brokerPassword) - 1] = '\0';
  settingsReady = applicationApiConfigured() && mqttCredentialsConfigured();
}

/** Erases only ServerSmartPlug-managed data.  It deliberately does not format the SD card,
 * so unrelated user files on the same card remain intact. */
void eraseManagedStorage() {
  if (!sdReady) return;
  const char* const paths[] = {
      kIndexPath, kIndexTemporaryPath, kIndexBackupPath,
      kSchedulePath, kScheduleTemporaryPath, kScheduleBackupPath,
      kHistoryPath, kEnergyResetAuditPath,
      kSnapshotPath, kSnapshotTemporaryPath, kSnapshotBackupPath,
  };
  for (const char* path : paths) {
    if (SD.exists(path)) SD.remove(path);
  }
}

void performFactoryReset() {
  preferences.begin("smartplug-srv", false);
  preferences.clear();
  preferences.end();
  preferences.begin("setup-auth", false);
  preferences.clear();
  preferences.end();
  eraseMqttV2Secrets();
  eraseManagedStorage();
  memset(devices, 0, sizeof(devices));
  resetSettings();
  settingsReady = false;
  Serial.println(F("INFO server_factory_reset_complete"));
  delay(100);
  ESP.restart();
}

void startNetwork() {
  // Match the known-good SmartPlug commissioning AP sequence: reset the Wi-Fi
  // state, set the DHCP subnet explicitly, then create a WPA2 AP on channel 1.
  // This is intentionally done before connecting the station interface.
  const IPAddress apIp(192, 168, 4, 1);
  const IPAddress subnet(255, 255, 255, 0);
  WiFi.mode(WIFI_OFF);
  delay(50);
  WiFi.mode(WIFI_AP_STA);
  delay(100);
  const bool apConfigured = WiFi.softAPConfig(apIp, apIp, subnet);
  const bool apStarted = WiFi.softAP(kSetupApSsid, kSetupApPassword,
                                     kSetupApChannel, false, kSetupApMaxClients);
  Serial.printf("INFO setup_ap started=%s ssid=%s ip=%s\\n",
                apStarted ? "true" : "false", kSetupApSsid,
                WiFi.softAPIP().toString().c_str());
  if (!apConfigured) {
    Serial.println(F("WARN setup_ap_config_failed"));
  }
  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
    if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
      Serial.printf("INFO station_connected ip=%s\n", WiFi.localIP().toString().c_str());
    } else if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
      // Reason codes are intentionally logged without SSID/password content.
      Serial.printf("WARN station_disconnected reason=%u\n", info.wifi_sta_disconnected.reason);
    }
  });
  if (strlen(settings.wifiSsid) > 0) {
    Serial.printf("INFO station_profile ssid_length=%u password_length=%u\n",
                  static_cast<unsigned>(strlen(settings.wifiSsid)),
                  static_cast<unsigned>(strlen(settings.wifiPassword)));
    WiFi.begin(settings.wifiSsid, settings.wifiPassword);
    Serial.println(F("INFO station_connecting"));
  }
}

String stationIpText() {
  return WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : String();
}

String serverId() {
  String id = WiFi.macAddress();
  id.replace(":", "");
  id.toUpperCase();
  return String("SRV-") + id;
}

String mdnsHost() {
  String host = String("srvrplug-") + WiFi.macAddress();
  host.replace(":", "");
  host.toLowerCase();
  return host;
}

void serviceMdns() {
  if (WiFi.status() != WL_CONNECTED || mdnsStarted) return;
  const String host = mdnsHost();
  if (!MDNS.begin(host.c_str())) {
    Serial.println(F("WARN mdns_start_failed"));
    return;
  }
  MDNS.addService("srvrplug", "tcp", kHttpPort);
  MDNS.addServiceTxt("srvrplug", "tcp", "server_id", serverId());
  MDNS.addServiceTxt("srvrplug", "tcp", "api_version", "v1");
  MDNS.addServiceTxt("srvrplug", "tcp", "mqtt_port", String(kMqttPort));
  mdnsStarted = true;
  Serial.printf("INFO mdns_ready host=%s.local\\n", host.c_str());
}

void configureTimeIfConnected() {
  static bool configured = false;
  if (!configured && WiFi.status() == WL_CONNECTED) {
    configTime(0, 0, "pool.ntp.org", "time.nist.gov");
    configured = true;
  }
}

DeviceRecord* findDevice(const String& id, const bool create) {
  if (!validDeviceId(id)) return nullptr;
  for (DeviceRecord& device : devices) {
    if (device.used && id == device.id) return &device;
  }
  if (!create) return nullptr;
  for (DeviceRecord& device : devices) {
    if (!device.used) {
      device = DeviceRecord{};
      device.used = true;
      copyText(device.id, id);
      return &device;
    }
  }
  return nullptr;
}

uint8_t deviceCount() {
  uint8_t count = 0;
  for (const DeviceRecord& device : devices) if (device.used) ++count;
  return count;
}

void sendJson(const int status, const String& body) {
  http.sendHeader("Access-Control-Allow-Origin", "*");
  http.sendHeader("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type");
  http.sendHeader("Cache-Control", "no-store");
  http.send(status, "application/json", body);
}

String jsonEscape(const String& input) {
  static const char hex[] = "0123456789abcdef";
  String escaped;
  escaped.reserve(input.length() + 8);
  for (size_t index = 0; index < input.length(); ++index) {
    const uint8_t value = static_cast<uint8_t>(input[index]);
    if (value == '"') escaped += F("\\\"");
    else if (value == '\\') escaped += F("\\\\");
    else if (value == '\n') escaped += F("\\n");
    else if (value == '\r') escaped += F("\\r");
    else if (value == '\t') escaped += F("\\t");
    else if (value < 0x20) {
      escaped += F("\\u00");
      escaped += hex[(value >> 4) & 0x0f];
      escaped += hex[value & 0x0f];
    } else escaped += static_cast<char>(value);
  }
  return escaped;
}

void sendError(const int status, const char* error) {
  sendJson(status, String("{\"error\":\"") + error + "\"}");
}

bool requestIsAuthorized() {
  if (!applicationApiConfigured()) {
    sendError(503, "server_not_configured");
    return false;
  }
  String token = http.header("X-API-Key");
  const String authorization = http.header("Authorization");
  if (token.isEmpty() && authorization.startsWith("Bearer ")) {
    token = authorization.substring(7);
  }
  if (!constantTimeEquals(token, settings.apiToken)) {
    sendError(401, "authentication_required");
    return false;
  }
  return true;
}

// SPMQTT2 is intentionally an application-layer authenticity boundary. PicoMQTT
// validates the shared broker login, but its publish callback does not expose a
// client identity. A signed envelope therefore binds each accepted message to a
// registered SmartPlug secret, its topic, and an anti-replay boot/nonce pair.
String mqttAuthPreferenceKey(const String& deviceId) {
  // NVS keys are limited to 15 characters. A SmartPlug id is SP- + 12 hex;
  // dropping the constant prefix leaves a compact, collision-free key.
  return String("v2") + deviceId.substring(3);
}

String mqttV2ReplayBootPreferenceKey(const String& deviceId) {
  return String("b") + deviceId.substring(3);
}

String mqttV2ReplayNoncePreferenceKey(const String& deviceId) {
  return String("n") + deviceId.substring(3);
}

void clearMqttV2ReplayState(const String& deviceId) {
  if (!validDeviceId(deviceId)) return;
  preferences.begin("spmqtt-auth", false);
  preferences.remove(mqttV2ReplayBootPreferenceKey(deviceId).c_str());
  preferences.remove(mqttV2ReplayNoncePreferenceKey(deviceId).c_str());
  preferences.end();
}

bool loadMqttV2ReplayState(const String& deviceId, const uint32_t boot,
                           uint32_t& downNonce) {
  downNonce = 0U;
  if (!validDeviceId(deviceId) || boot == 0U) return false;
  preferences.begin("spmqtt-auth", true);
  const uint32_t savedBoot = preferences.getULong(
      mqttV2ReplayBootPreferenceKey(deviceId).c_str(), 0U);
  const uint32_t savedNonce = preferences.getULong(
      mqttV2ReplayNoncePreferenceKey(deviceId).c_str(), 0U);
  preferences.end();
  if (savedBoot != boot) return false;
  downNonce = savedNonce;
  return true;
}

bool saveMqttV2ReplayState(const String& deviceId, const uint32_t boot,
                           const uint32_t downNonce) {
  if (!validDeviceId(deviceId) || boot == 0U) return false;
  preferences.begin("spmqtt-auth", false);
  const size_t bootWritten = preferences.putULong(
      mqttV2ReplayBootPreferenceKey(deviceId).c_str(), boot);
  const size_t nonceWritten = preferences.putULong(
      mqttV2ReplayNoncePreferenceKey(deviceId).c_str(), downNonce);
  preferences.end();
  return bootWritten == sizeof(boot) && nonceWritten == sizeof(downNonce);
}

bool mqttV2SecretForDevice(const String& deviceId, String& secret) {
  secret = String();
  if (!validDeviceId(deviceId)) return false;
  preferences.begin("spmqtt-auth", true);
  secret = preferences.getString(mqttAuthPreferenceKey(deviceId).c_str(), "");
  preferences.end();
  return validCredentialText(secret, kMqttAuthSecretMinChars, kMqttAuthSecretMaxChars);
}

bool mqttV2DeviceRegistered(const String& deviceId) {
  String ignored;
  return mqttV2SecretForDevice(deviceId, ignored);
}

bool saveMqttV2Secret(const String& deviceId, const String& secret) {
  if (!validDeviceId(deviceId) ||
      !validCredentialText(secret, kMqttAuthSecretMinChars, kMqttAuthSecretMaxChars)) {
    return false;
  }
  preferences.begin("spmqtt-auth", false);
  const size_t written = preferences.putString(mqttAuthPreferenceKey(deviceId).c_str(), secret);
  preferences.end();
  if (written != secret.length()) return false;
  // A replacement secret starts a new authenticated relationship. Do not
  // reuse a downlink nonce from the old secret/session.
  clearMqttV2ReplayState(deviceId);
  return true;
}

bool removeMqttV2Secret(const String& deviceId) {
  if (!validDeviceId(deviceId)) return false;
  preferences.begin("spmqtt-auth", false);
  const bool removed = preferences.remove(mqttAuthPreferenceKey(deviceId).c_str());
  preferences.end();
  clearMqttV2ReplayState(deviceId);
  return removed;
}

void eraseMqttV2Secrets() {
  preferences.begin("spmqtt-auth", false);
  preferences.clear();
  preferences.end();
}

String hexBytes(const uint8_t* bytes, const size_t length) {
  // ESP8266 Crypto's String HMAC formatter emits uppercase hexadecimal. Keep
  // the wire representation identical on both sides; the signature comparison
  // is intentionally constant-time and therefore case-sensitive.
  static constexpr char kHex[] = "0123456789ABCDEF";
  String result;
  result.reserve(length * 2U);
  for (size_t index = 0; index < length; ++index) {
    result += kHex[(bytes[index] >> 4U) & 0x0FU];
    result += kHex[bytes[index] & 0x0FU];
  }
  return result;
}

String randomHex(const size_t chars) {
  static constexpr char kHex[] = "0123456789abcdef";
  String result;
  result.reserve(chars);
  for (size_t index = 0; index < chars; ++index) {
    result += kHex[esp_random() & 0x0FU];
  }
  return result;
}

bool mqttV2Hmac(const String& secret, const char* direction, const String& topic,
                const String& deviceId, const uint32_t boot, const uint32_t nonce,
                const String& compactPayload, String& signature) {
  const mbedtls_md_info_t* const info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (info == nullptr) return false;
  const String canonical = String("SPMQTT2|v=2|dir=") + direction + "|topic=" + topic +
                           "|device=" + deviceId + "|boot=" + String(boot) +
                           "|nonce=" + String(nonce) + "|payload=" + compactPayload;
  uint8_t digest[32] = {};
  const int result = mbedtls_md_hmac(info,
                                     reinterpret_cast<const unsigned char*>(secret.c_str()), secret.length(),
                                     reinterpret_cast<const unsigned char*>(canonical.c_str()), canonical.length(),
                                     digest);
  if (result != 0) return false;
  signature = hexBytes(digest, sizeof(digest));
  return true;
}

bool constantTimeEquals(const String& first, const String& second) {
  const size_t maximum = max(first.length(), second.length());
  uint8_t difference = static_cast<uint8_t>(first.length() ^ second.length());
  for (size_t index = 0; index < maximum; ++index) {
    const char left = index < first.length() ? first[index] : 0;
    const char right = index < second.length() ? second[index] : 0;
    difference |= static_cast<uint8_t>(left ^ right);
  }
  return difference == 0;
}

// SPMQTT2 signs the compact JSON payload byte-for-byte.  ArduinoJson is still
// used below to validate the envelope, but serializing its parsed number values
// (for example 220.00 -> 220) would change the HMAC input.  Extract the raw
// top-level object text safely so verification has exactly the bytes signed by
// SmartPlug.
bool extractTopLevelObjectJsonField(const String& source, const char* field,
                                    String& rawObject) {
  rawObject = String();
  const String expected = String("\"") + field + "\"";
  bool inString = false;
  bool escaped = false;
  int depth = 0;
  for (size_t index = 0; index < source.length(); ++index) {
    const char current = source[index];
    if (inString) {
      if (escaped) escaped = false;
      else if (current == '\\') escaped = true;
      else if (current == '"') inString = false;
      continue;
    }
    if (current == '"') {
      if (depth == 1 && source.startsWith(expected, index)) {
        size_t valueAt = index + expected.length();
        while (valueAt < source.length() && isspace(static_cast<unsigned char>(source[valueAt]))) ++valueAt;
        if (valueAt >= source.length() || source[valueAt] != ':') return false;
        ++valueAt;
        while (valueAt < source.length() && isspace(static_cast<unsigned char>(source[valueAt]))) ++valueAt;
        if (valueAt >= source.length() || source[valueAt] != '{') return false;
        const size_t begin = valueAt;
        bool nestedString = false;
        bool nestedEscaped = false;
        int nestedDepth = 0;
        for (; valueAt < source.length(); ++valueAt) {
          const char nested = source[valueAt];
          if (nestedString) {
            if (nestedEscaped) nestedEscaped = false;
            else if (nested == '\\') nestedEscaped = true;
            else if (nested == '"') nestedString = false;
            continue;
          }
          if (nested == '"') { nestedString = true; continue; }
          if (nested == '{') ++nestedDepth;
          else if (nested == '}' && --nestedDepth == 0) {
            rawObject = source.substring(begin, valueAt + 1U);
            return true;
          }
        }
        return false;
      }
      inString = true;
    } else if (current == '{') {
      ++depth;
    } else if (current == '}') {
      --depth;
      if (depth < 0) return false;
    }
  }
  return false;
}

bool mqttV2Envelope(const String& topic, const String& payload, const char* direction,
                    String& deviceId, uint32_t& boot, uint32_t& nonce, String& compactPayload,
                    String& rejectReason) {
  JsonDocument document;
  const DeserializationError jsonError = deserializeJson(document, payload);
  if (jsonError) {
    // Distinguish a packet truncated in transit from syntactically invalid
    // telemetry. It is diagnostics only: no message content is exposed.
    rejectReason = jsonError == DeserializationError::IncompleteInput ? "v2_incomplete_json" :
                   jsonError == DeserializationError::NoMemory ? "v2_json_no_memory" :
                   "v2_invalid_json";
    return false;
  }
  if ((document["protocol"] | 0) != 2) { rejectReason = "v2_missing_marker"; return false; }
  deviceId = document["device_id"] | "";
  const String suppliedSignature = document["sig"] | "";
  if (!validDeviceId(deviceId) || suppliedSignature.length() != 64U ||
      document["boot"].isNull() || document["nonce"].isNull() || document["payload"].isNull() ||
      !document["payload"].is<JsonObject>()) {
    rejectReason = "v2_invalid_envelope";
    return false;
  }
  if (!extractTopLevelObjectJsonField(payload, "payload", compactPayload)) {
    rejectReason = "v2_payload_extraction_failed";
    return false;
  }
  const uint64_t nonce64 = document["nonce"] | 0ULL;
  const uint64_t boot64 = document["boot"] | 0ULL;
  if (boot64 == 0ULL || boot64 > UINT32_MAX || nonce64 == 0ULL || nonce64 > UINT32_MAX) {
    rejectReason = "v2_invalid_nonce";
    return false;
  }
  boot = static_cast<uint32_t>(boot64);
  nonce = static_cast<uint32_t>(nonce64);
  String secret;
  if (!mqttV2SecretForDevice(deviceId, secret)) { rejectReason = "v2_unregistered"; return false; }
  String expectedSignature;
  if (!mqttV2Hmac(secret, direction, topic, deviceId, boot, nonce, compactPayload, expectedSignature) ||
      !constantTimeEquals(suppliedSignature, expectedSignature)) {
    rejectReason = "v2_bad_signature";
    return false;
  }
  return true;
}

String mqttV2WrapDownlink(const String& topic, const String& deviceId,
                          const uint32_t boot, const uint32_t nonce, const String& compactPayload) {
  String secret;
  String signature;
  if (!mqttV2SecretForDevice(deviceId, secret) ||
      !mqttV2Hmac(secret, "down", topic, deviceId, boot, nonce, compactPayload, signature)) {
    return String();
  }
  JsonDocument document;
  document["protocol"] = 2;
  document["device_id"] = deviceId;
  document["boot"] = boot;
  document["nonce"] = nonce;
  JsonDocument payloadDocument;
  if (deserializeJson(payloadDocument, compactPayload) || !payloadDocument.is<JsonObject>()) {
    return String();
  }
  document["payload"] = payloadDocument.as<JsonObject>();
  document["sig"] = signature;
  String wrapped;
  serializeJson(document, wrapped);
  return wrapped;
}

String jsonNumber(const float value, const uint8_t decimals) {
  if (!isfinite(value)) return "0";
  return String(value, static_cast<unsigned int>(decimals));
}

String deviceSummaryJson(const DeviceRecord& device) {
  String json = "{\"device_id\":\"" + String(device.id) + "\",\"display_name\":\"" +
                String(device.id) + "\",\"status\":\"" + (device.online ? "online" : "offline") +
                "\",\"last_seen_utc\":" + String(device.lastSeenUtc) + ",\"relay\":\"" +
                String(device.relayState) + "\",\"energy_wh\":" + jsonNumber(device.energyWh, 3) +
                ",\"timer_deadline_utc\":" + String(device.timerDeadlineUtc) +
                ",\"timer_duration_seconds\":" + String(device.timerDurationSeconds) +
                ",\"timer_expiry_pending\":" + (device.timerExpiryPending ? "true" : "false") +
                ",\"timer_expiry_attempts\":" + String(device.timerExpiryAttempts) +
                ",\"timer_expiry_failed\":" + (device.timerExpiryFailed ? "true" : "false") + "}";
  return json;
}

String deviceScheduleJson(const DeviceRecord& device);

String deviceLatestJson(const DeviceRecord& device) {
  return String("{\"device_id\":\"") + device.id +
         "\",\"captured_at_ms\":" + String(static_cast<uint64_t>(device.lastSeenUtc) * 1000ULL) +
         ",\"status\":\"" + (device.online ? "online" : "offline") +
         "\",\"relay_state\":\"" + device.relayState +
         "\",\"calibrated\":" + (device.calibrated ? "true" : "false") +
         ",\"voltage_v\":" + jsonNumber(device.voltageV, 3) +
         ",\"current_a\":" + jsonNumber(device.currentA, 4) +
         ",\"active_power_w\":" + jsonNumber(device.activePowerW, 2) +
         ",\"apparent_power_va\":" + jsonNumber(device.apparentPowerVa, 2) +
         ",\"power_factor\":" + jsonNumber(device.powerFactor, 3) +
         ",\"energy_wh\":" + jsonNumber(device.energyWh, 3) +
         ",\"timer_deadline_utc\":" + String(device.timerDeadlineUtc) +
         ",\"timer_duration_seconds\":" + String(device.timerDurationSeconds) +
         ",\"timer_expiry_pending\":" + (device.timerExpiryPending ? "true" : "false") +
         ",\"timer_expiry_attempts\":" + String(device.timerExpiryAttempts) +
         ",\"timer_expiry_failed\":" + (device.timerExpiryFailed ? "true" : "false") +
         ",\"schedule\":" + deviceScheduleJson(device) + "}";
}

String deviceScheduleJson(const DeviceRecord& device) {
  const uint32_t now = currentUtc();
  uint32_t nextSeconds = 0U;
  int nextState = -1;
  if (now != 0U && device.scheduleEnabled && device.scheduleCount > 0U) {
    const time_t localEpoch = static_cast<time_t>(now) +
                              static_cast<time_t>(device.timezoneOffsetMinutes) * 60;
    tm local = {};
    gmtime_r(&localEpoch, &local);
    const int currentMinute = local.tm_hour * 60 + local.tm_min;
    for (uint8_t index = 0; index < device.scheduleCount; ++index) {
      const auto& entry = device.schedules[index];
      int deltaMinutes = (entry.hour * 60 + entry.minute) - currentMinute;
      if (deltaMinutes <= 0) deltaMinutes += 24 * 60;
      const uint32_t candidate = static_cast<uint32_t>(deltaMinutes * 60 - local.tm_sec);
      if (nextState < 0 || candidate < nextSeconds) {
        nextSeconds = candidate;
        nextState = entry.turnOn ? 1 : 0;
      }
    }
  }
  String result = String("{\"enabled\":") + (device.scheduleEnabled ? "true" : "false") +
      ",\"clock\":{\"synchronized\":" + (now != 0U ? "true" : "false") +
      ",\"utc_ms\":" + (now != 0U ? String(static_cast<uint64_t>(now) * 1000ULL) : "0") +
      ",\"timezone_offset_minutes\":" + String(device.timezoneOffsetMinutes) + "}" +
      ",\"next\":{\"remaining_seconds\":" + String(nextSeconds) + ",\"state\":\"" +
      (nextState == 1 ? "on" : nextState == 0 ? "off" : "") + "\"},\"entries\":[";
  for (uint8_t index = 0; index < device.scheduleCount; ++index) {
    if (index != 0U) result += ',';
    const auto& entry = device.schedules[index];
    result += String("{\"hour\":") + String(entry.hour) + ",\"minute\":" +
        String(entry.minute) + ",\"state\":\"" + (entry.turnOn ? "on" : "off") +
        "\",\"event\":\"" + String(entry.event) + "\"}";
  }
  return result + "]}";
}

// A server association owns timer and schedule state.  Detaching a device
// must explicitly erase that authority instead of merely revoking MQTT
// credentials: otherwise an old schedule would become live again if the same
// device ID is later re-attached.  Do not alter relay state or regular relay
// commands here.
bool clearDeviceAutomation(DeviceRecord& device) {
  if (strcmp(device.commandStatus, "queued") == 0 &&
      (strncmp(device.commandId, "timer-", 6U) == 0 ||
       strncmp(device.commandId, "schedule-", 9U) == 0)) {
    return false;
  }
  device.timerDeadlineUtc = 0U;
  device.timerDurationSeconds = 0U;
  device.timerExpiryPending = false;
  device.timerExpiryAttempts = 0U;
  device.timerExpiryFailed = false;
  device.timerExpiryLastAttemptMs = 0U;
  device.scheduleEnabled = false;
  device.scheduleCount = 0U;
  memset(device.schedules, 0, sizeof(device.schedules));
  device.lastScheduleMinuteUtc = -1;
  device.pendingScheduleDueUtc = 0U;
  device.pendingScheduleTurnOn = false;
  return true;
}

void appendEnergyResetAudit(const DeviceRecord& device, const float previousEnergyWh,
                            const uint32_t resetUtc) {
  if (!sdReady || resetUtc == 0U) return;
  File file = SD.open(kEnergyResetAuditPath, FILE_WRITE);
  if (!file) return;
  file.printf("%lu,%s,%.3f\n", static_cast<unsigned long>(resetUtc), device.id, previousEnergyWh);
  file.close();
}

// Bytes of /smartplug/history.csv that belong to each SmartPlug (RAM only). A background scan
// (serviceHistoryScan) fills it after boot; until it reaches EOF `historyScanDone` is false and
// appends are left for the scan to pick up, so nothing is counted twice.
uint64_t historyBytesByDevice[kMaxDevices] = {};
uint64_t historyBytesUnattributed = 0;
uint32_t historyScanOffset = 0;
bool historyScanDone = false;
// 0 = the cached SD capacity must be recomputed on the next /status?storage=1.
uint32_t sdCapacityCachedAtMs = 0;

void appendHistory(const DeviceRecord& device, const uint32_t windowUtc,
                   const float voltageV, const float currentA,
                   const float activePowerW, const float apparentPowerVa,
                   const float powerFactor, const float energyWh) {
  if (!sdReady || windowUtc == 0U) return;
  File file = SD.open(kHistoryPath, FILE_WRITE);
  if (!file) return;
  const size_t written = file.printf("%lu,%s,%.3f,%.4f,%.2f,%.2f,%.3f,%.3f\n",
              static_cast<unsigned long>(windowUtc), device.id, voltageV, currentA,
              activePowerW, apparentPowerVa, powerFactor, energyWh);
  file.close();
  if (historyScanDone && &device >= devices && &device < devices + kMaxDevices) {
    historyBytesByDevice[&device - devices] += written;
  }
}

// Reads at most a few dozen history lines per call, so a multi-megabyte file never stalls the
// broker or the HTTP server. Lines are attributed by the device id in their second CSV field.
void serviceHistoryScan() {
  if (historyScanDone || !sdReady) return;
  if (!SD.exists(kHistoryPath)) { historyScanDone = true; return; }
  File file = SD.open(kHistoryPath, FILE_READ);
  if (!file) return;
  if (!file.seek(historyScanOffset)) { file.close(); historyScanOffset = 0; return; }
  for (uint8_t lines = 0; lines < 40; ++lines) {
    if (!file.available()) { historyScanDone = true; break; }
    const String line = file.readStringUntil('\n');
    const size_t lineBytes = line.length() + 1U;
    // Every record ends with '\n'; a line that would run past EOF is still being written, so
    // leave it (offset unchanged) for the next pass.
    if (static_cast<uint64_t>(historyScanOffset) + lineBytes > file.size()) break;
    historyScanOffset += static_cast<uint32_t>(lineBytes);
    const int first = line.indexOf(',');
    const int second = first < 0 ? -1 : line.indexOf(',', first + 1);
    bool attributed = false;
    if (first >= 0 && second > first) {
      const String id = line.substring(first + 1, second);
      for (size_t i = 0; i < kMaxDevices; ++i) {
        if (devices[i].used && id == devices[i].id) {
          historyBytesByDevice[i] += lineBytes;
          attributed = true;
          break;
        }
      }
    }
    if (!attributed) historyBytesUnattributed += lineBytes;
  }
  file.close();
}

void flushAggregate(DeviceRecord& device) {
  if (device.aggregateCount == 0 || device.aggregateWindowUtc == 0U) return;
  const float count = static_cast<float>(device.aggregateCount);
  appendHistory(device, device.aggregateWindowUtc, device.sumVoltageV / count,
                device.sumCurrentA / count, device.sumActivePowerW / count,
                device.sumApparentPowerVa / count, device.sumPowerFactor / count,
                device.energyWh);
  device.sumVoltageV = 0.0F;
  device.sumCurrentA = 0.0F;
  device.sumActivePowerW = 0.0F;
  device.sumApparentPowerVa = 0.0F;
  device.sumPowerFactor = 0.0F;
  device.aggregateCount = 0;
}

void addAggregateSample(DeviceRecord& device) {
  const uint32_t now = currentUtc();
  if (now == 0U) return;
  const uint32_t window = now - (now % 60U);
  if (device.aggregateWindowUtc == 0U) device.aggregateWindowUtc = window;
  if (window != device.aggregateWindowUtc) {
    flushAggregate(device);
    device.aggregateWindowUtc = window;
  }
  device.sumVoltageV += device.voltageV;
  device.sumCurrentA += device.currentA;
  device.sumActivePowerW += device.activePowerW;
  device.sumApparentPowerVa += device.apparentPowerVa;
  device.sumPowerFactor += device.powerFactor;
  if (device.aggregateCount < UINT16_MAX) ++device.aggregateCount;
}

// ---- Per-second ("fine") history -------------------------------------------------------------
// PROPOSED (not yet in design.md). Rows go to /smartplug/s/<device_id>/<yyyymmdd>.csv (UTC day),
// one file per device and day, kept for kFineRetentionDays. Rows are queued in RAM and written in
// one batch every kFineFlushIntervalMs, so a flush opens each file once no matter how many rows it
// carries. The sampling step (1, 2, 5, 10 s) stretches automatically when a flush gets slow, which
// is how the server stays responsive as the number of SmartPlugs grows. The per-minute file above
// stays the source for long ranges (5m/1h/1d).
constexpr char kFineRoot[] = "/smartplug/s";
constexpr uint32_t kFineRetentionDays = 30U;
constexpr uint32_t kFineFlushIntervalMs = 10000UL;
constexpr size_t kFineQueueCapacity = 160;
constexpr uint8_t kFineLadder[] = {1, 2, 5, 10};
constexpr size_t kFineMaxFilesPerDevice = 64;

struct FineRow {
  uint32_t utc;
  float v, a, w, va, pf, e;
  uint8_t device;
  bool written;
};
FineRow fineQueue[kFineQueueCapacity];
size_t fineQueueCount = 0;
uint32_t fineDropped = 0;
uint32_t fineLastFlushMs = 0;
uint8_t fineLadderIndex = 0;
uint8_t fineFastFlushes = 0;
bool fineInitDone = false;
uint32_t fineLastPruneDay = 0;
uint64_t fineBytesByDevice[kMaxDevices] = {};

void fineDayText(const uint32_t utc, char (&out)[9]) {
  time_t t = static_cast<time_t>(utc);
  struct tm parts;
  gmtime_r(&t, &parts);
  snprintf(out, sizeof(out), "%04d%02d%02d", parts.tm_year + 1900, parts.tm_mon + 1, parts.tm_mday);
}

String fineDeviceDir(const char* id) { return String(kFineRoot) + "/" + id; }

// Lists a device's day files (name only, e.g. "20261009.csv") with their sizes.
size_t listFineFiles(const char* id, String (&names)[kFineMaxFilesPerDevice],
                     uint32_t (&sizes)[kFineMaxFilesPerDevice]) {
  size_t count = 0;
  const String dirPath = fineDeviceDir(id);
  if (!sdReady || !SD.exists(dirPath)) return 0;
  File dir = SD.open(dirPath);
  if (!dir) return 0;
  while (count < kFineMaxFilesPerDevice) {
    File entry = dir.openNextFile();
    if (!entry) break;
    if (!entry.isDirectory()) {
      String name = entry.name();
      const int slash = name.lastIndexOf('/');
      if (slash >= 0) name = name.substring(slash + 1);
      names[count] = name;
      sizes[count] = static_cast<uint32_t>(entry.size());
      ++count;
    }
    entry.close();
  }
  dir.close();
  return count;
}

void enqueueFineRow(DeviceRecord& device) {
  if (device.fineCount == 0U || device.fineWindowUtc == 0U) return;
  const float divisor = static_cast<float>(device.fineCount);
  if (fineQueueCount < kFineQueueCapacity && &device >= devices && &device < devices + kMaxDevices) {
    fineQueue[fineQueueCount++] = {device.fineWindowUtc, device.fineSumV / divisor, device.fineSumA / divisor,
                                   device.fineSumW / divisor, device.fineSumVa / divisor,
                                   device.fineSumPf / divisor, device.energyWh,
                                   static_cast<uint8_t>(&device - devices), false};
  } else {
    ++fineDropped;
  }
  device.fineSumV = device.fineSumA = device.fineSumW = device.fineSumVa = device.fineSumPf = 0.0F;
  device.fineCount = 0U;
}

void addFineSample(DeviceRecord& device) {
  const uint32_t now = currentUtc();
  if (now == 0U) return;
  const uint32_t step = kFineLadder[fineLadderIndex];
  const uint32_t window = now - (now % step);
  if (device.fineWindowUtc == 0U) device.fineWindowUtc = window;
  if (window != device.fineWindowUtc) {
    enqueueFineRow(device);
    device.fineWindowUtc = window;
  }
  device.fineSumV += device.voltageV;
  device.fineSumA += device.currentA;
  device.fineSumW += device.activePowerW;
  device.fineSumVa += device.apparentPowerVa;
  device.fineSumPf += device.powerFactor;
  if (device.fineCount < UINT16_MAX) ++device.fineCount;
}

void flushFineQueue() {
  if (fineQueueCount == 0 || !sdReady) return;
  const uint32_t startedMs = millis();
  if (!SD.exists(kFineRoot)) SD.mkdir(kFineRoot);
  for (size_t i = 0; i < fineQueueCount; ++i) {
    if (fineQueue[i].written) continue;
    const uint8_t dev = fineQueue[i].device;
    char day[9];
    fineDayText(fineQueue[i].utc, day);
    const String dir = fineDeviceDir(devices[dev].id);
    if (!SD.exists(dir)) SD.mkdir(dir);
    File file = SD.open(dir + "/" + day + ".csv", FILE_APPEND);
    if (!file) break;  // keep the rest queued and retry on the next flush
    for (size_t j = i; j < fineQueueCount; ++j) {
      if (fineQueue[j].written || fineQueue[j].device != dev) continue;
      char rowDay[9];
      fineDayText(fineQueue[j].utc, rowDay);
      if (strcmp(rowDay, day) != 0) continue;
      const size_t n = file.printf("%lu,%.3f,%.4f,%.2f,%.2f,%.3f,%.3f\n",
                                   static_cast<unsigned long>(fineQueue[j].utc), fineQueue[j].v, fineQueue[j].a,
                                   fineQueue[j].w, fineQueue[j].va, fineQueue[j].pf, fineQueue[j].e);
      fineBytesByDevice[dev] += n;
      fineQueue[j].written = true;
    }
    file.close();
  }
  size_t kept = 0;
  for (size_t i = 0; i < fineQueueCount; ++i) {
    if (!fineQueue[i].written) fineQueue[kept++] = fineQueue[i];
  }
  fineQueueCount = kept;

  // Self-tuning cadence: a slow flush means the SD card (or the number of SmartPlugs) cannot keep
  // up at this step, so sample less often; many fast flushes in a row allow going back down.
  const uint32_t duration = millis() - startedMs;
  if (duration > 150UL && static_cast<size_t>(fineLadderIndex) + 1U < sizeof(kFineLadder)) {
    ++fineLadderIndex;
    fineFastFlushes = 0;
    Serial.printf("INFO fine_history_step_s=%u flush_ms=%lu\n", static_cast<unsigned>(kFineLadder[fineLadderIndex]),
                  static_cast<unsigned long>(duration));
  } else if (duration < 40UL) {
    if (++fineFastFlushes >= 30U && fineLadderIndex > 0) {
      --fineLadderIndex;
      fineFastFlushes = 0;
      Serial.printf("INFO fine_history_step_s=%u flush_ms=%lu\n", static_cast<unsigned>(kFineLadder[fineLadderIndex]),
                    static_cast<unsigned long>(duration));
    }
  } else {
    fineFastFlushes = 0;
  }
}

void pruneFineFiles(const uint32_t utc) {
  if (!sdReady || utc <= kFineRetentionDays * 86400UL) return;
  char cutoff[9];
  fineDayText(utc - kFineRetentionDays * 86400UL, cutoff);
  static String names[kFineMaxFilesPerDevice];
  static uint32_t sizes[kFineMaxFilesPerDevice];
  for (size_t d = 0; d < kMaxDevices; ++d) {
    if (!devices[d].used) continue;
    const size_t count = listFineFiles(devices[d].id, names, sizes);
    for (size_t i = 0; i < count; ++i) {
      if (names[i].length() < 8 || names[i].substring(0, 8) >= String(cutoff)) continue;
      if (SD.remove(fineDeviceDir(devices[d].id) + "/" + names[i])) {
        fineBytesByDevice[d] = fineBytesByDevice[d] > sizes[i] ? fineBytesByDevice[d] - sizes[i] : 0U;
      }
    }
  }
}

// Removes every per-second file (history reset). Returns the bytes freed.
uint64_t eraseFineFiles() {
  uint64_t freed = 0;
  static String names[kFineMaxFilesPerDevice];
  static uint32_t sizes[kFineMaxFilesPerDevice];
  for (size_t d = 0; d < kMaxDevices; ++d) {
    if (!devices[d].used) continue;
    const size_t count = listFineFiles(devices[d].id, names, sizes);
    for (size_t i = 0; i < count; ++i) {
      if (SD.remove(fineDeviceDir(devices[d].id) + "/" + names[i])) freed += sizes[i];
    }
  }
  memset(fineBytesByDevice, 0, sizeof(fineBytesByDevice));
  fineQueueCount = 0;
  for (DeviceRecord& device : devices) {
    device.fineSumV = device.fineSumA = device.fineSumW = device.fineSumVa = device.fineSumPf = 0.0F;
    device.fineCount = 0U;
    device.fineWindowUtc = 0U;
  }
  return freed;
}

void serviceFineHistory() {
  if (!sdReady) return;
  if (!fineInitDone) {
    fineInitDone = true;
    static String names[kFineMaxFilesPerDevice];
    static uint32_t sizes[kFineMaxFilesPerDevice];
    for (size_t d = 0; d < kMaxDevices; ++d) {
      if (!devices[d].used) continue;
      const size_t count = listFineFiles(devices[d].id, names, sizes);
      uint64_t total = 0;
      for (size_t i = 0; i < count; ++i) total += sizes[i];
      fineBytesByDevice[d] = total;
    }
  }
  const uint32_t nowMs = millis();
  if (nowMs - fineLastFlushMs >= kFineFlushIntervalMs || fineQueueCount + 16U >= kFineQueueCapacity) {
    fineLastFlushMs = nowMs;
    flushFineQueue();
  }
  const uint32_t utc = currentUtc();
  if (utc != 0U && utc / 86400UL != fineLastPruneDay) {
    fineLastPruneDay = utc / 86400UL;
    pruneFineFiles(utc);
  }
}

bool replaceFileAtomically(const char* temporaryPath, const char* activePath,
                           const char* backupPath) {
  if (SD.exists(backupPath)) SD.remove(backupPath);
  if (SD.exists(activePath) && !SD.rename(activePath, backupPath)) return false;
  if (!SD.rename(temporaryPath, activePath)) {
    if (SD.exists(backupPath)) SD.rename(backupPath, activePath);
    return false;
  }
  if (SD.exists(backupPath)) SD.remove(backupPath);
  return true;
}

// An SD write failure is not treated as a successful mounted volume.  Demoting
// readiness makes `/health` truthful and makes serviceStorage() retry a clean
// SD.begin() after its normal bounded retry interval.  Keep the last known
// snapshot metadata for diagnostics; it is explicitly historical once
// sd_ready is false.
void recordSnapshotWriteFailure(const char* reason) {
  ++snapshotWriteFailures;
  lastSnapshotWriteFailureMs = millis();
  Serial.printf("WARN snapshot_write_failed reason=%s; demoting_sd\n", reason);
  if (sdReady) {
    SD.end();
    sdReady = false;
  }
  lastSdMountAttemptMs = millis();
}

bool restoreBackupIfNeeded(const char* activePath, const char* backupPath) {
  if (SD.exists(activePath)) return true;
  return SD.exists(backupPath) && SD.rename(backupPath, activePath);
}

bool saveDeviceIndex() {
  if (!sdReady) return false;
  SD.mkdir("/smartplug");
  if (SD.exists(kIndexTemporaryPath)) SD.remove(kIndexTemporaryPath);
  if (SD.exists(kScheduleTemporaryPath)) SD.remove(kScheduleTemporaryPath);
  File file = SD.open(kIndexTemporaryPath, FILE_WRITE);
  File scheduleFile = SD.open(kScheduleTemporaryPath, FILE_WRITE);
  if (!file || !scheduleFile) {
    if (file) file.close();
    if (scheduleFile) scheduleFile.close();
    if (SD.exists(kIndexTemporaryPath)) SD.remove(kIndexTemporaryPath);
    if (SD.exists(kScheduleTemporaryPath)) SD.remove(kScheduleTemporaryPath);
    return false;
  }
  for (const DeviceRecord& device : devices) {
    if (!device.used) continue;
    // The final field was added after the earlier timer-expiry fields. Older
    // index rows omit it and therefore remain backward compatible; only a
    // reset created by this firmware can be replayed after a server restart.
    file.printf("%s,%.3f,%s,%lu,%lu,%lu,%lu,%u,%u,%u,%u\n", device.id, device.energyWh, device.relayState,
                static_cast<unsigned long>(device.timerDeadlineUtc),
                static_cast<unsigned long>(device.timerDurationSeconds),
                static_cast<unsigned long>(device.lastEnergyResetUtc),
                static_cast<unsigned long>(device.pendingScheduleDueUtc),
                device.pendingScheduleTurnOn ? 1U : 0U,
                static_cast<unsigned>(device.timerExpiryAttempts),
                device.timerExpiryFailed ? 1U : 0U,
                device.awaitingEnergyReset ? 1U : 0U);
    // Schedule records are kept separately so an interrupted older index write
    // cannot make the core device record unreadable.
    scheduleFile.printf("%s,%u,%d", device.id, device.scheduleEnabled ? 1U : 0U,
                        static_cast<int>(device.timezoneOffsetMinutes));
    for (uint8_t index = 0; index < device.scheduleCount; ++index) {
      const auto& entry = device.schedules[index];
      scheduleFile.printf(",%u:%u:%u:%s", entry.hour, entry.minute,
                          entry.turnOn ? 1U : 0U, entry.event);
    }
    scheduleFile.println();
  }
  file.close();
  scheduleFile.close();
  if (!replaceFileAtomically(kIndexTemporaryPath, kIndexPath, kIndexBackupPath) ||
      !replaceFileAtomically(kScheduleTemporaryPath, kSchedulePath, kScheduleBackupPath)) {
    return false;
  }
  indexDirty = false;
  lastIndexFlushAtMs = millis();
  return true;
}

// Snapshots are deliberately separate from the low-frequency device index.  The
// index contains configuration/timer state, whereas this file is a complete
// live recovery record written at the one-second telemetry cadence.  A temporary
// file plus backup makes an interrupted SD write recoverable on the next boot.
bool saveSnapshots() {
  ++snapshotWriteAttempts;
  if (!sdReady) {
    Serial.println(F("WARN snapshot_write_skipped_sd_unavailable"));
    recordSnapshotWriteFailure("sd_unavailable");
    return false;
  }
  if (!SD.mkdir("/smartplug") && !SD.exists("/smartplug")) {
    recordSnapshotWriteFailure("mkdir_failed");
    return false;
  }
  if (SD.exists(kSnapshotTemporaryPath)) SD.remove(kSnapshotTemporaryPath);
  File file = SD.open(kSnapshotTemporaryPath, FILE_WRITE);
  if (!file) {
    recordSnapshotWriteFailure("open_failed");
    return false;
  }
  for (const DeviceRecord& device : devices) {
    if (!device.used) continue;
    if (file.printf("%s,%lu,%.3f,%.4f,%.2f,%.2f,%.3f,%.3f,%s,%u\n",
                    device.id, static_cast<unsigned long>(device.lastSeenUtc),
                    device.voltageV, device.currentA, device.activePowerW,
                    device.apparentPowerVa, device.powerFactor, device.energyWh,
                    device.relayState, device.calibrated ? 1U : 0U) == 0U) {
      file.close();
      recordSnapshotWriteFailure("write_failed");
      return false;
    }
  }
  file.close();
  if (!replaceFileAtomically(kSnapshotTemporaryPath, kSnapshotPath, kSnapshotBackupPath)) {
    recordSnapshotWriteFailure("atomic_replace_failed");
    return false;
  }
  // FAT may not publish a stream's final size until close().  Reading size before
  // closing made health report zero bytes even when the atomic snapshot existed.
  File persisted = SD.open(kSnapshotPath, FILE_READ);
  if (!persisted) {
    recordSnapshotWriteFailure("reopen_failed");
    return false;
  }
  lastKnownSnapshotFileBytes = static_cast<uint32_t>(persisted.size());
  persisted.close();
  snapshotDirty = false;
  const uint32_t nowMs = millis();
  lastSnapshotWriteCadenceMs = lastSnapshotWriteSuccessMs == 0U
      ? 0U : nowMs - lastSnapshotWriteSuccessMs;
  lastSnapshotWriteSuccessMs = nowMs;
  lastSnapshotWriteSuccessUtc = currentUtc();
  ++snapshotWriteSuccesses;
  lastSnapshotFlushAtMs = nowMs;
  return true;
}

uint8_t activeDeviceCount() {
  uint8_t count = 0U;
  for (const DeviceRecord& device : devices) if (device.used) ++count;
  return count;
}

void loadDeviceIndex() {
  if (!sdReady) return;
  restoreBackupIfNeeded(kIndexPath, kIndexBackupPath);
  restoreBackupIfNeeded(kSchedulePath, kScheduleBackupPath);
  if (!SD.exists(kIndexPath)) return;
  File file = SD.open(kIndexPath, FILE_READ);
  if (!file) return;
  while (file.available()) {
    const String line = file.readStringUntil('\n');
    const int firstComma = line.indexOf(',');
    const int secondComma = line.indexOf(',', firstComma + 1);
    if (firstComma < 0 || secondComma < 0) continue;
    const String id = line.substring(0, firstComma);
    DeviceRecord* device = findDevice(id, true);
    if (device == nullptr) continue;
    const float savedEnergy = line.substring(firstComma + 1, secondComma).toFloat();
    if (isfinite(savedEnergy) && savedEnergy >= device->energyWh) {
      device->energyWh = savedEnergy;
    }
    const int thirdComma = line.indexOf(',', secondComma + 1);
    const String relay = line.substring(secondComma + 1, thirdComma < 0 ? line.length() : thirdComma);
    if (relay == "on" || relay == "off" || relay == "unknown") {
      copyText(device->relayState, relay);
    }
    if (thirdComma < 0) continue;
    const int fourthComma = line.indexOf(',', thirdComma + 1);
    device->timerDeadlineUtc = strtoul(line.substring(thirdComma + 1,
                                                       fourthComma < 0 ? line.length() : fourthComma).c_str(), nullptr, 10);
    if (fourthComma < 0) continue;
    const int fifthComma = line.indexOf(',', fourthComma + 1);
    device->timerDurationSeconds = strtoul(line.substring(fourthComma + 1,
                                                          fifthComma < 0 ? line.length() : fifthComma).c_str(), nullptr, 10);
    if (fifthComma >= 0) {
      const int sixthComma = line.indexOf(',', fifthComma + 1);
      device->lastEnergyResetUtc = strtoul(line.substring(fifthComma + 1,
          sixthComma < 0 ? line.length() : sixthComma).c_str(), nullptr, 10);
      if (sixthComma >= 0) {
        const int seventhComma = line.indexOf(',', sixthComma + 1);
        device->pendingScheduleDueUtc = strtoul(line.substring(sixthComma + 1,
            seventhComma < 0 ? line.length() : seventhComma).c_str(), nullptr, 10);
        if (seventhComma >= 0) {
          const int eighthComma = line.indexOf(',', seventhComma + 1);
          device->pendingScheduleTurnOn = line.substring(seventhComma + 1,
              eighthComma < 0 ? line.length() : eighthComma).toInt() != 0;
          if (eighthComma >= 0) {
            const int ninthComma = line.indexOf(',', eighthComma + 1);
            device->timerExpiryAttempts = static_cast<uint8_t>(min(255L,
                line.substring(eighthComma + 1,
                    ninthComma < 0 ? line.length() : ninthComma).toInt()));
            if (ninthComma >= 0) {
              const int tenthComma = line.indexOf(',', ninthComma + 1);
              device->timerExpiryFailed = line.substring(ninthComma + 1,
                  tenthComma < 0 ? line.length() : tenthComma).toInt() != 0;
              if (tenthComma >= 0) {
                device->awaitingEnergyReset = line.substring(tenthComma + 1).toInt() != 0;
              }
            }
          }
        }
      }
    }
  }
  file.close();
  if (!SD.exists(kSchedulePath)) return;
  File scheduleFile = SD.open(kSchedulePath, FILE_READ);
  if (!scheduleFile) return;
  while (scheduleFile.available()) {
    const String line = scheduleFile.readStringUntil('\n');
    const int firstComma = line.indexOf(',');
    const int secondComma = line.indexOf(',', firstComma + 1);
    if (firstComma < 0 || secondComma < 0) continue;
    DeviceRecord* device = findDevice(line.substring(0, firstComma), false);
    if (device == nullptr) continue;
    device->scheduleEnabled = line.substring(firstComma + 1, secondComma).toInt() != 0;
    int cursor = secondComma + 1;
    int nextComma = line.indexOf(',', cursor);
    const int offset = line.substring(cursor, nextComma < 0 ? line.length() : nextComma).toInt();
    device->timezoneOffsetMinutes = static_cast<int16_t>(constrain(offset, -720, 840));
    device->scheduleCount = 0;
    while (nextComma >= 0 && device->scheduleCount < kMaxDailySchedules) {
      cursor = nextComma + 1;
      nextComma = line.indexOf(',', cursor);
      const String encoded = line.substring(cursor, nextComma < 0 ? line.length() : nextComma);
      const int firstColon = encoded.indexOf(':');
      const int secondColon = encoded.indexOf(':', firstColon + 1);
      if (firstColon < 0 || secondColon < 0) continue;
      const int hour = encoded.substring(0, firstColon).toInt();
      const int minute = encoded.substring(firstColon + 1, secondColon).toInt();
      const int thirdColon = encoded.indexOf(':', secondColon + 1);
      const int state = encoded.substring(secondColon + 1,
                                          thirdColon < 0 ? encoded.length() : thirdColon).toInt();
      if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || (state != 0 && state != 1)) continue;
      auto& entry = device->schedules[device->scheduleCount++];
      entry.hour = static_cast<uint8_t>(hour);
      entry.minute = static_cast<uint8_t>(minute);
      entry.turnOn = state == 1;
      if (thirdColon >= 0) copyText(entry.event, encoded.substring(thirdColon + 1));
    }
  }
  scheduleFile.close();
}

void loadSnapshots() {
  if (!sdReady) return;
  restoreBackupIfNeeded(kSnapshotPath, kSnapshotBackupPath);
  if (!SD.exists(kSnapshotPath)) return;
  File file = SD.open(kSnapshotPath, FILE_READ);
  if (!file) return;
  lastKnownSnapshotFileBytes = static_cast<uint32_t>(file.size());
  while (file.available()) {
    const String line = file.readStringUntil('\n');
    const int firstComma = line.indexOf(',');
    if (firstComma < 0) continue;
    DeviceRecord* device = findDevice(line.substring(0, firstComma), true);
    if (device == nullptr) continue;
    const int secondComma = line.indexOf(',', firstComma + 1);
    const int thirdComma = line.indexOf(',', secondComma + 1);
    const int fourthComma = line.indexOf(',', thirdComma + 1);
    const int fifthComma = line.indexOf(',', fourthComma + 1);
    const int sixthComma = line.indexOf(',', fifthComma + 1);
    const int seventhComma = line.indexOf(',', sixthComma + 1);
    const int eighthComma = line.indexOf(',', seventhComma + 1);
    const int ninthComma = line.indexOf(',', eighthComma + 1);
    if (secondComma < 0 || thirdComma < 0 || fourthComma < 0 || fifthComma < 0 ||
        sixthComma < 0 || seventhComma < 0 || eighthComma < 0 || ninthComma < 0) continue;
    const float voltage = line.substring(secondComma + 1, thirdComma).toFloat();
    const float current = line.substring(thirdComma + 1, fourthComma).toFloat();
    const float active = line.substring(fourthComma + 1, fifthComma).toFloat();
    const float apparent = line.substring(fifthComma + 1, sixthComma).toFloat();
    const float pf = line.substring(sixthComma + 1, seventhComma).toFloat();
    const float energy = line.substring(seventhComma + 1, eighthComma).toFloat();
    const String relay = line.substring(eighthComma + 1, ninthComma);
    if (!isfinite(voltage) || !isfinite(current) || !isfinite(active) ||
        !isfinite(apparent) || !isfinite(pf) || !isfinite(energy) || energy < 0.0F) continue;
    const uint32_t snapshotSeenUtc = strtoul(line.substring(firstComma + 1, secondComma).c_str(), nullptr, 10);
    // The index can be newer than a snapshot (or live telemetry may already
    // have arrived after an SD hot-insert).  Never let an older snapshot roll
    // its live fields backwards.  Energy is cumulative unless a separately
    // recorded reset is newer than this snapshot.
    const bool snapshotIsCurrent = device->lastSeenUtc == 0U || snapshotSeenUtc >= device->lastSeenUtc;
    const bool resetAfterSnapshot = device->lastEnergyResetUtc != 0U &&
        device->lastEnergyResetUtc > snapshotSeenUtc;
    if (!snapshotIsCurrent) continue;
    device->lastSeenUtc = snapshotSeenUtc;
    device->voltageV = voltage;
    device->currentA = current;
    device->activePowerW = active;
    device->apparentPowerVa = apparent;
    device->powerFactor = pf;
    device->energyWh = resetAfterSnapshot ? device->energyWh : max(device->energyWh, energy);
    device->calibrated = line.substring(ninthComma + 1).toInt() != 0;
    if (relay == "on" || relay == "off" || relay == "unknown") copyText(device->relayState, relay);
    // This is a historical display/recovery record, never proof that the
    // current socket is energised after the server itself restarts.
    device->online = false;
  }
  file.close();
}

void markRuntimeStateForStoragePersistence() {
  // A successful hot-remount deliberately does not read persisted device data.
  // Force the current in-memory index and latest snapshots back to the card on
  // the next storage service pass, rather than waiting a whole normal flush
  // interval after storage has recovered.
  indexDirty = true;
  snapshotDirty = true;
  const uint32_t now = millis();
  lastIndexFlushAtMs = now - kIndexFlushIntervalMs;
  lastSnapshotFlushAtMs = now - kSnapshotFlushIntervalMs;
}

void beginStorage() {
  // This decision must be made before SD.begin().  If any RAM state became
  // dirty while the card was unavailable, it is newer than the card and must
  // be persisted, not replaced.  `storageRecoveryHandled` also keeps a later
  // remount from replaying stale files after a normal cold-start restoration.
  const bool restoreColdBootRecovery = !storageRecoveryHandled &&
      !indexDirty && !snapshotDirty;
  lastSdMountAttemptMs = millis();
  sdSpi.begin(SERVER_SMARTPLUG_SD_SCK, SERVER_SMARTPLUG_SD_MISO,
              SERVER_SMARTPLUG_SD_MOSI, SERVER_SMARTPLUG_SD_CS);
  sdReady = SD.begin(SERVER_SMARTPLUG_SD_CS, sdSpi);
  if (!sdReady || SD.cardType() == CARD_NONE) {
    if (sdReady) SD.end();
    sdReady = false;
    Serial.println(F("WARN sd_unavailable"));
    return;
  }
  const uint8_t cardType = SD.cardType();
  Serial.printf("INFO sd_ready type=%u size_mb=%llu\\n", cardType,
                static_cast<unsigned long long>(SD.cardSize() / (1024ULL * 1024ULL)));
  SD.mkdir("/smartplug");
  if (restoreColdBootRecovery) {
    loadDeviceIndex();
    loadSnapshots();
    storageRecoveryHandled = true;
    Serial.println(F("INFO sd_cold_boot_recovery_loaded"));
    return;
  }

  // The SD mount recovered after runtime had started (or after RAM changed
  // while the card was unavailable).  Do not let stale files change relay
  // information, timer state, schedule/index state, or live measurements.
  storageRecoveryHandled = true;
  markRuntimeStateForStoragePersistence();
  Serial.println(F("INFO sd_hot_remount_preserved_ram_state"));
}

// Retired MQTT packet parser.  It remains below temporarily only as a source
// migration reference; it is not compiled or linked.  Production MQTT is
// provided by PicoMQTT (a maintained ESP32 MQTT 3.1.1 broker library).
#if 0
bool topicMatches(const String& filter, const String& topic) {
  if (filter == "#") return true;
  int filterStart = 0;
  int topicStart = 0;
  while (true) {
    const int filterSlash = filter.indexOf('/', filterStart);
    const int topicSlash = topic.indexOf('/', topicStart);
    const String filterLevel = filter.substring(filterStart,
                                                filterSlash < 0 ? filter.length() : filterSlash);
    const String topicLevel = topic.substring(topicStart,
                                              topicSlash < 0 ? topic.length() : topicSlash);
    if (filterLevel == "#") return filterSlash < 0;
    if (filterLevel != "+" && filterLevel != topicLevel) return false;
    if (filterSlash < 0 || topicSlash < 0) return filterSlash < 0 && topicSlash < 0;
    filterStart = filterSlash + 1;
    topicStart = topicSlash + 1;
  }
}

String mqttPacket(const uint8_t header, const String& topic, const String& payload,
                  const uint8_t qos, const bool retain) {
  String variable;
  const uint16_t topicLength = topic.length();
  variable.reserve(topicLength + payload.length() + 8);
  variable += static_cast<char>((topicLength >> 8U) & 0xFFU);
  variable += static_cast<char>(topicLength & 0xFFU);
  variable += topic;
  if (qos > 0U) {
    variable += static_cast<char>(0);
    variable += static_cast<char>(1);
  }
  variable += payload;
  String packet;
  packet.reserve(variable.length() + 5);
  packet += static_cast<char>(header | (qos << 1U) | (retain ? 1U : 0U));
  size_t remaining = variable.length();
  do {
    uint8_t encoded = remaining % 128U;
    remaining /= 128U;
    if (remaining > 0U) encoded |= 0x80U;
    packet += static_cast<char>(encoded);
  } while (remaining > 0U);
  packet += variable;
  return packet;
}

void sendMqttPacket(MqttSlot& slot, const String& packet) {
  if (slot.occupied && slot.tcp.connected()) {
    slot.tcp.write(reinterpret_cast<const uint8_t*>(packet.c_str()), packet.length());
  }
}

void sendSimpleMqtt(MqttSlot& slot, const uint8_t header, const uint8_t value = 0U) {
  uint8_t packet[2] = {header, 0U};
  if (header == 0x20U) {
    uint8_t connack[4] = {0x20U, 0x02U, 0x00U, value};
    slot.tcp.write(connack, sizeof(connack));
  } else if (header == 0x40U) {
    uint8_t puback[4] = {0x40U, 0x02U, 0x00U, value};
    slot.tcp.write(puback, sizeof(puback));
  } else {
    slot.tcp.write(packet, sizeof(packet));
  }
}

void forwardMqttPublish(const String& topic, const String& payload, const bool retain,
                        const uint8_t qos, const int skipSlot = -1) {
  const String packet = mqttPacket(0x30U, topic, payload, qos, retain);
  for (uint8_t index = 0; index < kMaxMqttClients; ++index) {
    MqttSlot& slot = mqttSlots[index];
    if (!slot.connected || static_cast<int>(index) == skipSlot) continue;
    for (uint8_t subscription = 0; subscription < slot.subscriptionCount; ++subscription) {
      if (topicMatches(slot.subscriptions[subscription], topic)) {
        sendMqttPacket(slot, packet);
        break;
      }
    }
  }
}

void storeRetained(const String& topic, const String& payload, const uint8_t qos) {
  RetainedMessage* selected = nullptr;
  for (RetainedMessage& message : retainedMessages) {
    if (message.used && topic == message.topic) {
      selected = &message;
      break;
    }
    if (!message.used && selected == nullptr) selected = &message;
  }
  if (selected == nullptr) selected = &retainedMessages[0];
  if (payload.isEmpty()) {
    *selected = RetainedMessage{};
    return;
  }
  selected->used = true;
  selected->qos = qos;
  copyText(selected->topic, topic);
  copyText(selected->payload, payload);
}

void publishEnergySync(DeviceRecord& device, const bool reset = false) {
  if (!device.used) return;
  const String base = String("smartplug/") + device.id;
  const String payload = String("{\"device_id\":\"") + device.id +
                         "\",\"energy_wh\":" + jsonNumber(device.energyWh, 3) +
                         ",\"recorded_at_ms\":" + String(millis()) +
                         ",\"reset\":" + (reset ? "true" : "false") + "}";
  forwardMqttPublish(base + "/sync/energy", payload, false, 0U);
}

void publishSnapshot(DeviceRecord& device) {
  if (!device.used) return;
  const String base = String("smartplug/") + device.id;
  // This is recovery data only.  In particular, relay_state is informational:
  // SmartPlug must never actuate its relay because of a recovered snapshot.
  const String payload = String("{\"version\":1,\"device_id\":\"") + device.id +
      "\",\"timestamp_utc\":" + String(device.lastSeenUtc) +
      ",\"voltage_v\":" + jsonNumber(device.voltageV, 3) +
      ",\"current_a\":" + jsonNumber(device.currentA, 4) +
      ",\"active_power_w\":" + jsonNumber(device.activePowerW, 2) +
      ",\"apparent_power_va\":" + jsonNumber(device.apparentPowerVa, 2) +
      ",\"power_factor\":" + jsonNumber(device.powerFactor, 3) +
      ",\"energy_wh\":" + jsonNumber(device.energyWh, 3) +
      ",\"energy_kwh\":" + jsonNumber(device.energyWh / 1000.0F, 6) +
      ",\"relay_state\":\"" + device.relayState + "\"}";
  forwardMqttPublish(base + "/sync/snapshot", payload, false, 0U);
}

String jsonField(const String& body, const char* key) {
  JsonDocument document;
  if (deserializeJson(document, body)) return String();
  const JsonVariant value = document[key];
  return value.is<const char*>() ? String(value.as<const char*>()) : String();
}

// A timer configured while relay OFF is only armed.  The authoritative
// relay-state publication or accepted relay acknowledgement starts its
// deadline once the SmartPlug has actually reached ON.
void startArmedTimerAfterRelayOn(DeviceRecord& device) {
  if (strcmp(device.relayState, "on") != 0 || device.timerDeadlineUtc != 0U ||
      device.timerDurationSeconds == 0U) return;
  const uint32_t now = currentUtc();
  if (now == 0U) return;
  device.timerDeadlineUtc = now + device.timerDurationSeconds;
  device.timerDurationSeconds = 0U;
  indexDirty = true;
  snapshotDirty = true;
  Serial.printf("INFO timer_started id=%s deadline=%lu\n", device.id,
                static_cast<unsigned long>(device.timerDeadlineUtc));
}

void handleRelayAcknowledgement(DeviceRecord& device, const String& payload) {
  JsonDocument document;
  if (deserializeJson(document, payload)) return;
  const bool accepted = document["accepted"] | false;
  const String state = document["state"] | "";
  if (state == "on" || state == "off") {
    copyText(device.relayState, state);
    // A timer configured while the relay was off is armed, not running. Start it only
    // after the device itself confirms that the relay is on.
    if (state == "on" && device.timerDeadlineUtc == 0U && device.timerDurationSeconds > 0U) {
      const uint32_t now = currentUtc();
      if (now != 0U) {
        device.timerDeadlineUtc = now + device.timerDurationSeconds;
        device.timerDurationSeconds = 0U;
        indexDirty = true;
      }
    }
  }
  if (strcmp(device.commandStatus, "queued") != 0) return;
  const bool timerExpiryCommand = device.timerExpiryPending &&
      strcmp(device.commandState, "off") == 0;
  if (accepted && state == device.commandState) {
    copyLiteral(device.commandStatus, "completed");
    if (timerExpiryCommand) {
      // Only the accepted acknowledgement of the expiry OFF command consumes
      // the deadline. A stale snapshot, telemetry state, rejected command, or
      // unanswered MQTT publish must leave it recoverable and retryable.
      device.timerDeadlineUtc = 0U;
      device.timerDurationSeconds = 0U;
      device.timerExpiryAttempts = 0U;
      device.timerExpiryFailed = false;
      device.timerExpiryPending = false;
      device.timerExpiryLastAttemptMs = 0U;
      indexDirty = true;
      Serial.printf("INFO timer_expiry_completed id=%s\n", device.id);
    }
  } else {
    copyLiteral(device.commandStatus, "rejected");
    if (timerExpiryCommand) {
      indexDirty = true;
      Serial.printf("WARN timer_expiry_rejected id=%s attempt=%u\n", device.id,
                    static_cast<unsigned>(device.timerExpiryAttempts));
    }
  }
  device.commandResolvedAtMs = millis();
}

void handleAllParameters(DeviceRecord& device, const String& payload) {
  JsonDocument document;
  if (deserializeJson(document, payload)) return;
  const JsonVariantConst voltage = document["voltage_v"];
  const JsonVariantConst current = document["current_a"];
  const JsonVariantConst activePower = document["active_power_w"];
  const JsonVariantConst apparentPower = document["apparent_power_va"];
  const JsonVariantConst powerFactor = document["power_factor"];
  const JsonVariantConst energy = document["energy_wh"];
  if (!voltage.is<float>() || !current.is<float>() || !activePower.is<float>() ||
      !apparentPower.is<float>() || !powerFactor.is<float>() || !energy.is<float>()) return;
  const float candidateEnergy = energy.as<float>();
  if (!isfinite(candidateEnergy) || candidateEnergy < 0.0F || candidateEnergy > 1000000.0F) return;
  device.voltageV = voltage.as<float>();
  device.currentA = current.as<float>();
  device.activePowerW = activePower.as<float>();
  device.apparentPowerVa = apparentPower.as<float>();
  device.powerFactor = powerFactor.as<float>();
  // During a server-authorized reset, ignore stale pre-reset telemetry until the
  // SmartPlug reports its newly reset counter. This keeps the server at zero
  // instead of briefly resurrecting the old total.
  if (device.awaitingEnergyReset) {
    if (candidateEnergy <= 1.0F) {
      device.energyWh = candidateEnergy;
      device.awaitingEnergyReset = false;
    }
  } else {
    // Normal telemetry is cumulative. A restart or old retained record cannot
    // lower the server-owned counter.
    device.energyWh = max(device.energyWh, candidateEnergy);
  }
  device.calibrated = document["calibrated"] | false;
  device.online = true;
  device.lastSeenMs = millis();
  device.lastSeenUtc = currentUtc();
  addAggregateSample(device);
  addFineSample(device);
  indexDirty = true;
  snapshotDirty = true;
}

void handleSingleMeasurement(DeviceRecord& device, const String& suffix,
                             const String& payload) {
  JsonDocument document;
  if (deserializeJson(document, payload)) return;
  const JsonVariantConst value = document["value"];
  if (!value.is<float>()) return;
  const float measurement = value.as<float>();
  if (!isfinite(measurement)) return;
  if (suffix == "measurement/voltage") device.voltageV = measurement;
  else if (suffix == "measurement/current") device.currentA = measurement;
  else if (suffix == "measurement/active-power") device.activePowerW = measurement;
  else if (suffix == "measurement/apparent-power") device.apparentPowerVa = measurement;
  else if (suffix == "measurement/power-factor") device.powerFactor = measurement;
  else if (suffix == "measurement/energy") {
    if (measurement < 0.0F || measurement > 1000000.0F) return;
    if (device.awaitingEnergyReset) {
      if (measurement <= 1.0F) {
        device.energyWh = measurement;
        device.awaitingEnergyReset = false;
      }
    } else {
      device.energyWh = max(device.energyWh, measurement);
    }
    indexDirty = true;
  } else {
    return;
  }
  // Individual topics update the current device view. The allparameters topic
  // is the one atomic sample used for one-minute aggregation.
  device.online = true;
  device.lastSeenMs = millis();
  device.lastSeenUtc = currentUtc();
  snapshotDirty = true;
}

void handleMqttApplicationMessage(const String& topic, const String& payload) {
  if (!topic.startsWith("smartplug/")) return;
  const int idStart = strlen("smartplug/");
  const int separator = topic.indexOf('/', idStart);
  if (separator < 0) return;
  const String deviceId = topic.substring(idStart, separator);
  DeviceRecord* device = findDevice(deviceId, true);
  if (device == nullptr) return;
  const String suffix = topic.substring(separator + 1);
  if (suffix == "measurement/allparameters") {
    handleAllParameters(*device, payload);
  } else if (suffix.startsWith("measurement/")) {
    handleSingleMeasurement(*device, suffix, payload);
  } else if (suffix == "availability") {
    const String state = payload;
    device->online = state == "online";
    device->lastSeenMs = millis();
    device->lastSeenUtc = currentUtc();
    snapshotDirty = true;
    // Kept for older firmware which only understands sync/energy.  New
    // firmware actively asks for sync/snapshot during its boot recovery.
    if (device->online) publishEnergySync(*device);
  } else if (suffix == "sync/request") {
    publishSnapshot(*device);
  } else if (suffix == "state") {
    const String relay = jsonField(payload, "relay");
    if (relay == "on" || relay == "off" || relay == "unknown") {
      copyText(device->relayState, relay);
      if (relay == "on" && device->timerDeadlineUtc == 0U && device->timerDurationSeconds > 0U) {
        const uint32_t now = currentUtc();
        if (now != 0U) {
          device->timerDeadlineUtc = now + device->timerDurationSeconds;
          device->timerDurationSeconds = 0U;
        }
      }
      indexDirty = true;
      snapshotDirty = true;
    }
  } else if (suffix == "ack/relay") {
    handleRelayAcknowledgement(*device, payload);
    indexDirty = true;
    snapshotDirty = true;
  }
}

void publishBrokerMessage(const String& topic, const String& payload,
                          const bool retain, const uint8_t qos,
                          const int skipSlot = -1) {
  if (retain) storeRetained(topic, payload, qos);
  handleMqttApplicationMessage(topic, payload);
  forwardMqttPublish(topic, payload, retain, qos, skipSlot);
}

bool slotOwnsTopic(const MqttSlot& slot, const String& topic) {
  return strlen(slot.deviceId) > 0 && topic.startsWith(String("smartplug/") + slot.deviceId + "/");
}

bool deviceMayPublish(const MqttSlot& slot, const String& topic) {
  if (!slotOwnsTopic(slot, topic)) return false;
  const String suffix = topic.substring((String("smartplug/") + slot.deviceId + "/").length());
  return suffix == "measurement/voltage" || suffix == "measurement/current" ||
         suffix == "measurement/active-power" || suffix == "measurement/apparent-power" ||
         suffix == "measurement/power-factor" || suffix == "measurement/energy" ||
         suffix == "measurement/allparameters" || suffix == "state" ||
          suffix == "availability" || suffix == "ack/relay" || suffix == "telemetry" ||
          suffix == "sync/request";
}

bool deviceMaySubscribe(const MqttSlot& slot, const String& filter) {
  if (!slotOwnsTopic(slot, filter)) return false;
  const String suffix = filter.substring((String("smartplug/") + slot.deviceId + "/").length());
  return suffix == "cmd/relay" || suffix == "cmd/factory-reset" || suffix == "sync/energy" ||
         suffix == "sync/snapshot";
}

bool decodeMqttString(const uint8_t* data, const size_t length, size_t& offset,
                      String& value, const size_t maximumLength) {
  if (offset + 2U > length) return false;
  const uint16_t textLength = (static_cast<uint16_t>(data[offset]) << 8U) | data[offset + 1U];
  offset += 2U;
  if (textLength > maximumLength || offset + textLength > length) return false;
  value = "";
  value.reserve(textLength);
  for (uint16_t i = 0; i < textLength; ++i) value += static_cast<char>(data[offset + i]);
  offset += textLength;
  return true;
}

void removeSlot(const uint8_t index, const bool unexpected) {
  MqttSlot& slot = mqttSlots[index];
  if (!slot.occupied) return;
  Serial.printf("INFO mqtt_session_closed unexpected=%u id=%s\n",
                unexpected ? 1U : 0U, slot.deviceId);
  if (unexpected && slot.connected && slot.willEnabled) {
    publishBrokerMessage(slot.willTopic, slot.willPayload, slot.willRetain, slot.willQos,
                         static_cast<int>(index));
  }
  slot.tcp.stop();
  slot = MqttSlot{};
}

bool processConnect(MqttSlot& slot, const uint8_t* body, const size_t length) {
  ++mqttConnectPackets;
  size_t offset = 0;
  String protocol;
  if (!decodeMqttString(body, length, offset, protocol, 8) || protocol != "MQTT" ||
      offset + 4U > length || body[offset++] != 4U) {
    sendSimpleMqtt(slot, 0x20U, 0x01U);
    return false;
  }
  const uint8_t flags = body[offset++];
  const uint16_t keepAlive = (static_cast<uint16_t>(body[offset]) << 8U) | body[offset + 1U];
  offset += 2U;
  if ((flags & 0x01U) != 0U || (flags & 0x40U) != 0U && (flags & 0x80U) == 0U) {
    sendSimpleMqtt(slot, 0x20U, 0x02U);
    return false;
  }
  String clientId;
  if (!decodeMqttString(body, length, offset, clientId, 63)) {
    sendSimpleMqtt(slot, 0x20U, 0x02U);
    return false;
  }
  const bool willFlag = (flags & 0x04U) != 0U;
  String willTopic;
  String willPayload;
  if (willFlag && (!decodeMqttString(body, length, offset, willTopic, 127) ||
                   !decodeMqttString(body, length, offset, willPayload, 383))) {
    sendSimpleMqtt(slot, 0x20U, 0x02U);
    return false;
  }
  String username;
  String password;
  if ((flags & 0x80U) != 0U && !decodeMqttString(body, length, offset, username, 32)) {
    sendSimpleMqtt(slot, 0x20U, 0x04U);
    return false;
  }
  if ((flags & 0x40U) != 0U && !decodeMqttString(body, length, offset, password, 64)) {
    sendSimpleMqtt(slot, 0x20U, 0x04U);
    return false;
  }
  if (!mqttCredentialsConfigured() || !constantTimeEquals(username, settings.brokerUsername) ||
      !constantTimeEquals(password, settings.brokerPassword)) {
    sendSimpleMqtt(slot, 0x20U, 0x04U);
    return false;
  }
  slot.connected = true;
  slot.cleanSession = (flags & 0x02U) != 0U;
  slot.keepAliveSeconds = keepAlive == 0U ? 30U : keepAlive;
  slot.lastRxAtMs = millis();
  copyText(slot.clientId, clientId);
  const String deviceId = clientId.startsWith("SmartPlug-")
                            ? clientId.substring(strlen("SmartPlug-")) : String();
  if (!validDeviceId(deviceId)) {
    sendSimpleMqtt(slot, 0x20U, 0x02U);
    return false;
  }
  // One connected identity owns one base topic. A reconnect replaces the old
  // socket without publishing its will, as this is a normal client takeover.
  for (uint8_t index = 0; index < kMaxMqttClients; ++index) {
    MqttSlot& existing = mqttSlots[index];
    if (&existing != &slot && existing.occupied && existing.connected &&
        deviceId == existing.deviceId) {
      removeSlot(index, false);
    }
  }
  copyText(slot.deviceId, deviceId);
  slot.willEnabled = willFlag;
  slot.willRetain = (flags & 0x20U) != 0U;
  slot.willQos = (flags >> 3U) & 0x03U;
  if (willFlag) {
    copyText(slot.willTopic, willTopic);
    copyText(slot.willPayload, willPayload);
  }
  sendSimpleMqtt(slot, 0x20U, 0U);
  ++mqttAcceptedMessages;
  Serial.printf("INFO mqtt_session_accepted id=%s\n", slot.deviceId);
  return true;
}

void sendSubscriptionAck(MqttSlot& slot, const uint16_t packetId, const uint8_t count) {
  String packet;
  packet += static_cast<char>(0x90U);
  packet += static_cast<char>(2U + count);
  packet += static_cast<char>((packetId >> 8U) & 0xFFU);
  packet += static_cast<char>(packetId & 0xFFU);
  for (uint8_t i = 0; i < count; ++i) packet += static_cast<char>(0U);
  sendMqttPacket(slot, packet);
}

void sendRetainedForSubscription(MqttSlot& slot, const String& filter) {
  for (const RetainedMessage& message : retainedMessages) {
    if (message.used && topicMatches(filter, message.topic)) {
      sendMqttPacket(slot, mqttPacket(0x30U, message.topic, message.payload,
                                      message.qos, true));
    }
  }
}

void processSubscribe(MqttSlot& slot, const uint8_t* body, const size_t length) {
  ++mqttSubscribePackets;
  if (length < 5U) return;
  size_t offset = 0;
  const uint16_t packetId = (static_cast<uint16_t>(body[offset]) << 8U) | body[offset + 1U];
  offset += 2U;
  const uint8_t firstSubscription = slot.subscriptionCount;
  uint8_t granted = 0;
  while (offset < length) {
    String filter;
    if (!decodeMqttString(body, length, offset, filter, 127) || offset >= length) break;
    ++offset;  // Requested QoS; this broker grants QoS 0.
    if (deviceMaySubscribe(slot, filter) && slot.subscriptionCount < kMaxSubscriptions) {
      copyText(slot.subscriptions[slot.subscriptionCount++], filter);
      ++granted;
    }
  }
  if (granted > 0U) {
    Serial.printf("INFO mqtt_subscribe id=%s granted=%u\n", slot.deviceId, granted);
    sendSubscriptionAck(slot, packetId, granted);
    for (uint8_t index = firstSubscription; index < slot.subscriptionCount; ++index) {
      sendRetainedForSubscription(slot, slot.subscriptions[index]);
    }
  }
}

void processPublish(MqttSlot& slot, const uint8_t header, const uint8_t* body,
                    const size_t length) {
  ++mqttPublishPackets;
  size_t offset = 0;
  String topic;
  if (!decodeMqttString(body, length, offset, topic, 127)) return;
  const uint8_t qos = (header >> 1U) & 0x03U;
  uint16_t packetId = 0;
  if (qos == 1U) {
    if (offset + 2U > length) return;
    packetId = (static_cast<uint16_t>(body[offset]) << 8U) | body[offset + 1U];
    offset += 2U;
  } else if (qos > 1U) {
    return;
  }
  if (offset > length || length - offset > 383U) return;
  if (!deviceMayPublish(slot, topic)) {
    recordMqttReject("unauthorized_topic");
    Serial.printf("WARN mqtt_publish_denied id=%s topic=%s\n", slot.deviceId, topic.c_str());
    slot.tcp.stop();
    return;
  }
  String payload;
  payload.reserve(length - offset);
  for (size_t i = offset; i < length; ++i) payload += static_cast<char>(body[i]);
  publishBrokerMessage(topic, payload, (header & 0x01U) != 0U, qos, -1);
  if (qos == 1U) {
    uint8_t ack[4] = {0x40U, 0x02U, static_cast<uint8_t>(packetId >> 8U),
                      static_cast<uint8_t>(packetId & 0xFFU)};
    slot.tcp.write(ack, sizeof(ack));
  }
}

void processMqttPacket(const uint8_t index, const uint8_t header,
                       const uint8_t* body, const size_t length) {
  MqttSlot& slot = mqttSlots[index];
  slot.lastRxAtMs = millis();
  const uint8_t type = header & 0xF0U;
  if (type != 0x30U) {
    Serial.printf("INFO mqtt_packet id=%s type=0x%02X bytes=%u\n", slot.deviceId,
                  type, static_cast<unsigned>(length));
  }
  if (!slot.connected) {
    if (type != 0x10U || !processConnect(slot, body, length)) removeSlot(index, false);
    return;
  }
  if (type == 0x30U) {
    processPublish(slot, header, body, length);
  } else if (type == 0x80U && (header & 0x0FU) == 0x02U) {
    processSubscribe(slot, body, length);
  } else if (type == 0xC0U) {
    sendSimpleMqtt(slot, 0xD0U);
  } else if (type == 0xE0U) {
    removeSlot(index, false);
  }
}

bool consumeMqttPacket(const uint8_t index) {
  MqttSlot& slot = mqttSlots[index];
  if (slot.rxLength < 2U) return false;
  size_t offset = 1U;
  size_t remaining = 0U;
  size_t multiplier = 1U;
  uint8_t encoded = 0;
  do {
    if (offset >= slot.rxLength || offset > 4U) return false;
    encoded = slot.rx[offset++];
    remaining += (encoded & 0x7FU) * multiplier;
    multiplier *= 128U;
  } while ((encoded & 0x80U) != 0U);
  if (remaining > kMqttInputBytes - offset) {
    Serial.printf("WARN mqtt_frame_too_large id=%s remaining=%u\n", slot.deviceId,
                  static_cast<unsigned>(remaining));
    removeSlot(index, true);
    return false;
  }
  const size_t fullLength = offset + remaining;
  if (slot.rxLength < fullLength) return false;
  processMqttPacket(index, slot.rx[0], slot.rx + offset, remaining);
  if (!slot.occupied) return false;
  memmove(slot.rx, slot.rx + fullLength, slot.rxLength - fullLength);
  slot.rxLength -= fullLength;
  return true;
}

void serviceMqttBroker() {
  WiFiClient incoming = mqttServer.available();
  if (incoming) {
    ++mqttTcpConnections;
    int freeIndex = -1;
    for (uint8_t i = 0; i < kMaxMqttClients; ++i) {
      if (!mqttSlots[i].occupied) { freeIndex = i; break; }
    }
    if (freeIndex < 0) {
      incoming.stop();
    } else {
      mqttSlots[freeIndex] = MqttSlot{};
      mqttSlots[freeIndex].tcp = incoming;
      mqttSlots[freeIndex].tcp.setNoDelay(true);
      mqttSlots[freeIndex].occupied = true;
      mqttSlots[freeIndex].lastRxAtMs = millis();
    }
  }
  const uint32_t now = millis();
  for (uint8_t index = 0; index < kMaxMqttClients; ++index) {
    MqttSlot& slot = mqttSlots[index];
    if (!slot.occupied) continue;
    while (slot.tcp.available() && slot.rxLength < kMqttInputBytes) {
      const int value = slot.tcp.read();
      if (value < 0) break;
      slot.rx[slot.rxLength++] = static_cast<uint8_t>(value);
      slot.lastRxAtMs = now;
    }
    if (slot.rxLength >= kMqttInputBytes) {
      Serial.printf("WARN mqtt_rx_buffer_full id=%s\n", slot.deviceId);
      removeSlot(index, true);
      continue;
    }
    while (slot.occupied && consumeMqttPacket(index)) {}
    if (!slot.occupied) continue;
    // Before CONNECT is parsed there is no MQTT keep-alive to rely on, so a
    // dead half-open TCP socket can be retired after the short handshake
    // grace period.  Once CONNECT succeeded, do not tear it down based on
    // WiFiClient::connected(): ESP32 can report a transient false value while
    // TCP is still handing the client its CONNACK/SUBSCRIBE exchange.  The
    // MQTT keep-alive below is the authoritative active-session timeout.
    if (!slot.connected && !slot.tcp.connected() &&
        now - slot.lastRxAtMs >= kMqttPostAcceptGraceMs) {
      Serial.printf("WARN mqtt_preconnect_socket_closed\n");
      removeSlot(index, true);
      continue;
    }
    const uint32_t allowance = static_cast<uint32_t>(slot.keepAliveSeconds) * 1500UL;
    if (slot.connected && allowance > 0U && now - slot.lastRxAtMs > allowance) {
      Serial.printf("WARN mqtt_keepalive_expired id=%s\n", slot.deviceId);
      removeSlot(index, true);
    }
  }
}
#endif  // retired raw MQTT parser

// MQTT transport is intentionally delegated to PicoMQTT.  The application
// layer below owns only SmartPlug payload validation, SD persistence and REST;
// it no longer decodes or writes MQTT frames itself.
void handleMqttApplicationMessage(const String& topic, const String& payload);
void handleMqttV2ApplicationMessage(const String& topic, const String& topicDeviceId,
                                    const uint32_t boot, const uint32_t nonce,
                                    const String& compactPayload);

// PicoMQTT invokes the broker callback as soon as a PUBLISH header is
// available.  Wi-Fi/TCP can deliver the payload body in a following segment;
// a single packet.read() must therefore not be treated as a malformed packet.
// Keep the wait bounded so a client that advertises a body then stalls cannot
// starve the ESP32 event loop indefinitely.  A client that deliberately closes
// the socket while changing Server -> Direct mode is different from a stalled
// or malformed publisher: its incomplete, best-effort telemetry is discarded
// but must not be recorded as an authentication/protocol rejection.
enum class MqttPayloadReadResult : uint8_t {
  Complete,
  ClientDisconnected,
  TimedOut,
};

MqttPayloadReadResult readMqttPayload(PicoMQTT::IncomingPacket& packet,
                                      const size_t expected, String& payload) {
  // A 345-byte legacy measurement can arrive in a second TCP segment on a
  // busy Wi-Fi link.  Keep this bounded, but allow one normal 500 ms
  // telemetry interval plus margin before declaring the packet incomplete.
  constexpr uint32_t kPayloadReadTimeoutMs = 750U;
  const uint32_t deadline = millis() + kPayloadReadTimeoutMs;
  payload = String();
  payload.reserve(expected);
  while (packet.get_remaining_size() > 0U) {
    const int value = packet.read();
    if (value >= 0) {
      payload += static_cast<char>(value);
      continue;
    }
    if (!packet.connected()) return MqttPayloadReadResult::ClientDisconnected;
    if (static_cast<int32_t>(millis() - deadline) >= 0) return MqttPayloadReadResult::TimedOut;
    delay(1);
  }
  return payload.length() == expected ? MqttPayloadReadResult::Complete
                                      : MqttPayloadReadResult::TimedOut;
}

class SmartPlugMqttBroker final : public PicoMQTT::Server {
 public:
  using PicoMQTT::Server::Server;

 protected:
  PicoMQTT::ConnectReturnCode auth(const char* clientId, const char* username,
                                   const char* password) override {
    const String id = clientId == nullptr ? String() : String(clientId);
    const String deviceId = id.startsWith("SmartPlug-")
                              ? id.substring(strlen("SmartPlug-")) : String();
    if (!validDeviceId(deviceId)) return PicoMQTT::CRC_IDENTIFIER_REJECTED;
    if (!mqttCredentialsConfigured() || username == nullptr || password == nullptr) {
      return PicoMQTT::CRC_NOT_AUTHORIZED;
    }
    if (!constantTimeEquals(String(username), settings.brokerUsername) ||
        !constantTimeEquals(String(password), settings.brokerPassword)) {
      return PicoMQTT::CRC_BAD_USERNAME_OR_PASSWORD;
    }
    return PicoMQTT::CRC_ACCEPTED;
  }

  void on_connected(const char* clientId) override {
    // PicoMQTT reports the socket first and the MQTT CONNECT handshake later.
    // Do not treat this callback as a successful SmartPlug session.
    (void)clientId;
  }

  void on_message(const char* topic, PicoMQTT::IncomingPacket& packet) override {
    const size_t bytes = packet.get_remaining_size();
    if (bytes > 1400U) {
      recordMqttReject("payload_too_large");
      Serial.printf("WARN mqtt_publish_rejected reason=payload_too_large topic=%s\n",
                    topic == nullptr ? "" : topic);
      return;
    }
    String payload;
    const MqttPayloadReadResult payloadRead = readMqttPayload(packet, bytes, payload);
    if (payloadRead != MqttPayloadReadResult::Complete) {
      if (payloadRead == MqttPayloadReadResult::ClientDisconnected) {
        Serial.printf("INFO mqtt_publish_abandoned_client_disconnect topic=%s\n",
                      topic == nullptr ? "" : topic);
        return;
      }
      recordMqttReject("payload_read_failed", topic == nullptr ? "" : topic,
                       static_cast<uint16_t>(min(bytes, static_cast<size_t>(UINT16_MAX))));
      Serial.printf("WARN mqtt_publish_rejected reason=payload_read_failed topic=%s\n",
                    topic == nullptr ? "" : topic);
      return;
    }
    const String messageTopic = topic == nullptr ? String() : String(topic);
    const int prefixLength = strlen("smartplug/");
    const int separator = messageTopic.indexOf('/', prefixLength);
    const String topicDeviceId = separator > prefixLength
        ? messageTopic.substring(prefixLength, separator) : String();
    const String topicKind = separator >= 0 ? messageTopic.substring(separator + 1) : String();
    if (validDeviceId(topicDeviceId) && mqttV2DeviceRegistered(topicDeviceId)) {
      String signedDeviceId, compactPayload, rejectReason;
      uint32_t boot = 0U;
      uint32_t nonce = 0U;
      if (!mqttV2Envelope(messageTopic, payload, "up", signedDeviceId, boot, nonce, compactPayload,
                          rejectReason) || signedDeviceId != topicDeviceId) {
        recordMqttReject(rejectReason.isEmpty() ? "v2_topic_mismatch" : rejectReason.c_str(),
                         topicKind.c_str(),
                         static_cast<uint16_t>(min(payload.length(), static_cast<unsigned int>(UINT16_MAX))));
        Serial.printf("WARN mqtt_publish_rejected reason=%s topic=%s\n",
                      rejectReason.isEmpty() ? "v2_topic_mismatch" : rejectReason.c_str(),
                      messageTopic.c_str());
        return;
      }
      ++mqttPublishPackets;
      ++mqttAcceptedMessages;
      handleMqttV2ApplicationMessage(messageTopic, topicDeviceId, boot, nonce, compactPayload);
      return;
    }
    ++mqttPublishPackets;
    ++mqttAcceptedMessages;
    handleMqttApplicationMessage(messageTopic, payload);
  }
};

SmartPlugMqttBroker mqttBroker(kMqttPort);

void forwardMqttPublish(const String& topic, const String& payload, const bool retain,
                        const uint8_t qos, const int = -1) {
  // PicoMQTT's broker correctly handles MQTT framing, delivery and subscription
  // matching.  The current SmartPlug contract uses QoS 0; retain is deliberately
  // not used for recovery because snapshots are persisted on SD and requested
  // explicitly by the device during boot.
  (void)qos;
  mqttBroker.publish(topic.c_str(), payload.c_str(), static_cast<uint8_t>(0U), retain);
}

bool publishDeviceMessage(const String& topic, DeviceRecord& device, const String& body,
                          const bool retain, const uint8_t qos) {
  if (!mqttV2DeviceRegistered(device.id)) {
    forwardMqttPublish(topic, body, retain, qos);
    return true;
  }
  if (!device.mqttV2Seen) {
    recordMqttReject("v2_boot_unknown");
    Serial.printf("WARN mqtt_downlink_suppressed reason=v2_boot_unknown id=%s topic=%s\n",
                  device.id, topic.c_str());
    return false;
  }
  if (device.mqttV2DownNonce == UINT32_MAX) {
    recordMqttReject("v2_down_nonce_exhausted");
    return false;
  }
  const uint32_t nextDownNonce = device.mqttV2DownNonce + 1U;
  const String wrapped = mqttV2WrapDownlink(topic, device.id, device.mqttV2Boot,
                                             nextDownNonce, body);
  if (wrapped.isEmpty()) {
    recordMqttReject("v2_down_sign_failed");
    return false;
  }
  // The SmartPlug persists accepted signed downlinks. Persist the server
  // counter before publishing so a ServerSmartPlug restart cannot resume at
  // nonce 1 and make every later recovery/command look like a replay.
  if (!saveMqttV2ReplayState(device.id, device.mqttV2Boot, nextDownNonce)) {
    recordMqttReject("v2_down_nonce_storage_failed");
    return false;
  }
  device.mqttV2DownNonce = nextDownNonce;
  forwardMqttPublish(topic, wrapped, retain, qos);
  return true;
}

void publishEnergySync(DeviceRecord& device, const bool reset = false) {
  if (!device.used) return;
  const String base = String("smartplug/") + device.id;
  const String payload = String("{\"device_id\":\"") + device.id +
                         "\",\"energy_wh\":" + jsonNumber(device.energyWh, 3) +
                         ",\"recorded_at_ms\":" + String(millis()) +
                         ",\"reset\":" + (reset ? "true" : "false") + "}";
  publishDeviceMessage(base + "/sync/energy", device, payload, false, 0U);
}

// Reissue only an unresolved reset.  The payload is intentionally the legacy
// sync/energy object so older SmartPlug firmware continues to understand it;
// SPMQTT2 wrapping, when configured, is handled by publishDeviceMessage().
// The physical counter confirmation below clears the flag before any later
// availability or sync/request can publish another destructive reset.
void republishPendingEnergyReset(DeviceRecord& device) {
  if (!device.awaitingEnergyReset) return;
  publishEnergySync(device, true);
  Serial.printf("INFO energy_reset_reissued id=%s\n", device.id);
}

void publishSnapshot(DeviceRecord& device) {
  if (!device.used) return;
  const String base = String("smartplug/") + device.id;
  // Snapshot data is recovery/continuity information only. SmartPlug must
  // never change relay state merely because this informational field exists.
  // Keep this production PicoMQTT route complete: the retired raw parser had
  // the complete object, but an earlier port accidentally reduced it to Wh.
  const String payload = String("{\"version\":1,\"device_id\":\"") + device.id +
      "\",\"timestamp_utc\":" + String(device.lastSeenUtc) +
      ",\"voltage_v\":" + jsonNumber(device.voltageV, 3) +
      ",\"current_a\":" + jsonNumber(device.currentA, 4) +
      ",\"active_power_w\":" + jsonNumber(device.activePowerW, 2) +
      ",\"apparent_power_va\":" + jsonNumber(device.apparentPowerVa, 2) +
      ",\"power_factor\":" + jsonNumber(device.powerFactor, 3) +
      ",\"energy_wh\":" + jsonNumber(device.energyWh, 3) +
      ",\"energy_kwh\":" + jsonNumber(device.energyWh / 1000.0F, 6) +
      ",\"relay_state\":\"" + device.relayState + "\"}";
  publishDeviceMessage(base + "/sync/snapshot", device, payload, false, 0U);
}

String jsonField(const String& body, const char* key) {
  JsonDocument document;
  if (deserializeJson(document, body)) return String();
  const JsonVariant value = document[key];
  return value.is<const char*>() ? String(value.as<const char*>()) : String();
}

// A timer configured while relay OFF is only armed.  The authoritative
// relay-state publication or accepted relay acknowledgement starts its
// deadline once the SmartPlug has actually reached ON.
void startArmedTimerAfterRelayOn(DeviceRecord& device) {
  if (strcmp(device.relayState, "on") != 0 || device.timerDeadlineUtc != 0U ||
      device.timerDurationSeconds == 0U) return;
  const uint32_t now = currentUtc();
  if (now == 0U) return;
  device.timerDeadlineUtc = now + device.timerDurationSeconds;
  device.timerDurationSeconds = 0U;
  indexDirty = true;
  snapshotDirty = true;
  Serial.printf("INFO timer_started id=%s deadline=%lu\n", device.id,
                static_cast<unsigned long>(device.timerDeadlineUtc));
}

void handleRelayAcknowledgement(DeviceRecord& device, const String& payload) {
  JsonDocument document;
  if (deserializeJson(document, payload)) return;
  const bool accepted = document["accepted"] | false;
  const String state = document["state"] | "";
  if (state == "on" || state == "off") {
    copyText(device.relayState, state);
    startArmedTimerAfterRelayOn(device);
  }
  if (strcmp(device.commandStatus, "queued") != 0) return;
  const bool timerExpiryCommand = device.timerExpiryPending &&
      strcmp(device.commandState, "off") == 0;
  if (accepted && state == device.commandState) {
    copyLiteral(device.commandStatus, "completed");
    if (timerExpiryCommand) {
      // Only the accepted acknowledgement of the expiry OFF command consumes
      // the deadline. A stale snapshot, telemetry state, rejected command, or
      // unanswered MQTT publish must leave it recoverable and retryable.
      device.timerDeadlineUtc = 0U;
      device.timerDurationSeconds = 0U;
      device.timerExpiryAttempts = 0U;
      device.timerExpiryFailed = false;
      device.timerExpiryPending = false;
      device.timerExpiryLastAttemptMs = 0U;
      indexDirty = true;
      Serial.printf("INFO timer_expiry_completed id=%s\n", device.id);
    }
  } else {
    copyLiteral(device.commandStatus, "rejected");
    if (timerExpiryCommand) {
      indexDirty = true;
      Serial.printf("WARN timer_expiry_rejected id=%s attempt=%u\n", device.id,
                    static_cast<unsigned>(device.timerExpiryAttempts));
    }
  }
  device.commandResolvedAtMs = millis();
}

void handleAllParameters(DeviceRecord& device, const String& payload) {
  JsonDocument document;
  if (deserializeJson(document, payload)) return;
  const float energy = document["energy_wh"] | NAN;
  if (!isfinite(energy) || energy < 0.0F || energy > 1000000.0F) return;
  device.voltageV = document["voltage_v"] | 0.0F;
  device.currentA = document["current_a"] | 0.0F;
  device.activePowerW = document["active_power_w"] | 0.0F;
  device.apparentPowerVa = document["apparent_power_va"] | 0.0F;
  device.powerFactor = document["power_factor"] | 0.0F;
  if (device.awaitingEnergyReset) {
    // Never treat the publish itself as acknowledgement.  A received physical
    // counter near zero is the only confirmation that consumes the pending
    // reset, so an offline SmartPlug will receive reset=true after it returns.
    if (energy <= 1.0F) {
      device.energyWh = energy;
      device.awaitingEnergyReset = false;
      Serial.printf("INFO energy_reset_confirmed id=%s\n", device.id);
    }
  } else if (energy >= device.energyWh) {
    device.energyWh = energy;
  }
  device.calibrated = document["calibrated"] | false;
  device.online = true; device.lastSeenMs = millis(); device.lastSeenUtc = currentUtc();
  addAggregateSample(device); addFineSample(device); indexDirty = true; snapshotDirty = true;
}

void handleMqttApplicationMessage(const String& topic, const String& payload) {
  if (!topic.startsWith("smartplug/")) return;
  const int start = strlen("smartplug/");
  const int separator = topic.indexOf('/', start);
  if (separator < 0) return;
  const String id = topic.substring(start, separator);
  if (!validDeviceId(id)) {
    recordMqttReject("invalid_device_id");
    Serial.printf("WARN mqtt_publish_rejected reason=invalid_device_id topic=%s\n", topic.c_str());
    return;
  }
  DeviceRecord* device = findDevice(id, true);
  if (device == nullptr) return;
  const String suffix = topic.substring(separator + 1);
  if (suffix == "measurement/allparameters") handleAllParameters(*device, payload);
  else if (suffix.startsWith("measurement/")) {
    JsonDocument document;
    if (deserializeJson(document, payload)) return;
    const float value = document["value"] | NAN;
    if (!isfinite(value)) return;
    if (suffix == "measurement/voltage") device->voltageV = value;
    else if (suffix == "measurement/current") device->currentA = value;
    else if (suffix == "measurement/active-power") device->activePowerW = value;
    else if (suffix == "measurement/apparent-power") device->apparentPowerVa = value;
    else if (suffix == "measurement/power-factor") device->powerFactor = value;
    else if (suffix == "measurement/energy") {
      if (value < 0.0F || value > 1000000.0F) return;
      if (device->awaitingEnergyReset) {
        if (value <= 1.0F) {
          device->energyWh = value;
          device->awaitingEnergyReset = false;
          Serial.printf("INFO energy_reset_confirmed id=%s\n", device->id);
        }
      } else if (value >= device->energyWh) {
        device->energyWh = value;
      }
    } else return;
    device->online = true; device->lastSeenMs = millis(); device->lastSeenUtc = currentUtc();
    indexDirty = true; snapshotDirty = true;
  }
  else if (suffix == "availability") {
    String status = payload;
    JsonDocument availability;
    if (!deserializeJson(availability, payload)) status = availability["status"] | "";
    device->online = status == "online"; device->lastSeenMs = millis(); device->lastSeenUtc = currentUtc(); snapshotDirty = true;
    if (device->online) publishEnergySync(*device, device->awaitingEnergyReset);
  } else if (suffix == "sync/request") {
    ++mqttSyncRequests;
    publishSnapshot(*device);
    republishPendingEnergyReset(*device);
    ++mqttSyncSnapshotsPublished;
  }
  else if (suffix == "state") {
    String relay = jsonField(payload, "relay");
    if (relay.isEmpty()) relay = jsonField(payload, "state");
    if (relay == "on" || relay == "off" || relay == "unknown") {
      copyText(device->relayState, relay);
      if (device->restorePending) device->restoreStateSeen = true;
      startArmedTimerAfterRelayOn(*device);
      indexDirty = true;
      snapshotDirty = true;
    }
  } else if (suffix == "ack/relay") { handleRelayAcknowledgement(*device, payload); indexDirty = true; snapshotDirty = true; }
}

void handleMqttV2ApplicationMessage(const String& topic, const String& topicDeviceId,
                                    const uint32_t boot, const uint32_t nonce,
                                    const String& compactPayload) {
  const String suffix = topic.substring(topic.indexOf('/', strlen("smartplug/")) + 1);
  DeviceRecord* const device = findDevice(topicDeviceId, true);
  if (device == nullptr) {
    recordMqttReject("v2_unknown_device");
    return;
  }
  if (!device->mqttV2Seen || boot != device->mqttV2Boot) {
    // A boot epoch is generated by firmware for every boot. Do not permit a
    // captured older boot to evict a currently live epoch; a real reboot first
    // becomes offline, then introduces its fresh signed boot value.
    if (device->mqttV2Seen && device->online &&
        millis() - device->lastSeenMs <= kDeviceOfflineTimeoutMs) {
      recordMqttReject("v2_boot_active");
      return;
    }
    // Only a reboot this server run actually witnessed (an earlier epoch was seen) counts; a
    // server restart must never replay stale SD state onto a device that kept running.
    const bool witnessedReboot = device->mqttV2Seen;
    if (witnessedReboot && (strcmp(device->relayState, "on") == 0 || strcmp(device->relayState, "off") == 0)) {
      copyLiteral(device->restoreTarget, device->relayState);
      device->restorePending = true;
      device->restoreStateSeen = false;
      device->restoreDetectedMs = millis();
      Serial.printf("INFO reconcile_after_boot_detected id=%s target=%s\n", device->id, device->restoreTarget);
    }
    device->mqttV2Seen = true;
    device->mqttV2Boot = boot;
    device->mqttV2LastNonce = nonce;
    if (!loadMqttV2ReplayState(device->id, boot, device->mqttV2DownNonce)) {
      device->mqttV2DownNonce = 0U;
      if (!saveMqttV2ReplayState(device->id, boot, 0U)) {
        recordMqttReject("v2_down_nonce_storage_failed");
        return;
      }
    }
  } else if (nonce <= device->mqttV2LastNonce) {
    recordMqttReject("v2_replay");
    return;
  } else {
    device->mqttV2LastNonce = nonce;
  }
  // The compact object was covered by the HMAC with the complete topic,
  // device id, boot epoch and nonce. The legacy application handlers consume
  // the same JSON payloads, keeping storage and REST behavior identical.
  handleMqttApplicationMessage(topic, compactPayload);
}

void publishBrokerMessage(const String& topic, const String& payload,
                          const bool retain, const uint8_t qos, const int = -1) {
  const int start = strlen("smartplug/");
  const int separator = topic.indexOf('/', start);
  const String id = separator > start ? topic.substring(start, separator) : String();
  DeviceRecord* const device = findDevice(id, false);
  if (device != nullptr) {
    String v2Payload = payload;
    if (mqttV2DeviceRegistered(device->id)) {
      const String suffix = separator >= 0 ? topic.substring(separator + 1) : String();
      if (suffix == "cmd/relay" && (payload == "on" || payload == "off")) {
        v2Payload = String("{\"state\":\"") + payload + "\"}";
      } else if (suffix == "cmd/factory-reset" && payload == "FACTORY_RESET") {
        v2Payload = "{\"command\":\"FACTORY_RESET\"}";
      }
    }
    publishDeviceMessage(topic, *device, v2Payload, retain, qos);
  } else {
    forwardMqttPublish(topic, payload, retain, qos);
  }
}

void serviceMqttBroker() { mqttBroker.loop(); }

void serviceBrokerHeartbeat() {
  constexpr uint32_t kHeartbeatIntervalMs = 1000UL;
  const uint32_t now = millis();
  if (now - lastBrokerHeartbeatAtMs < kHeartbeatIntervalMs) return;
  lastBrokerHeartbeatAtMs = now;
  // A broker-level liveness marker is intentionally separate from every
  // device's telemetry. SmartPlug enables its reconnect watchdog only after
  // observing this optional message, so older ServerSmartPlug builds remain
  // compatible with the same firmware.
  mqttBroker.publish("smartplug/broker/heartbeat", "1", static_cast<uint8_t>(0U), false);
}

// `online` is a display/runtime flag which is refreshed by telemetry.  A
// timer-expiry relay command is an actuator action, so it must use the
// stronger freshness predicate rather than trusting a flag that may not yet
// have been aged out in this loop iteration.  In particular, never consume a
// bounded expiry retry merely because the last known state was online.
bool deviceHasFreshTelemetry(const DeviceRecord& device, const uint32_t nowMs) {
  return device.online && device.lastSeenMs != 0U &&
      nowMs - device.lastSeenMs <= kDeviceOfflineTimeoutMs;
}

void expireStaleDevices() {
  const uint32_t now = millis();
  for (DeviceRecord& device : devices) {
    if (!device.used || !device.online) continue;
    if (deviceHasFreshTelemetry(device, now)) continue;
    device.online = false;
    snapshotDirty = true;
    indexDirty = true;
    Serial.printf("INFO device_offline_timeout id=%s\n", device.id);
  }
}

void expireCommands() {
  const uint32_t now = millis();
  for (DeviceRecord& device : devices) {
    if (device.used && strcmp(device.commandStatus, "queued") == 0 &&
        now - device.commandCreatedAtMs > kCommandTimeoutMs) {
      copyLiteral(device.commandStatus, "timeout");
      device.commandResolvedAtMs = now;
      if (device.timerExpiryPending && strcmp(device.commandState, "off") == 0) {
        indexDirty = true;
        Serial.printf("WARN timer_expiry_timeout id=%s attempt=%u\n", device.id,
                      static_cast<unsigned>(device.timerExpiryAttempts));
      }
    }
  }
}

void serviceTimers() {
  const uint32_t now = currentUtc();
  if (now == 0U) return;
  const uint32_t nowMs = millis();
  for (DeviceRecord& device : devices) {
    if (!device.used || device.timerDeadlineUtc == 0U || now < device.timerDeadlineUtc) continue;
    // Offline expiry is deliberately retained: no QoS0 command is published
    // and no attempt is consumed until a fresh telemetry/availability message
    // establishes that the SmartPlug is online again.  The existing bounded
    // retry/ACK flow starts only after this gate.
    if (!deviceHasFreshTelemetry(device, nowMs) ||
        strcmp(device.commandStatus, "queued") == 0) continue;
    if (device.timerExpiryFailed) continue;
    if (device.timerExpiryAttempts >= kTimerExpiryMaxAttempts) {
      device.timerExpiryFailed = true;
      device.timerExpiryPending = false;
      copyLiteral(device.commandStatus, "timer_expiry_failed");
      device.commandResolvedAtMs = nowMs;
      indexDirty = true;
      Serial.printf("ERROR timer_expiry_failed id=%s attempts=%u\n", device.id,
                    static_cast<unsigned>(device.timerExpiryAttempts));
      continue;
    }
    if (device.timerExpiryLastAttemptMs != 0U &&
        nowMs - device.timerExpiryLastAttemptMs < kTimerExpiryRetryDelayMs) continue;
    ++commandCounter;
    const String commandId = String("timer-") + String(millis()) + "-" + String(commandCounter);
    copyText(device.commandId, commandId); copyLiteral(device.commandState, "off");
    copyLiteral(device.commandStatus, "queued"); device.commandCreatedAtMs = nowMs;
    device.commandResolvedAtMs = 0U;
    device.timerExpiryPending = true;
    ++device.timerExpiryAttempts;
    device.timerExpiryLastAttemptMs = nowMs;
    indexDirty = true;
    Serial.printf("INFO timer_expiry_off_queued id=%s attempt=%u\n", device.id,
                  static_cast<unsigned>(device.timerExpiryAttempts));
    publishBrokerMessage(String("smartplug/") + device.id + "/cmd/relay", "off", false, 0U);
  }
}

// After a SmartPlug reboot witnessed by this server run, return the relay to the state this server
// last knew (the SmartPlug itself does not self-restore in Server mode). It is an ordinary signed
// relay command, sent at most once per boot, and never overrides a newer user/timer/schedule
// command or an expired timer. Without NTP time the timer check is skipped, not guessed.
constexpr uint32_t kRestoreSettleMs = 4000UL;
constexpr uint32_t kRestoreGiveUpMs = 60000UL;

void serviceBootRestore() {
  const uint32_t nowMs = millis();
  for (DeviceRecord& device : devices) {
    if (!device.used || !device.restorePending) continue;
    const uint32_t waited = nowMs - device.restoreDetectedMs;
    if (waited > kRestoreGiveUpMs) {
      device.restorePending = false;
      Serial.printf("WARN reconcile_after_boot_expired id=%s\n", device.id);
      continue;
    }
    if (waited < kRestoreSettleMs || !device.restoreStateSeen) continue;
    if (!deviceHasFreshTelemetry(device, nowMs) || strcmp(device.commandStatus, "queued") == 0) continue;
    if (device.commandCreatedAtMs != 0U && device.commandCreatedAtMs >= device.restoreDetectedMs) {
      device.restorePending = false;
      Serial.printf("INFO reconcile_after_boot_skipped id=%s reason=newer_command\n", device.id);
      continue;
    }
    bool targetOn = strcmp(device.restoreTarget, "on") == 0;
    const uint32_t utc = currentUtc();
    if (device.timerDeadlineUtc != 0U && utc != 0U && utc >= device.timerDeadlineUtc) targetOn = false;
    device.restorePending = false;  // once per boot
    const bool reportedOn = strcmp(device.relayState, "on") == 0;  // "unknown" counts as off
    if (reportedOn == targetOn) {
      Serial.printf("INFO reconcile_after_boot_ok id=%s state=%s\n", device.id, targetOn ? "on" : "off");
      continue;
    }
    ++commandCounter;
    const String commandId = String("restore-") + String(nowMs) + "-" + String(commandCounter);
    copyText(device.commandId, commandId);
    copyLiteral(device.commandState, targetOn ? "on" : "off");
    copyLiteral(device.commandStatus, "queued");
    device.commandCreatedAtMs = nowMs;
    device.commandResolvedAtMs = 0U;
    indexDirty = true;
    Serial.printf("INFO reconcile_after_boot id=%s target=%s reported=%s\n", device.id,
                  targetOn ? "on" : "off", reportedOn ? "on" : "off");
    publishBrokerMessage(String("smartplug/") + device.id + "/cmd/relay", targetOn ? "on" : "off", false, 0U);
  }
}

void serviceSchedules() {
  const uint32_t now = currentUtc();
  if (now == 0U) return;
  for (DeviceRecord& device : devices) {
    if (!device.used) continue;
    // A brief Wi-Fi/MQTT interruption must not silently discard a schedule.
    // Keep the newest due command for five minutes, then discard it rather
    // than applying a stale appliance action much later.
    if (device.pendingScheduleDueUtc != 0U) {
      if (now > device.pendingScheduleDueUtc + kScheduleCatchUpSeconds) {
        device.pendingScheduleDueUtc = 0U;
        indexDirty = true;
      } else if (device.online && strcmp(device.commandStatus, "queued") != 0) {
        ++commandCounter;
        const String commandId = String("schedule-catchup-") + String(millis()) + "-" + String(commandCounter);
        copyText(device.commandId, commandId);
        copyLiteral(device.commandState, device.pendingScheduleTurnOn ? "on" : "off");
        copyLiteral(device.commandStatus, "queued");
        device.commandCreatedAtMs = millis();
        device.commandResolvedAtMs = 0U;
        publishBrokerMessage(String("smartplug/") + device.id + "/cmd/relay",
                             device.pendingScheduleTurnOn ? "on" : "off", false, 0U);
        device.pendingScheduleDueUtc = 0U;
        indexDirty = true;
      }
    }
    if (!device.scheduleEnabled || device.scheduleCount == 0U) continue;
    const int64_t minuteKey = static_cast<int64_t>(now / 60U);
    if (minuteKey == device.lastScheduleMinuteUtc) continue;
    device.lastScheduleMinuteUtc = minuteKey;
    const time_t localEpoch = static_cast<time_t>(now) +
                              static_cast<time_t>(device.timezoneOffsetMinutes) * 60;
    tm local = {};
    gmtime_r(&localEpoch, &local);
    int selected = -1;
    for (uint8_t index = 0; index < device.scheduleCount; ++index) {
      const auto& entry = device.schedules[index];
      if (entry.hour == local.tm_hour && entry.minute == local.tm_min) selected = index;
    }
    if (selected < 0) continue;
    const bool turnOn = device.schedules[selected].turnOn;
    if (!device.online || strcmp(device.commandStatus, "queued") == 0) {
      device.pendingScheduleDueUtc = now;
      device.pendingScheduleTurnOn = turnOn;
      indexDirty = true;
      continue;
    }
    ++commandCounter;
    const String commandId = String("schedule-") + String(millis()) + "-" + String(commandCounter);
    copyText(device.commandId, commandId);
    copyLiteral(device.commandState, turnOn ? "on" : "off");
    copyLiteral(device.commandStatus, "queued");
    device.commandCreatedAtMs = millis();
    device.commandResolvedAtMs = 0U;
    publishBrokerMessage(String("smartplug/") + device.id + "/cmd/relay", turnOn ? "on" : "off", false, 0U);
  }
}

void serviceStorage() {
  // A card may be inserted after the ESP32 is powered. Keep retrying so the
  // setup/status endpoint becomes useful without requiring a firmware upload
  // or power cycle after each wiring adjustment.
  if (!sdReady) {
    if (millis() - lastSdMountAttemptMs >= 5000UL) beginStorage();
    return;
  }
  if (sdReady && indexDirty && millis() - lastIndexFlushAtMs >= kIndexFlushIntervalMs) {
    saveDeviceIndex();
  }
  if (sdReady && snapshotDirty && millis() - lastSnapshotFlushAtMs >= kSnapshotFlushIntervalMs) {
    // Rate-limit failed writes too.  Leaving this timestamp unchanged on a
    // transient SD failure retried the full atomic write every loop iteration.
    lastSnapshotFlushAtMs = millis();
    saveSnapshots();
  }
}

void handleHealth() {
  const uint32_t nowMs = millis();
  const uint32_t lastSnapshotSuccessAgeMs = lastSnapshotWriteSuccessMs == 0U
      ? 0U : nowMs - lastSnapshotWriteSuccessMs;
  const uint32_t lastSnapshotFailureAgeMs = lastSnapshotWriteFailureMs == 0U
      ? 0U : nowMs - lastSnapshotWriteFailureMs;
  sendJson(200, String("{\"server_version\":\"") + kServerVersion +
                "\",\"web_server_started\":true,\"mqtt_broker_started\":true,\"sd_ready\":" +
                (sdReady ? "true" : "false") + ",\"time_synchronized\":" +
                (timeIsSynchronized() ? "true" : "false") +
                ",\"snapshot_file_bytes\":" + String(lastKnownSnapshotFileBytes) +
                ",\"snapshot_dirty\":" + (snapshotDirty ? "true" : "false") +
                ",\"snapshot_storage\":{\"write_interval_target_ms\":" +
                String(kSnapshotFlushIntervalMs) + ",\"write_attempts\":" +
                String(snapshotWriteAttempts) + ",\"write_successes\":" +
                String(snapshotWriteSuccesses) + ",\"write_failures\":" +
                String(snapshotWriteFailures) + ",\"last_success_utc\":" +
                String(lastSnapshotWriteSuccessUtc) + ",\"last_success_age_ms\":" +
                String(lastSnapshotSuccessAgeMs) + ",\"last_success_cadence_ms\":" +
                String(lastSnapshotWriteCadenceMs) + ",\"last_failure_age_ms\":" +
                String(lastSnapshotFailureAgeMs) + "}" +
                ",\"active_devices\":" + String(activeDeviceCount()) +
                ",\"mqtt_runtime\":{\"received_messages\":" + String(mqttPublishPackets) +
                ",\"accepted_messages\":" + String(mqttAcceptedMessages) +
                ",\"denied_publishes\":" + String(mqttDeniedPublishes) +
                ",\"sync_requests\":" + String(mqttSyncRequests) +
                ",\"sync_snapshots_published\":" + String(mqttSyncSnapshotsPublished) +
                ",\"last_rejected_reason\":\"" +
                String(lastMqttRejectReason[0] ? lastMqttRejectReason : "none") +
                "\",\"last_rejected_topic_kind\":\"" +
                String(lastMqttRejectTopicKind[0] ? lastMqttRejectTopicKind : "none") +
                "\",\"last_rejected_payload_bytes\":" + String(lastMqttRejectPayloadBytes) +
                "}}");
}

// PROPOSED (not yet in design.md): sd_total_bytes / sd_used_bytes in "storage", only when the
// request carries ?storage=1. Computing used space walks the FAT, which can block for seconds on a
// large card, so the result is cached for five minutes and never computed for plain status polls.
String sdCapacityJson() {
  static uint64_t cachedTotal = 0;
  static uint64_t cachedUsed = 0;
  if (!sdReady || !http.hasArg("storage")) return String();
  const uint32_t now = millis();
  if (sdCapacityCachedAtMs == 0U || now - sdCapacityCachedAtMs > 300000UL) {
    cachedTotal = SD.totalBytes();
    cachedUsed = SD.usedBytes();
    sdCapacityCachedAtMs = now == 0U ? 1U : now;
  }
  uint64_t historyTotal = historyBytesUnattributed;
  String perDevice = "[";
  bool first = true;
  for (size_t i = 0; i < kMaxDevices; ++i) {
    if (!devices[i].used) continue;
    const uint64_t deviceBytes = historyBytesByDevice[i] + fineBytesByDevice[i];
    historyTotal += deviceBytes;
    if (!first) perDevice += ',';
    first = false;
    perDevice += String("{\"device_id\":\"") + devices[i].id + "\",\"bytes\":" +
                 String(static_cast<unsigned long long>(deviceBytes)) + "}";
  }
  perDevice += "]";
  return String(",\"sd_total_bytes\":") + String(static_cast<unsigned long long>(cachedTotal)) +
         ",\"sd_used_bytes\":" + String(static_cast<unsigned long long>(cachedUsed)) +
         ",\"history_scan_complete\":" + (historyScanDone ? "true" : "false") +
         ",\"history_bytes_total\":" + String(static_cast<unsigned long long>(historyTotal)) +
         ",\"history_by_device\":" + perDevice;
}

void handleStatus() {
  if (!requestIsAuthorized()) return;
  sendJson(200, String("{\"server_version\":\"") + kServerVersion +
                "\",\"configured\":" + (settingsReady ? "true" : "false") +
                ",\"mqtt_broker\":{\"port\":1883,\"credentials_configured\":" +
                (mqttCredentialsConfigured() ? "true" : "false") + "},\"storage\":{\"sd_ready\":" +
                (sdReady ? "true" : "false") + sdCapacityJson() + "},\"network\":{\"station_connected\":" +
                (WiFi.status() == WL_CONNECTED ? "true" : "false") +
                ",\"station_ip\":\"" + stationIpText() + "\",\"access_point_ssid\":\"" +
                kSetupApSsid + "\",\"access_point_ip\":\"" + WiFi.softAPIP().toString() +
                "\"},\"devices\":" + String(deviceCount()) + "}");
}

void handleDevices() {
  if (!requestIsAuthorized()) return;
  String response = "{\"devices\":[";
  bool first = true;
  for (const DeviceRecord& device : devices) {
    if (!device.used) continue;
    if (!first) response += ',';
    response += deviceSummaryJson(device);
    first = false;
  }
  response += "]}";
  sendJson(200, response);
}

String requestPathPart(const uint8_t index) {
  const String uri = http.uri();
  int start = 0;
  uint8_t part = 0;
  while (start < uri.length()) {
    const int slash = uri.indexOf('/', start + 1);
    if (part == index) return uri.substring(start, slash < 0 ? uri.length() : slash);
    if (slash < 0) break;
    start = slash;
    ++part;
  }
  return String();
}

uint32_t historyResolutionSeconds(const String& resolution) {
  if (resolution == "1s") return 1U;
  if (resolution == "1m") return 60U;
  if (resolution == "5m") return 300U;
  if (resolution == "30m") return 1800U;
  if (resolution == "1h") return 3600U;
  if (resolution == "1d") return 86400U;
  return 0U;
}

// PROPOSED: resolution=1s serves the per-second files (max one hour per request). Rows that are
// still waiting in the RAM queue are included, so the newest points are not up to 10 s late.
void handleFineHistory(const DeviceRecord& device) {
  const uint32_t nowUtc = currentUtc();
  const uint32_t to = http.hasArg("to") ? strtoul(http.arg("to").c_str(), nullptr, 10) : nowUtc;
  const uint32_t from = http.hasArg("from") ? strtoul(http.arg("from").c_str(), nullptr, 10)
                                            : (to > 300U ? to - 300U : 0U);
  if (to < from || to - from > 3600U) { sendError(400, "range_too_large_for_resolution"); return; }
  http.sendHeader("Access-Control-Allow-Origin", "*");
  http.sendHeader("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type");
  http.sendHeader("Cache-Control", "no-store");
  http.setContentLength(CONTENT_LENGTH_UNKNOWN);
  http.send(200, "application/json", "");
  http.sendContent(String("{\"device_id\":\"") + device.id + "\",\"resolution\":\"1s\",\"energy_reset_utc\":" +
                   String(device.lastEnergyResetUtc) + ",\"points\":[");
  bool sent = false;
  auto emit = [&](const unsigned long timestamp, const float v, const float a, const float w,
                  const float va, const float pf, const float e) {
    if (sent) http.sendContent(",");
    http.sendContent(String("{\"timestamp_utc_ms\":") + String(static_cast<uint64_t>(timestamp) * 1000ULL) +
      ",\"voltage_v\":" + jsonNumber(v, 3) + ",\"current_a\":" + jsonNumber(a, 4) +
      ",\"active_power_w\":" + jsonNumber(w, 2) + ",\"apparent_power_va\":" + jsonNumber(va, 2) +
      ",\"power_factor\":" + jsonNumber(pf, 3) + ",\"energy_wh\":" + jsonNumber(e, 3) + "}");
    sent = true;
  };
  uint32_t lastEmitted = 0U;
  if (sdReady && nowUtc != 0U) {
    for (uint32_t day = from / 86400UL; day <= to / 86400UL; ++day) {
      char dayText[9];
      fineDayText(day * 86400UL, dayText);
      const String path = fineDeviceDir(device.id) + "/" + dayText + ".csv";
      if (!SD.exists(path)) continue;
      File file = SD.open(path, FILE_READ);
      while (file && file.available()) {
        const String line = file.readStringUntil('\n');
        unsigned long timestamp = 0;
        float v = 0, a = 0, w = 0, va = 0, pf = 0, e = 0;
        if (sscanf(line.c_str(), "%lu,%f,%f,%f,%f,%f,%f", &timestamp, &v, &a, &w, &va, &pf, &e) != 7) continue;
        if (timestamp < from || timestamp > to) continue;
        emit(timestamp, v, a, w, va, pf, e);
        lastEmitted = static_cast<uint32_t>(timestamp);
      }
      if (file) file.close();
    }
  }
  const size_t deviceIndex = &device - devices;
  for (size_t i = 0; i < fineQueueCount; ++i) {
    const FineRow& row = fineQueue[i];
    if (row.device != deviceIndex || row.written || row.utc < from || row.utc > to || row.utc <= lastEmitted) continue;
    emit(row.utc, row.v, row.a, row.w, row.va, row.pf, row.e);
  }
  http.sendContent("]}");
}

void handleHistory(const DeviceRecord& device) {
  if (!sdReady) { sendError(503, "sd_card_unavailable"); return; }
  const String resolutionText = http.hasArg("resolution") ? http.arg("resolution") : "1m";
  if (resolutionText == "1s") { handleFineHistory(device); return; }
  const uint32_t resolution = historyResolutionSeconds(resolutionText);
  if (resolution == 0U) { sendError(400, "invalid_resolution"); return; }
  const uint32_t from = http.hasArg("from") ? strtoul(http.arg("from").c_str(), nullptr, 10) : 0U;
  const uint32_t to = http.hasArg("to") ? strtoul(http.arg("to").c_str(), nullptr, 10) : UINT32_MAX;
  if (!SD.exists(kHistoryPath)) {
    sendJson(200, String("{\"device_id\":\"") + device.id + "\",\"resolution\":\"" +
                  resolutionText + "\",\"points\":[]}");
    return;
  }
  http.sendHeader("Access-Control-Allow-Origin", "*");
  http.sendHeader("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type");
  http.sendHeader("Cache-Control", "no-store");
  http.setContentLength(CONTENT_LENGTH_UNKNOWN);
  http.send(200, "application/json", "");
  http.sendContent(String("{\"device_id\":\"") + device.id + "\",\"resolution\":\"" +
                   resolutionText + "\",\"energy_reset_utc\":" +
                   String(device.lastEnergyResetUtc) + ",\"points\":[");
  File file = SD.open(kHistoryPath, FILE_READ);
  bool sent = false;
  uint32_t bucket = 0U;
  uint16_t count = 0U;
  float sumV = 0.0F, sumA = 0.0F, sumW = 0.0F, sumVa = 0.0F, sumPf = 0.0F, lastEnergy = 0.0F;
  auto flush = [&]() {
    if (count == 0U) return;
    if (sent) http.sendContent(",");
    const float divisor = static_cast<float>(count);
    http.sendContent(String("{\"timestamp_utc_ms\":") + String(static_cast<uint64_t>(bucket) * 1000ULL) +
      ",\"voltage_v\":" + jsonNumber(sumV / divisor, 3) +
      ",\"current_a\":" + jsonNumber(sumA / divisor, 4) +
      ",\"active_power_w\":" + jsonNumber(sumW / divisor, 2) +
      ",\"apparent_power_va\":" + jsonNumber(sumVa / divisor, 2) +
      ",\"power_factor\":" + jsonNumber(sumPf / divisor, 3) +
      ",\"energy_wh\":" + jsonNumber(lastEnergy, 3) + "}");
    sent = true;
  };
  while (file && file.available()) {
    const String line = file.readStringUntil('\n');
    char id[20] = {};
    unsigned long timestamp = 0;
    float voltage = 0, current = 0, watt = 0, va = 0, pf = 0, energy = 0;
    if (sscanf(line.c_str(), "%lu,%19[^,],%f,%f,%f,%f,%f,%f", &timestamp, id,
               &voltage, &current, &watt, &va, &pf, &energy) != 8 || String(device.id) != id) continue;
    if (timestamp < from || timestamp > to) continue;
    const uint32_t nextBucket = static_cast<uint32_t>(timestamp) -
                                (static_cast<uint32_t>(timestamp) % resolution);
    if (count > 0U && nextBucket != bucket) {
      flush();
      count = 0U; sumV = sumA = sumW = sumVa = sumPf = 0.0F;
    }
    bucket = nextBucket;
    sumV += voltage; sumA += current; sumW += watt; sumVa += va; sumPf += pf;
    // Samples are chronological. Keeping the final reading lets a true reset
    // boundary appear in the requested graph instead of being hidden by max().
    lastEnergy = energy;
    if (count < UINT16_MAX) ++count;
  }
  if (file) file.close();
  flush();
  http.sendContent("]}");
}

void handleDeviceRoute() {
  if (!requestIsAuthorized()) return;
  const String uri = http.uri();
  const String prefix = "/api/v1/devices/";
  const int start = prefix.length();
  const int separator = uri.indexOf('/', start);
  const String id = uri.substring(start, separator < 0 ? uri.length() : separator);
  DeviceRecord* device = findDevice(id, false);
  if (device == nullptr) { sendError(404, "device_not_found"); return; }
  const String action = separator < 0 ? "" : uri.substring(separator + 1);
  if (http.method() == HTTP_GET && action.isEmpty()) {
    sendJson(200, deviceSummaryJson(*device));
  } else if (http.method() == HTTP_GET && action == "latest") {
    sendJson(200, deviceLatestJson(*device));
  } else if (http.method() == HTTP_GET && action == "energy") {
    sendJson(200, String("{\"device_id\":\"") + device->id +
                  "\",\"energy_wh\":" + jsonNumber(device->energyWh, 3) +
                  ",\"source\":\"server_sd\",\"recorded_at_ms\":" +
                  String(static_cast<uint64_t>(device->lastSeenUtc) * 1000ULL) + "}");
  } else if (http.method() == HTTP_GET && action == "history") {
    handleHistory(*device);
  } else if (http.method() == HTTP_POST && action == "energy/reset") {
    JsonDocument document;
    if (!http.hasArg("plain") || deserializeJson(document, http.arg("plain")) ||
        String(document["confirm_1"] | "") != "RESET_ENERGY" ||
        String(document["confirm_2"] | "") != "RESET_ENERGY" ||
        String(document["confirm_3"] | "") != "RESET_ENERGY") {
      sendError(400, "triple_confirmation_required"); return;
    }
    // A reset requested while the SmartPlug is offline must survive a server
    // restart. Refuse to queue it when the SD index cannot provide that
    // durability instead of accepting a one-shot QoS0 command that may vanish.
    if (!sdReady) { sendError(503, "storage_unavailable"); return; }
    const DeviceRecord beforeReset = *device;
    const float previousEnergyWh = device->energyWh;
    const uint32_t resetUtc = currentUtc();
    device->energyWh = 0.0F;
    device->lastEnergyResetUtc = resetUtc;
    device->awaitingEnergyReset = true;
    indexDirty = true;
    snapshotDirty = true;
    if (!saveDeviceIndex()) {
      *device = beforeReset;
      indexDirty = true;
      snapshotDirty = true;
      sendError(503, "storage_write_failed");
      return;
    }
    appendEnergyResetAudit(*device, previousEnergyWh, resetUtc);
    publishEnergySync(*device, true);
    sendJson(202, String("{\"device_id\":\"") + device->id +
                    "\",\"result\":\"energy_reset_queued\",\"previous_energy_wh\":" +
                    jsonNumber(previousEnergyWh, 3) + ",\"reset_utc\":" + String(resetUtc) + "}");
  } else if (http.method() == HTTP_POST && action == "factory-reset") {
    JsonDocument document;
    if (!http.hasArg("plain") || deserializeJson(document, http.arg("plain")) ||
        String(document["confirm_1"] | "") != "FACTORY_RESET" ||
        String(document["confirm_2"] | "") != "FACTORY_RESET" ||
        String(document["confirm_3"] | "") != "FACTORY_RESET") {
      sendError(400, "triple_confirmation_required"); return;
    }
    if (!device->online) { sendError(409, "device_offline"); return; }
    publishBrokerMessage(String("smartplug/") + device->id + "/cmd/factory-reset", "FACTORY_RESET", false, 0U);
    sendJson(202, String("{\"device_id\":\"") + device->id + "\",\"result\":\"factory_reset_queued\"}");
  } else if (http.method() == HTTP_GET && action == "schedule") {
    sendJson(200, deviceScheduleJson(*device));
  } else if (http.method() == HTTP_POST && action == "schedule") {
    if (!sdReady) { sendError(503, "storage_unavailable"); return; }
    JsonDocument document;
    if (!http.hasArg("plain") || deserializeJson(document, http.arg("plain"))) {
      sendError(400, "invalid_schedule_payload"); return;
    }
    const String command = document["action"] | "";
    if (!document["timezone_offset_minutes"].isNull()) {
      const int offset = document["timezone_offset_minutes"] | 0;
      if (offset < -720 || offset > 840) { sendError(400, "invalid_timezone_offset"); return; }
      device->timezoneOffsetMinutes = static_cast<int16_t>(offset);
    }
    if (command == "set_enabled") {
      device->scheduleEnabled = document["enabled"] | false;
    } else if (command == "add") {
      const int hour = document["hour"] | -1;
      const int minute = document["minute"] | -1;
      const String state = document["state"] | "";
      const String event = document["event"] | "";
      if (hour < 0 || hour > 23 || minute < 0 || minute > 59 ||
          (state != "on" && state != "off") || !validScheduleEvent(event)) {
        sendError(400, "invalid_schedule_entry"); return;
      }
      if (device->scheduleCount >= kMaxDailySchedules) { sendError(409, "schedule_full"); return; }
      auto& entry = device->schedules[device->scheduleCount++];
      entry.hour = static_cast<uint8_t>(hour);
      entry.minute = static_cast<uint8_t>(minute);
      entry.turnOn = state == "on";
      copyText(entry.event, event);
    } else if (command == "delete") {
      const int index = document["index"] | -1;
      if (index < 0 || index >= device->scheduleCount) { sendError(400, "invalid_schedule_index"); return; }
      for (uint8_t i = static_cast<uint8_t>(index); i + 1U < device->scheduleCount; ++i) {
        device->schedules[i] = device->schedules[i + 1U];
      }
      --device->scheduleCount;
    } else if (command == "move") {
      const int from = document["from"] | -1;
      const int to = document["to"] | -1;
      if (from < 0 || to < 0 || from >= device->scheduleCount || to >= device->scheduleCount) {
        sendError(400, "invalid_schedule_index"); return;
      }
      const auto moved = device->schedules[from];
      if (from < to) for (int i = from; i < to; ++i) device->schedules[i] = device->schedules[i + 1];
      if (from > to) for (int i = from; i > to; --i) device->schedules[i] = device->schedules[i - 1];
      device->schedules[to] = moved;
    } else {
      sendError(400, "invalid_schedule_action"); return;
    }
    indexDirty = true;
    if (!saveDeviceIndex()) { sendError(503, "storage_write_failed"); return; }
    sendJson(200, deviceScheduleJson(*device));
  } else if (http.method() == HTTP_GET && action == "timer") {
    const uint32_t now = currentUtc();
    const uint32_t remaining = device->timerDeadlineUtc > now && now != 0U
                                 ? device->timerDeadlineUtc - now : 0U;
    sendJson(200, String("{\"device_id\":\"") + device->id +
                  "\",\"armed_seconds\":" + String(device->timerDurationSeconds) +
                  ",\"deadline_utc\":" + String(device->timerDeadlineUtc) +
                  ",\"remaining_seconds\":" + String(remaining) +
                  ",\"expiry_pending\":" + (device->timerExpiryPending ? "true" : "false") +
                  ",\"expiry_attempts\":" + String(device->timerExpiryAttempts) +
                  ",\"expiry_failed\":" + (device->timerExpiryFailed ? "true" : "false") + "}");
  } else if (http.method() == HTTP_POST && action == "timer") {
    if (!sdReady) { sendError(503, "storage_unavailable"); return; }
    JsonDocument document;
    if (!http.hasArg("plain") || deserializeJson(document, http.arg("plain"))) {
      sendError(400, "invalid_timer_payload"); return;
    }
    const String command = document["action"] | "apply";
    if (command == "reset") {
      device->timerDeadlineUtc = 0U;
      device->timerDurationSeconds = 0U;
      device->timerExpiryAttempts = 0U;
      device->timerExpiryFailed = false;
      device->timerExpiryPending = false;
      device->timerExpiryLastAttemptMs = 0U;
      indexDirty = true;
      if (!saveDeviceIndex()) { sendError(503, "storage_write_failed"); return; }
      sendJson(200, String("{\"device_id\":\"") + device->id + "\",\"result\":\"timer_reset\"}");
      return;
    }
    const uint32_t days = document["days"] | 0U;
    const uint32_t hours = document["hours"] | 0U;
    const uint32_t minutes = document["minutes"] | 0U;
    const uint32_t seconds = document["seconds"] | 0U;
    if (hours > 23U || minutes > 59U || seconds > 59U) { sendError(400, "invalid_timer_duration"); return; }
    const uint64_t total = static_cast<uint64_t>(days) * 86400ULL +
                           static_cast<uint64_t>(hours) * 3600ULL +
                           static_cast<uint64_t>(minutes) * 60ULL + seconds;
    if (total == 0ULL || total > UINT32_MAX || !timeIsSynchronized()) {
      sendError(400, "invalid_or_unsynchronized_timer"); return;
    }
    if (strcmp(device->relayState, "on") == 0) {
      device->timerDeadlineUtc = currentUtc() + static_cast<uint32_t>(total);
      device->timerDurationSeconds = 0U;
    } else {
      device->timerDeadlineUtc = 0U;
      device->timerDurationSeconds = static_cast<uint32_t>(total);
    }
    device->timerExpiryAttempts = 0U;
    device->timerExpiryFailed = false;
    device->timerExpiryPending = false;
    device->timerExpiryLastAttemptMs = 0U;
    indexDirty = true;
    if (!saveDeviceIndex()) { sendError(503, "storage_write_failed"); return; }
    sendJson(200, String("{\"device_id\":\"") + device->id +
                  "\",\"result\":\"timer_applied\",\"armed_seconds\":" +
                  String(device->timerDurationSeconds) + ",\"deadline_utc\":" +
                  String(device->timerDeadlineUtc) + "}");
  } else if (http.method() == HTTP_POST && action == "automation/reset") {
    if (!sdReady) { sendError(503, "storage_unavailable"); return; }
    // A published timer/schedule command cannot be recalled from MQTT.  Refuse
    // a detach that would otherwise claim the automation was gone while the
    // relay action could still be delivered.
    if (strcmp(device->commandStatus, "queued") == 0 &&
        (strncmp(device->commandId, "timer-", 6U) == 0 ||
         strncmp(device->commandId, "schedule-", 9U) == 0)) {
      sendError(409, "automation_command_pending"); return;
    }
    const DeviceRecord previous = *device;
    if (!clearDeviceAutomation(*device)) {
      sendError(409, "automation_command_pending"); return;
    }
    indexDirty = true;
    snapshotDirty = true;
    if (!saveDeviceIndex()) {
      *device = previous;
      indexDirty = true;
      snapshotDirty = true;
      sendError(503, "storage_write_failed"); return;
    }
    sendJson(200, String("{\"device_id\":\"") + device->id +
                  "\",\"result\":\"automation_reset\"}");
  } else if (http.method() == HTTP_POST && action == "relay") {
    String state;
    JsonDocument document;
    if (http.hasArg("plain") && !deserializeJson(document, http.arg("plain"))) {
      state = document["state"] | "";
    } else if (http.hasArg("state")) {
      state = http.arg("state");
    }
    state.toLowerCase();
    if (state != "on" && state != "off") { sendError(400, "invalid_relay_state"); return; }
    if (!device->online) { sendError(409, "device_offline"); return; }
    if (strcmp(device->commandStatus, "queued") == 0) { sendError(409, "command_pending"); return; }
    device->restorePending = false;  // an explicit user command always wins over a reconcile
    ++commandCounter;
    const String commandId = String("cmd-") + String(millis()) + "-" + String(commandCounter);
    copyText(device->commandId, commandId);
    copyText(device->commandState, state);
    copyLiteral(device->commandStatus, "queued");
    device->commandCreatedAtMs = millis();
    device->commandResolvedAtMs = 0U;
    const String topic = String("smartplug/") + device->id + "/cmd/relay";
    publishBrokerMessage(topic, state, false, 0U);
    sendJson(202, String("{\"command_id\":\"") + device->commandId +
                  "\",\"status\":\"queued\",\"state\":\"" + state + "\"}");
  } else {
    sendError(404, "not_found");
  }
}

void handleCommandRoute() {
  if (!requestIsAuthorized()) return;
  const String prefix = "/api/v1/commands/";
  const String commandId = http.uri().substring(prefix.length());
  for (const DeviceRecord& device : devices) {
    if (device.used && commandId == device.commandId) {
      sendJson(200, String("{\"command_id\":\"") + device.commandId +
                    "\",\"device_id\":\"" + device.id +
                    "\",\"state\":\"" + device.commandState +
                    "\",\"status\":\"" + device.commandStatus + "\"}");
      return;
    }
  }
  sendError(404, "command_not_found");
}

void clearMqttV2Runtime(DeviceRecord& device) {
  device.mqttV2Seen = false;
  device.mqttV2Boot = 0U;
  device.mqttV2LastNonce = 0U;
  device.mqttV2DownNonce = 0U;
}

// Registration is intentionally an application-API operation, never an MQTT
// message. The server stores only a per-device v2 secret and returns no secret
// material, so the Android app can provision a SmartPlug without broadening
// the shared broker credentials.
void handleMqttAuthRoute() {
  if (!requestIsAuthorized()) return;
  const String prefix = "/api/v1/mqtt-auth/devices/";
  const String id = http.uri().substring(prefix.length());
  if (!validDeviceId(id) || id.indexOf('/') >= 0) {
    sendError(400, "invalid_device_id");
    return;
  }
  if (http.method() == HTTP_POST) {
    String secret;
    if (http.hasArg("plain")) {
      JsonDocument document;
      if (deserializeJson(document, http.arg("plain"))) {
        sendError(400, "invalid_mqtt_auth_payload");
        return;
      }
      secret = document["signing_secret"] | "";
    } else {
      secret = http.arg("signing_secret");
    }
    if (!validCredentialText(secret, kMqttAuthSecretMinChars, kMqttAuthSecretMaxChars)) {
      sendError(400, "invalid_mqtt_auth_secret");
      return;
    }
    if (!saveMqttV2Secret(id, secret)) {
      sendError(503, "mqtt_auth_storage_failed");
      return;
    }
    DeviceRecord* const device = findDevice(id, true);
    if (device != nullptr) clearMqttV2Runtime(*device);
    sendJson(201, String("{\"device_id\":\"") + id +
                  "\",\"protocol\":\"SPMQTT2\",\"registered\":true}");
    return;
  }
  if (http.method() == HTTP_DELETE) {
    if (!mqttV2DeviceRegistered(id)) {
      sendError(404, "mqtt_auth_not_registered");
      return;
    }
    if (!removeMqttV2Secret(id)) {
      sendError(503, "mqtt_auth_storage_failed");
      return;
    }
    DeviceRecord* const device = findDevice(id, false);
    if (device != nullptr) clearMqttV2Runtime(*device);
    sendJson(200, String("{\"device_id\":\"") + id +
                  "\",\"protocol\":\"SPMQTT2\",\"registered\":false}");
    return;
  }
  sendError(405, "method_not_allowed");
}

void handleSetupStatus() {
  if (!requestIsSetupAdmin()) return;
  sendJson(200, String("{\"access_point\":{\"ssid\":\"") + kSetupApSsid +
                "\",\"ip\":\"" + WiFi.softAPIP().toString() +
                "\"},\"station\":{\"configured\":" +
                (strlen(settings.wifiSsid) > 0 ? "true" : "false") +
                ",\"connected\":" + (WiFi.status() == WL_CONNECTED ? "true" : "false") +
                ",\"ip\":\"" + stationIpText() + "\"},\"configured\":" +
                (settingsReady ? "true" : "false") + ",\"server_id\":\"" + serverId() +
                "\",\"mdns_host\":\"" + mdnsHost() + ".local\",\"mqtt_port\":" + String(kMqttPort) + ",\"sd_ready\":" +
                (sdReady ? "true" : "false") + "}");
}

void handleSetupScanWifi() {
  if (!requestIsSetupAdmin()) return;
  // Keep the setup AP online while the station radio scans; the Android app remains bound to
  // that AP and receives this response as the SmartPlug pairing flow does.
  const int count = WiFi.scanNetworks(/*async=*/false, /*show_hidden=*/true);
  if (count < 0) {
    sendError(503, "wifi_scan_failed");
    return;
  }
  String body(F("{\"networks\":["));
  bool first = true;
  for (int index = 0; index < count; ++index) {
    const String ssid = WiFi.SSID(index);
    if (ssid.isEmpty()) continue;
    if (!first) body += ',';
    first = false;
    body += F("{\"ssid\":\"");
    body += jsonEscape(ssid);
    body += F("\",\"rssi\":");
    body += String(WiFi.RSSI(index));
    body += F(",\"security\":\"");
    body += WiFi.encryptionType(index) == WIFI_AUTH_OPEN ? F("open") : F("secured");
    body += F("\"}");
  }
  WiFi.scanDelete();
  body += F("]}");
  sendJson(200, body);
}

/** Authenticated reset used only by the application's triple-confirmed global reset.
 * Respond first, then reboot from loop(), so the phone can receive the accepted result. */
void handleServerFactoryReset() {
  if (!requestIsAuthorized()) return;
  JsonDocument document;
  if (!http.hasArg("plain") || deserializeJson(document, http.arg("plain")) ||
      String(document["confirm_1"] | "") != "FACTORY_RESET" ||
      String(document["confirm_2"] | "") != "FACTORY_RESET" ||
      String(document["confirm_3"] | "") != "FACTORY_RESET") {
    sendError(400, "triple_confirmation_required");
    return;
  }
  factoryResetPending = true;
  factoryResetAtMs = millis() + 300U;
  sendJson(202, "{\"result\":\"factory_reset_accepted\"}");
}

// Browser recovery path for a household that has lost the app/API token.  It
// is deliberately limited to the commissioning SoftAP and still requires the
// same explicit three confirmations as the application flow.
bool requestComesFromSetupAp() {
  const IPAddress remote = http.client().remoteIP();
  const IPAddress ap = WiFi.softAPIP();
  return remote[0] == ap[0] && remote[1] == ap[1] && remote[2] == ap[2];
}

void handleSetupFactoryReset() {
  if (!requestComesFromSetupAp()) {
    sendError(403, "setup_ap_connection_required");
    return;
  }
  if (!requestIsSetupAdmin()) return;
  if (http.arg("confirm_1") != "FACTORY_RESET" ||
      http.arg("confirm_2") != "FACTORY_RESET" ||
      http.arg("confirm_3") != "FACTORY_RESET") {
    http.send(400, "text/html; charset=utf-8",
              "<!doctype html><meta charset=utf-8><p>Semua tiga konfirmasi harus bertuliskan FACTORY_RESET.</p><a href='/setup'>Kembali</a>");
    return;
  }
  factoryResetPending = true;
  factoryResetAtMs = millis() + 500U;
  http.send(202, "text/html; charset=utf-8",
            "<!doctype html><meta charset=utf-8><p>Factory reset diterima. Server akan restart dan kembali ke mode setup.</p>");
}

void handleSetupPage() {
  if (!requestIsSetupAdmin()) return;
  const String html = R"HTML(<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ServerSmartPlug setup</title><style>body{margin:0;background:#f5f7f8;color:#10202a;font:16px Arial,sans-serif}.wrap{max-width:680px;margin:36px auto;padding:28px;background:#fff;border:1px solid #c7d1d5;border-radius:12px}h1{margin:0 0 10px}p{line-height:1.5}label{display:block;font-weight:bold;margin-top:15px}input{box-sizing:border-box;width:100%;padding:11px;margin-top:6px;border:1px solid #71838b;border-radius:6px;font-size:16px}button{margin-top:22px;padding:12px 16px;border:0;border-radius:6px;background:#075e54;color:#fff;font-weight:bold;font-size:16px}.danger{background:#a3212d}.note{background:#eef5f3;padding:12px;border-left:4px solid #075e54}.warn{margin-top:30px;padding-top:18px;border-top:1px solid #d4b3b7}.small{font-size:13px;color:#253940}</style><main class="wrap"><h1>ServerSmartPlug setup</h1><p>Configure the Wi-Fi connection, the application API token, and the credentials used by SmartPlug MQTT clients. The setup access point remains available after saving.</p><p class="note">The application calls this server REST API. SmartPlug devices use the MQTT broker at port 1883.</p><form method="post" action="/setup"><label>Wi-Fi SSID</label><input name="ssid" maxlength="32" required><label>Wi-Fi password</label><input name="wifi_password" type="password" maxlength="63"><label>Application API token</label><input name="api_token" type="password" minlength="16" maxlength="64" required><label>MQTT username</label><input name="broker_username" minlength="3" maxlength="32" required><label>MQTT password</label><input name="broker_password" type="password" minlength="8" maxlength="63" required><button type="submit">Save configuration</button></form><p class="small">After saving, use the ServerSmartPlug station IP shown by <code>/setup/status</code> as the MQTT broker host in SmartPlug.</p><section class="warn"><h2>Factory reset</h2><p>Only available while connected to the ServerSmartPlug setup Wi-Fi. This removes Wi-Fi, API token, MQTT credentials, and managed SmartPlug records. The SD card is not formatted.</p><form method="post" action="/setup/factory-reset" onsubmit="return confirm('Reset ServerSmartPlug now?')"><label>Type FACTORY_RESET three times</label><input name="confirm_1" autocomplete="off" required><input name="confirm_2" autocomplete="off" required><input name="confirm_3" autocomplete="off" required><button class="danger" type="submit">Factory reset server</button></form></section></main></html>)HTML";
  http.send(200, "text/html; charset=utf-8", html);
}

void handleSetupSave() {
  if (!requestIsSetupAdmin()) return;
  const String ssid = http.arg("ssid");
  const String wifiPassword = http.arg("wifi_password");
  const String apiToken = http.arg("api_token");
  const String brokerUsername = http.arg("broker_username");
  const String brokerPassword = http.arg("broker_password");
  if (ssid.isEmpty() || ssid.length() > 32 || wifiPassword.length() > 63 ||
      !validCredentialText(apiToken, 16, 64) ||
      !validCredentialText(brokerUsername, 3, 32) ||
      !validCredentialText(brokerPassword, 8, 63)) {
    http.send(400, "text/plain", "Invalid setup data. Token and MQTT credentials must use printable ASCII characters.");
    return;
  }
  copyText(settings.wifiSsid, ssid);
  copyText(settings.wifiPassword, wifiPassword);
  copyText(settings.apiToken, apiToken);
  copyText(settings.brokerUsername, brokerUsername);
  copyText(settings.brokerPassword, brokerPassword);
  if (!saveSettings()) {
    http.send(500, "text/plain", "Configuration could not be saved.");
    return;
  }
  settingsReady = true;
  // Do not log credentials, but keep enough commissioning evidence to distinguish
  // a malformed app payload from a router authentication/association failure.
  Serial.printf("INFO setup_apply ssid_length=%u password_length=%u\n",
                static_cast<unsigned>(strlen(settings.wifiSsid)),
                static_cast<unsigned>(strlen(settings.wifiPassword)));
  WiFi.disconnect(false, true);
  WiFi.begin(settings.wifiSsid, settings.wifiPassword);
  http.send(200, "text/html; charset=utf-8",
            "<p>Configuration saved. The server is connecting to Wi-Fi. Return to <a href='/setup'>setup</a> and read <code>/setup/status</code> for its station IP.</p>");
}

// PROPOSED (not yet in design.md as a contract): POST /api/v1/history/reset.
// Deletes only the measurement history file to free SD space. Device energy totals,
// snapshots, schedules, timers, the energy-reset audit log and unrelated SD files stay.
void handleHistoryReset() {
  if (!requestIsAuthorized()) return;
  JsonDocument document;
  if (!http.hasArg("plain") || deserializeJson(document, http.arg("plain")) ||
      String(document["confirm_1"] | "") != "RESET_HISTORY" ||
      String(document["confirm_2"] | "") != "RESET_HISTORY" ||
      String(document["confirm_3"] | "") != "RESET_HISTORY") {
    sendError(400, "triple_confirmation_required");
    return;
  }
  if (!sdReady) { sendError(503, "storage_unavailable"); return; }
  uint64_t freedBytes = 0;
  if (SD.exists(kHistoryPath)) {
    File file = SD.open(kHistoryPath, FILE_READ);
    if (file) { freedBytes = file.size(); file.close(); }
    if (!SD.remove(kHistoryPath)) { sendError(503, "storage_write_failed"); return; }
  }
  freedBytes += eraseFineFiles();
  memset(historyBytesByDevice, 0, sizeof(historyBytesByDevice));
  historyBytesUnattributed = 0;
  historyScanOffset = 0;
  historyScanDone = true;
  sdCapacityCachedAtMs = 0U;  // freed space must show up immediately, not after the 5-minute cache
  Serial.println(F("INFO history_reset_complete"));
  sendJson(200, String("{\"result\":\"history_reset\",\"freed_bytes\":") +
                String(static_cast<unsigned long>(freedBytes)) + "}");
}

void handleNotFound() {
  if (http.method() == HTTP_OPTIONS) {
    http.sendHeader("Access-Control-Allow-Origin", "*");
    http.sendHeader("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type");
    http.sendHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
    http.send(204);
    return;
  }
  const String uri = http.uri();
  if (uri.startsWith("/api/v1/devices/")) { handleDeviceRoute(); return; }
  if (uri.startsWith("/api/v1/commands/")) { handleCommandRoute(); return; }
  if (uri.startsWith("/api/v1/mqtt-auth/devices/")) { handleMqttAuthRoute(); return; }
  sendError(404, "not_found");
}

void beginHttpApi() {
  const char* headers[] = {"Authorization", "X-API-Key", "Content-Type"};
  http.collectHeaders(headers, 3);
  http.on("/", HTTP_GET, handleSetupPage);
  http.on("/setup", HTTP_GET, handleSetupPage);
  http.on("/setup", HTTP_POST, handleSetupSave);
  http.on("/setup/factory-reset", HTTP_POST, handleSetupFactoryReset);
  http.on("/setup/status", HTTP_GET, handleSetupStatus);
  http.on("/setup/scan-wifi", HTTP_POST, handleSetupScanWifi);
  http.on("/api/v1/factory-reset", HTTP_POST, handleServerFactoryReset);
  http.on("/health", HTTP_GET, handleHealth);
  http.on("/api/v1/status", HTTP_GET, handleStatus);
  http.on("/api/v1/devices", HTTP_GET, handleDevices);
  http.on("/api/v1/history/reset", HTTP_POST, handleHistoryReset);
  http.onNotFound(handleNotFound);
  http.begin();
}

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.printf("INFO server_boot version=%s\\n", kServerVersion);
  loadSettings();
  startNetwork();
  beginStorage();
  beginHttpApi();
  mqttBroker.begin();
}

void loop() {
  http.handleClient();
  if (factoryResetPending && static_cast<int32_t>(millis() - factoryResetAtMs) >= 0) {
    performFactoryReset();
  }
  serviceMdns();
  configureTimeIfConnected();
  serviceMqttBroker();
  serviceBrokerHeartbeat();
  expireStaleDevices();
  expireCommands();
  serviceTimers();
  serviceBootRestore();
  serviceSchedules();
  serviceStorage();
  serviceHistoryScan();
  serviceFineHistory();
  delay(2);
}
