"""PlatformIO pre-build hook. Never embeds a shared default in a factory image."""
import os
from pathlib import Path
Import("env")
profile = os.environ.get("SMARTPLUG_FACTORY_PROFILE", "")
path = Path(profile).resolve() if profile else None
if not path or not path.is_file():
    raise RuntimeError("Factory build requires SMARTPLUG_FACTORY_PROFILE; generate the unit label/profile first.")
env.Append(CPPDEFINES=["SMARTPLUG_FACTORY_PROVISIONED=1"])
env.Append(CCFLAGS=["-include", str(path)])
