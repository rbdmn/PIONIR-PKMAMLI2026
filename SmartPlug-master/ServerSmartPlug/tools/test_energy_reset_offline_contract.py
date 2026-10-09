"""Static regression contract for offline ServerSmartPlug energy reset.

This is deliberately a source contract rather than a fake ESP32/MQTT runtime.
It proves the safety-critical ordering: a reset is persisted before its first
QoS0 publication, replayed on availability and sync/request while pending,
and stopped only after a physical near-zero counter confirmation.
"""

from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "src" / "main.cpp"


def between(text: str, begin: str, end: str) -> str:
    start = text.index(begin)
    finish = text.index(end, start)
    return text[start:finish]


def between_last(text: str, begin: str, end: str) -> str:
    start = text.rindex(begin)
    finish = text.index(end, start)
    return text[start:finish]


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")
    # main.cpp keeps an explicitly disabled raw-parser migration reference
    # above the PicoMQTT production implementation. Contract assertions below
    # must inspect the compiled half only.
    production = text[text.index("#endif  // retired raw MQTT parser") :]

    # Persist the pending flag as a trailing field so older index rows remain
    # readable, then restore it after reboot when that field is present.
    assert '"%s,%.3f,%s,%lu,%lu,%lu,%lu,%u,%u,%u,%u\\n"' in text
    assert "device.awaitingEnergyReset ? 1U : 0U" in text
    assert "const int tenthComma = line.indexOf(',', ninthComma + 1);" in text
    assert "device->awaitingEnergyReset = line.substring(tenthComma + 1).toInt() != 0;" in text

    reset_route = between(production, 'action == "energy/reset"', '} else if (http.method() == HTTP_POST && action == "factory-reset")')
    for required in (
        'if (!sdReady) { sendError(503, "storage_unavailable"); return; }',
        "device->awaitingEnergyReset = true;",
        "if (!saveDeviceIndex()) {",
        "publishEnergySync(*device, true);",
    ):
        assert required in reset_route, f"reset durability behavior missing: {required}"
    assert reset_route.index("device->awaitingEnergyReset = true;") < reset_route.index("if (!saveDeviceIndex()) {") < reset_route.index("publishEnergySync(*device, true);"), (
        "must persist pending reset before the first QoS0 publish"
    )

    handler = between_last(production, "void handleMqttApplicationMessage", "void handleMqttV2ApplicationMessage")
    assert "if (device->online) publishEnergySync(*device, device->awaitingEnergyReset);" in handler
    sync = between(handler, '} else if (suffix == "sync/request") {', "  }\n  else if (suffix == \"state\")")
    assert "publishSnapshot(*device);" in sync
    assert "republishPendingEnergyReset(*device);" in sync

    # Confirmation clears the pending state only underneath an energy <= 1.0F
    # test. Once false, both reissue paths are guarded and cannot reset again.
    all_parameters = between(production, "void handleAllParameters", "void handleMqttApplicationMessage")
    assert "if (device.awaitingEnergyReset)" in all_parameters
    assert "if (energy <= 1.0F)" in all_parameters
    assert "device.awaitingEnergyReset = false;" in all_parameters
    reissue = between(production, "void republishPendingEnergyReset", "void publishSnapshot")
    assert "if (!device.awaitingEnergyReset) return;" in reissue
    assert "publishEnergySync(device, true);" in reissue

    print("PASS offline energy-reset durability contract")


if __name__ == "__main__":
    main()
