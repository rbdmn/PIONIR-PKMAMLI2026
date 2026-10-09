"""Static contract guard for the owner-only MQTT route.

The ESP8266 HTTP handler is tied to ESP8266WebServer and cannot be executed by
the portable Unity suite.  This guard protects the security boundary at the
source level while the firmware build verifies the actual target compiles.
"""
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "src" / "main.cpp"


def handler_body(source: str) -> str:
    start = source.index("void SmartPlugApi::handleMqttSettings()")
    end = source.index("void SmartPlugApi::handleRelayCommand()", start)
    return source[start:end]


def test_mqtt_settings_requires_owner_not_operational_member() -> None:
    body = handler_body(SOURCE.read_text(encoding="utf-8"))
    assert "const bool viaOwnerToken = requireOwnerTokenBearer();" in body
    assert "if (!viaOwnerToken && !requireSession(true)) return;" in body
    assert "requireOperationalTokenBearer();" not in body


if __name__ == "__main__":
    test_mqtt_settings_requires_owner_not_operational_member()
    print("PASS: MQTT settings is owner/session only")
