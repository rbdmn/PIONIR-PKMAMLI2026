"""Static contract guard for Direct-to-Server automation hand-off.

ESP8266 HTTP and LittleFS code cannot be faithfully executed by the host
suite.  This guard protects the authority boundary while the product build
proves the target sources compile together.
"""

from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "src" / "main.cpp"
TEXT = SOURCE.read_text(encoding="utf-8")


def between(start: str, end: str) -> str:
    return TEXT[TEXT.index(start):TEXT.index(end, TEXT.index(start))]


authority = between("bool SmartPlugApi::localAutomationAllowed()", "String SmartPlugApi::scheduleJson()")
assert "return mqtt_ == nullptr || !mqtt_->mqttMode();" in authority
assert "timer_persist::save(0U, 0U)" in authority
assert "persistentSettings.scheduleEnabled = 0U;" in authority
assert "persistentSettings.scheduleCount = 0U;" in authority

timer = between("void SmartPlugApi::handleTimer()", "String SmartPlugApi::scheduleJson()")
assert 'sendError(409, "automation_requires_direct_mode")' in timer
assert timer.index("localAutomationAllowed()") < timer.index("const String action")

schedule = between("void SmartPlugApi::handleSchedule()", "bool SmartPlugApi::takeEnergyReset()")
assert 'sendError(409, "automation_requires_direct_mode")' in schedule
assert schedule.index("localAutomationAllowed()") < schedule.index("const String action")

timer_service = between("bool SmartPlugApi::takeTimerOff()", "void SmartPlugApi::reportTimerOffRequestResult")
schedule_service = between("bool SmartPlugApi::takeScheduledRelayCommand", "void SmartPlugApi::serviceRealtimeClock")
assert "if (!localAutomationAllowed()) return false;" in timer_service
assert "if (!localAutomationAllowed()) return false;" in schedule_service

mqtt = between("void SmartPlugApi::handleMqttSettings()", "void SmartPlugApi::handleRelayCommand()")
assert "wasServerMode && !requestingServerMode" in mqtt
assert "automation_transition_clear_failed" in mqtt
assert "!wasServerMode && requestingServerMode" in mqtt
assert mqtt.index("mqtt_->saveWebSettings") < mqtt.index("localAutomationCleared = clearLocalAutomationForServerTransition()")

print("PASS automation authority transition contract")
