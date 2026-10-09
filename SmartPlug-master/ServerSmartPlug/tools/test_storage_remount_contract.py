"""Static contract check for the ServerSmartPlug SD recovery split.

This repository does not have an ESP32 host-test environment.  Keep this
small check focused on a regression that is easy to reintroduce by moving the
SD load calls back into the generic mount path: persisted data may initialise
RAM at cold boot, but a runtime remount must preserve RAM and persist it.
"""

from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "src" / "main.cpp"


def between(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    finish = text.index(end, begin)
    return text[begin:finish]


def main() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    mount = between(source, "void beginStorage()", "// Retired MQTT packet parser")
    marker = "if (restoreColdBootRecovery)"
    assert marker in mount
    cold, hot = mount.split(marker, 1)[1].split("// The SD mount recovered", 1)
    assert "loadDeviceIndex();" in cold
    assert "loadSnapshots();" in cold
    assert "loadDeviceIndex();" not in hot
    assert "loadSnapshots();" not in hot
    assert "markRuntimeStateForStoragePersistence();" in hot

    persist = between(source, "void markRuntimeStateForStoragePersistence()", "void beginStorage()")
    assert "indexDirty = true;" in persist
    assert "snapshotDirty = true;" in persist
    assert "lastIndexFlushAtMs = now - kIndexFlushIntervalMs;" in persist
    assert "lastSnapshotFlushAtMs = now - kSnapshotFlushIntervalMs;" in persist

    print("PASS: SD cold-recovery and runtime-remount contract")


if __name__ == "__main__":
    main()
