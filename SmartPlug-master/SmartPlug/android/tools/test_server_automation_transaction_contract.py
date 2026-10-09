"""Guard the ServerSmartPlug authority handover transaction.

This is deliberately a small source contract check: a dropped network response
must not turn a selected route into a split automation authority.  Runtime
behaviour remains covered by the Android repository tests and physical gates.
"""

from pathlib import Path


SOURCE = Path(__file__).parents[1] / "app/src/main/java/com/smartplug/app/data/repository/DeviceRepositoryImpl.kt"


def between(source: str, start: str, end: str) -> str:
    return source.split(start, 1)[1].split(end, 1)[0]


def require_order(body: str, *markers: str) -> None:
    positions = [body.index(marker) for marker in markers]
    assert positions == sorted(positions), markers


def main() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    connect = between(source, "override suspend fun connectToServer", "override suspend fun disconnectFromServer")
    disconnect = between(source, "override suspend fun disconnectFromServer", "override suspend fun canManageServer")

    require_order(
        connect,
        "serverApi.registerMqttAuthDevice",
        "serverApi.resetAutomation",
        "deviceApi.setMqttSettings",
        "saveDevice(device.copy(",
    )
    assert "if (automationReset is ApiResult.Failure)" in connect
    assert "serverApi.deleteMqttAuthDevice" in connect

    require_order(
        disconnect,
        "serverApi.resetAutomation",
        "api.setMqttSettings",
        "saveDevice(device.copy(integrationMode = IntegrationMode.DIRECT",
        "serverApi.deleteMqttAuthDevice",
    )
    assert "if (automationReset is ApiResult.Failure) return automationReset" in disconnect
    print("PASS Android Server automation handover transaction contract")


if __name__ == "__main__":
    main()
