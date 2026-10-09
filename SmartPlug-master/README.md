# SmartPlug

Open source reference implementation for the SmartPlug Android application,
SmartPlug firmware, and ServerSmartPlug firmware.

## Repository layout

- `SmartPlug/` — Android application, firmware, hardware sources, and public
  engineering documentation.
- `ServerSmartPlug/` — ESP32 server firmware used by the server integration
  mode.

## Public developer documentation

- [`SmartPlug/README.md`](SmartPlug/README.md) — board/source orientation and
  safety boundary.
- [`SmartPlug/docs/design.md`](SmartPlug/docs/design.md) — current system
  design and Direct/Server behaviour.
- [`SmartPlug/android/README.md`](SmartPlug/android/README.md) — Android
  onboarding, build, and user-flow contract.
- [`SmartPlug/firmware/README.md`](SmartPlug/firmware/README.md) and
  [`ServerSmartPlug/README.md`](ServerSmartPlug/README.md) — firmware build
  instructions.

Historical validation notes, private engineering trackers, evidence snapshots,
and legacy source copies are preserved in
[`internal-records/SmartPlug-internal-records.rar`](internal-records/SmartPlug-internal-records.rar).
They are not build inputs and are intentionally kept out of the browsable
developer documentation.

> Safety notice: this repository contains engineering source and is not proof
> that a mains-connected product is safe, certified, or production-ready.
> Review the public design documentation and perform an appropriate hardware
> safety review before building or energizing any hardware.
