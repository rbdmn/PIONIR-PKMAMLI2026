"""Guard the ESP8266 512 KiB / 32 KiB LittleFS application slot.

The production ``smartplug_product_32k`` image is linked with
``eagle.flash.512k32.ld``.  Its maximum application image is 466,928 bytes.
Keep this guard as a post-build action so a binary that no longer fits cannot
be treated as a release candidate.

It measures the same ELF sections used by PlatformIO's ESP8266 size checker,
not the generated ``.bin`` file (which includes image headers). It can also be
run directly for release verification:
    python tools/check_esp8266_32k_capacity.py .pio/build/smartplug_product_32k/firmware.elf
"""

from __future__ import annotations

import pathlib
import os
import re
import shutil
import subprocess
import sys


SLOT_BYTES = 466_928
# Ceiling of 95% of SLOT_BYTES.  Warning is deliberately non-fatal so the
# current near-limit product image can still be built, but release evidence
# cannot overlook the remaining headroom.
WARNING_BYTES = 443_582


PROGRAM_SECTION_PATTERN = re.compile(
    r"^(?:\.irom0\.text|\.text|\.text1|\.data|\.rodata|)\s+([0-9]+).*"
)


def report_size(size: int) -> int:
    percent = (size * 100.0) / SLOT_BYTES
    message = (
        "ESP8266 32K slot: "
        f"{size:,} / {SLOT_BYTES:,} B ({percent:.1f}%), "
        f"headroom {SLOT_BYTES - size:,} B"
    )

    if size > SLOT_BYTES:
        print(f"ERROR: {message}; image exceeds the application slot.")
        return 1
    if size >= WARNING_BYTES:
        print(f"WARNING: {message}; at or above the 95% release warning threshold.")
    else:
        print(f"OK: {message}; below the 95% release warning threshold.")
    return 0


def program_size_from_elf(elf: pathlib.Path, size_tool: str, command_env=None) -> int:
    """Return the same aggregate program size PlatformIO reports for ESP8266."""
    completed = subprocess.run(
        [size_tool, "-A", "-d", str(elf)],
        check=True,
        capture_output=True,
        text=True,
        env=command_env,
    )
    return sum(
        int(match.group(1))
        for line in completed.stdout.splitlines()
        if (match := PROGRAM_SECTION_PATTERN.search(line.strip()))
    )


def default_size_tool() -> str:
    """Find PlatformIO's ESP8266 size tool for standalone verification."""
    name = "xtensa-lx106-elf-size.exe" if os.name == "nt" else "xtensa-lx106-elf-size"
    if found := shutil.which(name):
        return found
    candidate = pathlib.Path.home() / ".platformio" / "packages" / "toolchain-xtensa" / "bin" / name
    if candidate.is_file():
        return str(candidate)
    return name


def _post_build_action(target, source, env):  # PlatformIO/SCons callback.
    # For this post-action, SCons reports target=firmware.bin and
    # source=firmware.elf. The ELF is intentional: PlatformIO's slot figure is
    # the sum of loaded sections, while the bin contains image headers.
    elf = pathlib.Path(str(source[0]))
    command_env = os.environ.copy()
    command_env["PATH"] = str(env["ENV"]["PATH"])
    size = program_size_from_elf(elf, env.subst("$SIZETOOL"), command_env)
    status = report_size(size)
    if status:
        raise RuntimeError("ESP8266 32K application image exceeds 466,928 B")


def _install_platformio_hook() -> bool:
    try:
        Import("env")  # type: ignore[name-defined]  # supplied by SCons
    except NameError:
        return False

    env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", _post_build_action)
    return True


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--size":
        raise SystemExit(report_size(int(sys.argv[2])))
    if len(sys.argv) != 2:
        print(
            f"Usage: {pathlib.Path(sys.argv[0]).name} <firmware.elf> | --size <bytes>",
            file=sys.stderr,
        )
        raise SystemExit(2)
    raise SystemExit(
        report_size(program_size_from_elf(pathlib.Path(sys.argv[1]), default_size_tool()))
    )

_install_platformio_hook()
