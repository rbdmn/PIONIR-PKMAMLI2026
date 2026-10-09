"""Static contract for starting an armed ServerSmartPlug timer after relay ON."""

from pathlib import Path


source = (Path(__file__).resolve().parents[1] / "src" / "main.cpp").read_text(encoding="utf-8")

helper_start = source.rindex("void startArmedTimerAfterRelayOn(DeviceRecord& device)")
helper_end = source.index("\nvoid handleRelayAcknowledgement", helper_start)
helper = source[helper_start:helper_end]
for expected in (
    'strcmp(device.relayState, "on") != 0',
    "device.timerDeadlineUtc = now + device.timerDurationSeconds;",
    "device.timerDurationSeconds = 0U;",
):
    assert expected in helper, f"missing armed timer transition: {expected}"

ack_start = helper_end + 1
ack_end = source.index("\nvoid handleAllParameters", ack_start)
ack = source[ack_start:ack_end]
assert ack.index("startArmedTimerAfterRelayOn(device);") < ack.index('strcmp(device.commandStatus, "queued")'), (
    "relay acknowledgement must arm the waiting timer even when no command is queued"
)

active_handler_start = source.index("void handleMqttApplicationMessage(const String& topic, const String& payload)", helper_end)
active_handler = source[active_handler_start:source.index("\nvoid handleMqttV2ApplicationMessage", active_handler_start)]
state_start = active_handler.index('else if (suffix == "state")')
state_end = active_handler.index('} else if (suffix == "ack/relay")', state_start)
assert "startArmedTimerAfterRelayOn(*device);" in active_handler[state_start:state_end], (
    "relay state publication must arm the waiting timer"
)

print("PASS timer armed-to-running contract")
