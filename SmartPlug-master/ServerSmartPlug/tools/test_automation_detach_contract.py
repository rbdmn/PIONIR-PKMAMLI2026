"""Static contract for safe ServerSmartPlug automation detach/reset."""

from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "src" / "main.cpp"
TEXT = SOURCE.read_text(encoding="utf-8")


def between(start: str, end: str) -> str:
    return TEXT[TEXT.index(start):TEXT.index(end, TEXT.index(start))]


helper = between("bool clearDeviceAutomation(DeviceRecord& device)", "String jsonField")
for expected in (
    "device.timerDeadlineUtc = 0U;",
    "device.timerDurationSeconds = 0U;",
    "device.scheduleEnabled = false;",
    "device.scheduleCount = 0U;",
    "device.pendingScheduleDueUtc = 0U;",
    'strncmp(device.commandId, "timer-", 6U)',
    'strncmp(device.commandId, "schedule-", 9U)',
):
    assert expected in helper, f"missing detach reset: {expected}"

route = between("void handleDeviceRoute()", "void handleCommandRoute()")
assert 'action == "automation/reset"' in route
assert 'sendError(409, "automation_command_pending")' in route
assert "const DeviceRecord previous = *device;" in route
assert "if (!saveDeviceIndex())" in route
reset_route = route[route.index('action == "automation/reset"'):]
assert reset_route.index("clearDeviceAutomation(*device)") < reset_route.index("saveDeviceIndex()")

print("PASS server automation detach contract")
