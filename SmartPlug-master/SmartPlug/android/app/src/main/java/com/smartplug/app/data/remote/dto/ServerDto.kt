package com.smartplug.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** DTOs for ServerSmartPlug's application REST API, design.md "REST API aplikasi ke server". */

/**
 * One-time SPMQTT2 provisioning material.  The value is generated for one
 * connect attempt, sent only to the registered ServerSmartPlug and local
 * SmartPlug over authenticated REST, and deliberately never persisted.
 */
@JsonClass(generateAdapter = true)
data class MqttAuthDeviceRequestDto(
    @Json(name = "signing_secret") val signingSecret: String,
)

/** Typed payloads avoid Kotlin's `Map<String, *>` wildcard at the Retrofit boundary.
 * The ServerSmartPlug accepts the same JSON field names as before. */
@JsonClass(generateAdapter = true)
data class ServerTimerRequestDto(
    @Json(name = "action") val action: String,
    @Json(name = "days") val days: Int? = null,
    @Json(name = "hours") val hours: Int? = null,
    @Json(name = "minutes") val minutes: Int? = null,
    @Json(name = "seconds") val seconds: Int? = null,
)

@JsonClass(generateAdapter = true)
data class ServerScheduleRequestDto(
    @Json(name = "action") val action: String,
    @Json(name = "timezone_offset_minutes") val timezoneOffsetMinutes: Int? = null,
    @Json(name = "enabled") val enabled: Boolean? = null,
    @Json(name = "hour") val hour: Int? = null,
    @Json(name = "minute") val minute: Int? = null,
    @Json(name = "state") val state: String? = null,
    @Json(name = "event") val event: String? = null,
    @Json(name = "index") val index: Int? = null,
    @Json(name = "from") val from: Int? = null,
    @Json(name = "to") val to: Int? = null,
)

@JsonClass(generateAdapter = true)
data class ServerDeviceDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "display_name") val displayName: String? = null,
    @Json(name = "status") val status: String,
)

@JsonClass(generateAdapter = true)
data class ServerLatestDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "captured_at_ms") val capturedAtMs: Long,
    @Json(name = "status") val status: String,
    @Json(name = "relay_state") val relayState: String,
    @Json(name = "calibrated") val calibrated: Boolean = false,
    @Json(name = "voltage_v") val voltageV: Double = 0.0,
    @Json(name = "current_a") val currentA: Double = 0.0,
    @Json(name = "active_power_w") val activePowerW: Double = 0.0,
    @Json(name = "apparent_power_va") val apparentPowerVa: Double = 0.0,
    @Json(name = "power_factor") val powerFactor: Double = 0.0,
    @Json(name = "energy_wh") val energyWh: Double = 0.0,
    @Json(name = "timer_deadline_utc") val timerDeadlineUtc: Long = 0,
    @Json(name = "timer_duration_seconds") val timerDurationSeconds: Long = 0,
    @Json(name = "schedule") val schedule: DeviceScheduleDto? = null,
)

@JsonClass(generateAdapter = true)
data class ServerEnergyDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "energy_wh") val energyWh: Double,
    @Json(name = "source") val source: String,
    @Json(name = "recorded_at_ms") val recordedAtMs: Long,
)

@JsonClass(generateAdapter = true)
data class ServerHistoryPointDto(
    @Json(name = "timestamp_utc_ms") val timestampUtcMs: Long,
    @Json(name = "voltage_v") val voltageV: Double,
    @Json(name = "current_a") val currentA: Double,
    @Json(name = "active_power_w") val activePowerW: Double,
    @Json(name = "apparent_power_va") val apparentPowerVa: Double,
    @Json(name = "power_factor") val powerFactor: Double,
    @Json(name = "energy_wh") val energyWh: Double,
)

@JsonClass(generateAdapter = true)
data class ServerHistoryResponseDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "resolution") val resolution: String,
    @Json(name = "points") val points: List<ServerHistoryPointDto>,
)

@JsonClass(generateAdapter = true)
data class ServerRelayResponseDto(
    @Json(name = "command_id") val commandId: String,
    @Json(name = "status") val status: String,
    @Json(name = "state") val state: String,
)

@JsonClass(generateAdapter = true)
data class ServerCommandStatusDto(
    @Json(name = "command_id") val commandId: String,
    @Json(name = "status") val status: String,
    @Json(name = "state") val state: String? = null,
)

/**
 * GET /api/v1/status. The "storage" object is a PROPOSAL awaiting approval in a separate server
 * task (not part of design.md yet): [ServerStorageDto.sdTotalBytes] / [ServerStorageDto.sdUsedBytes].
 * Everything is optional so today's server, which has no such field, still parses.
 */
@JsonClass(generateAdapter = true)
data class ServerStatusDto(
    @Json(name = "storage") val storage: ServerStorageDto? = null,
)

@JsonClass(generateAdapter = true)
data class ServerStorageDto(
    @Json(name = "sd_total_bytes") val sdTotalBytes: Long? = null,
    @Json(name = "sd_used_bytes") val sdUsedBytes: Long? = null,
    /** PROPOSED: false while the server is still tallying its history file per SmartPlug. */
    @Json(name = "history_scan_complete") val historyScanComplete: Boolean? = null,
    @Json(name = "history_bytes_total") val historyBytesTotal: Long? = null,
    @Json(name = "history_by_device") val historyByDevice: List<ServerDeviceStorageDto>? = null,
)

@JsonClass(generateAdapter = true)
data class ServerDeviceStorageDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "bytes") val bytes: Long? = null,
)
