"""Static contract check for ServerSmartPlug timer expiry offline safety.

This keeps the firmware source contract reviewable without pretending to
simulate MQTT/ESP32 hardware in a host unit test.  It verifies the freshness
predicate and, critically, that serviceTimers gates every attempt increment
and QoS0 relay publish behind that predicate.
"""

from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "src" / "main.cpp"
text = SOURCE.read_text(encoding="utf-8")

helper = """bool deviceHasFreshTelemetry(const DeviceRecord& device, const uint32_t nowMs) {
  return device.online && device.lastSeenMs != 0U &&
      nowMs - device.lastSeenMs <= kDeviceOfflineTimeoutMs;
}"""
assert helper in text, "fresh online predicate missing or weakened"

start = text.index("void serviceTimers()")
end = text.index("\nvoid serviceSchedules()", start)
timer_body = text[start:end]
gate = "if (!deviceHasFreshTelemetry(device, nowMs) ||\n        strcmp(device.commandStatus, \"queued\") == 0) continue;"
assert gate in timer_body, "timer expiry must skip while stale/offline"

for operation in (
    "++device.timerExpiryAttempts;",
    'publishBrokerMessage(String("smartplug/") + device.id + "/cmd/relay", "off", false, 0U);',
):
    assert operation in timer_body, f"expected timer operation missing: {operation}"
    assert timer_body.index(gate) < timer_body.index(operation), (
        f"offline freshness gate must precede: {operation}"
    )

assert "device.timerDeadlineUtc = 0U;" not in timer_body, (
    "serviceTimers must not clear an expired deadline before ACK"
)
print("PASS timer offline/reconnect contract")
