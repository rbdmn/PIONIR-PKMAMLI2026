# ServerSmartPlug — ESP32 firmware

`ServerSmartPlug` is the ESP32-side server for SmartPlug installations configured in MQTT mode. It runs these local services from one firmware image:

- MQTT 3.1.1 broker on TCP port 1883 for SmartPlug telemetry, relay commands, acknowledgement, retained state, availability, and energy synchronization.
- Application REST API on TCP port 80, protected by an API token.
- SD-card backed latest-state and one-minute history storage.
- A protected Wi-Fi access-point setup page; no serial-monitor step is part of normal commissioning.

## First commissioning

1. Power the ESP32 and join its setup access point: `ServerSmartPlug-Setup`.
2. Enter the setup password: `SmartPlugSetup`.
3. Open the local setup page displayed at the access point gateway and save the Wi-Fi credentials, application API token, MQTT username, and MQTT password.
4. Configure each SmartPlug for MQTT mode with the server's Wi-Fi address, port `1883`, and the MQTT credentials saved above.
5. Integrate the application through the server REST API, not directly through MQTT.

The access point remains available for later configuration. The broker denies MQTT connections until broker credentials have been configured. The application REST API denies access until its API token has been configured.

The broker accepts SmartPlug identities in the form `SmartPlug-SP-<STA_MAC>`.
After authentication, it enforces the device's own `smartplug/SP-<STA_MAC>/...`
base topic: a SmartPlug may publish only telemetry/state/availability/relay-ack
messages and may subscribe only to its own relay-command and energy-sync topics.

## REST API for the application

Every `/api/v1/...` endpoint requires either:

```text
Authorization: Bearer <application-api-token>
```

or:

```text
X-API-Key: <application-api-token>
```

Implemented routes are:

```text
GET  /api/v1/status
GET  /api/v1/devices
GET  /api/v1/devices/{device_id}
GET  /api/v1/devices/{device_id}/latest
GET  /api/v1/devices/{device_id}/history?from=<utc>&to=<utc>&resolution=1m|5m|30m|1h|1d
GET  /api/v1/devices/{device_id}/energy
POST /api/v1/devices/{device_id}/relay       {"state":"on"|"off"}
POST /api/v1/devices/{device_id}/automation/reset
GET  /api/v1/commands/{command_id}
GET  /health
```

The server permits one pending relay command per device. It returns `409` when the device is offline or already has a pending command, resolves matching `ack/relay` messages as `completed` or `rejected`, and marks an unanswered command as `timeout` after five seconds. When a timer expires, its deadline is retained until the matching accepted `OFF` acknowledgement arrives. While the device is offline or its telemetry is stale, the server retains the expired deadline without publishing a QoS0 relay command or consuming an expiry retry. Once fresh telemetry confirms the device is online again, a rejected or unanswered expiry command is retried at most three times with a two-second backoff; after that the deadline remains stored and the timer endpoint/latest device data expose `expiry_failed=true` for explicit recovery rather than silently discarding the expiry.

`POST /api/v1/devices/{device_id}/automation/reset` clears only that device's
server-owned timer and daily Schedule before attachment or detachment. It never
changes the relay state. If a timer/schedule relay command is already queued,
the endpoint returns `409 automation_command_pending` because a published MQTT
message cannot be recalled; the caller must wait for the command to resolve
before treating the detach as complete.

## Storage behaviour

Telemetry refreshes latest data whenever `measurement/allparameters` arrives. The server aggregates voltage, current, active power, apparent power, and power factor into one-minute records; energy is stored as the latest cumulative counter, never averaged. The server maintains the greatest valid energy counter it has received and publishes it on `<base>/sync/energy` when the SmartPlug announces `availability=online`.

An energy reset is different from ordinary synchronization: before its first MQTT publish the server durably records an `awaiting reset` state in the device index. If the SmartPlug is offline, the server reissues the legacy-compatible `sync/energy` payload with `reset=true` on its next online availability and on every boot `sync/request` until live physical telemetry reports an energy counter at or below 1 Wh. That confirmation clears the durable flag, so later reconnects cannot repeat a reset that has already been applied.

The SD card is the durable store. The status endpoint reports whether it was mounted. The `/health` field `snapshot_file_bytes` is the last size successfully loaded or written by the runtime, so a frequent health poll does not itself open the SD-card file. `snapshot_storage` additionally exposes non-secret write attempts, successes, failures, target interval, latest successful-write UTC/age/cadence, and latest failure age.

If an atomic snapshot write, re-open verification, or its storage directory preparation fails, the firmware marks `sd_ready=false`, keeps the latest telemetry in RAM, and retries a clean SD mount after five seconds. Persisted index/snapshot data is restored only during cold-start recovery before RAM has accepted runtime changes. A later hot-remount never reloads those files over current RAM relay, timer, schedule/index, or measurement state; it instead marks the current RAM index and snapshots dirty and writes them back promptly. The last known snapshot size remains diagnostic only while storage is unavailable; it is not proof of a fresh durable write. The default SD pins are defined in `platformio.ini` and must match the actual ServerSmartPlug board before hardware deployment.

## Build

```powershell
pio run -d D:\IoT\ServerSmartPlug -e server_esp32
```

This build command compiles the firmware only. It does not upload, provision, or prove MQTT, SD-card, or relay behaviour on physical hardware.
