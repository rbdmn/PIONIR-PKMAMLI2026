"""Static regression guard for owner-only server routing.

These paths rely on Android encrypted storage and Retrofit, so this guard
checks the policy split directly while JVM tests cover ViewModel action gating.
"""
from pathlib import Path


ANDROID = Path(__file__).resolve().parents[1]
REPOSITORY = ANDROID / "app/src/main/java/com/smartplug/app/data/repository/DeviceRepositoryImpl.kt"


def section(source: str, start: str, end: str) -> str:
    offset = source.index(start)
    return source[offset:source.index(end, offset)]


def test_owner_only_for_server_changes_member_for_daily_reads() -> None:
    source = REPOSITORY.read_text(encoding="utf-8")
    connect = section(source, "override suspend fun connectToServer", "override suspend fun disconnectFromServer")
    disconnect = section(source, "override suspend fun disconnectFromServer", "override suspend fun canManageServer")
    status = section(source, "private suspend fun fetchDirectStatus", "private suspend fun fetchDirectMeasurement")
    measurement = section(source, "private suspend fun fetchDirectMeasurement", "private suspend fun fetchServerStatus")

    assert "requireOwnerToken(device.deviceId)" in connect
    assert "requireOwnerToken(device.deviceId)" in disconnect
    assert "requireOperationalCredential(device.deviceId)" in status
    assert "requireOperationalCredential(device.deviceId)" in measurement


if __name__ == "__main__":
    test_owner_only_for_server_changes_member_for_daily_reads()
    print("PASS: owner-only server changes; member operational reads preserved")
