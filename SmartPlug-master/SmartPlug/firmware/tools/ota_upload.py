"""Retired helper retained to prevent an unsafe OTA attempt on 512 KB flash."""

raise SystemExit(
    "OTA disabled: this SmartPlug board has 512 KB flash and cannot safely "
    "stage the current firmware image. Use U6/serial with AC disconnected."
)
