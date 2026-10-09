package com.smartplug.app.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** DTOs for the SmartPlug AP-only pairing API, `design.md` "Pairing API". */

@JsonClass(generateAdapter = true)
data class PairInfoDto(
    @Json(name = "api_version") val apiVersion: String,
    @Json(name = "product") val product: String,
    @Json(name = "protocol") val protocol: String,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "sta_mac") val staMac: String,
    @Json(name = "state") val state: String,
    @Json(name = "pairing_token") val pairingToken: String,
    @Json(name = "token_expires_in_s") val tokenExpiresInS: Int,
)

@JsonClass(generateAdapter = true)
data class PairScanWifiResponseDto(
    @Json(name = "api_version") val apiVersion: String,
    @Json(name = "state") val state: String,
    @Json(name = "networks") val networks: List<WifiNetworkDto>,
)

@JsonClass(generateAdapter = true)
data class WifiNetworkDto(
    @Json(name = "ssid") val ssid: String,
    @Json(name = "rssi") val rssi: Int,
    @Json(name = "security") val security: String,
)

@JsonClass(generateAdapter = true)
data class ServerConnectionProfileDto(
    @Json(name = "type") val type: String = "server",
    @Json(name = "server_id") val serverId: String,
    @Json(name = "broker_host") val brokerHost: String,
    @Json(name = "broker_port") val brokerPort: Int,
    @Json(name = "mqtt_username") val mqttUsername: String,
    @Json(name = "mqtt_password") val mqttPassword: String,
    @Json(name = "base_topic") val baseTopic: String,
)

@JsonClass(generateAdapter = true)
data class DirectConnectionProfileDto(
    @Json(name = "type") val type: String = "direct",
)

@JsonClass(generateAdapter = true)
data class PairConfigureRequestDto(
    @Json(name = "ssid") val ssid: String,
    @Json(name = "password") val password: String,
    /** Either a [DirectConnectionProfileDto] or [ServerConnectionProfileDto]; Moshi serializes
     * whichever concrete map is supplied by [com.smartplug.app.data.remote.PairingApi]. */
    @Json(name = "connection_profile") val connectionProfile: Map<String, Any?>,
)

@JsonClass(generateAdapter = true)
data class PairConfigureResponseDto(
    @Json(name = "api_version") val apiVersion: String,
    @Json(name = "configuration_id") val configurationId: String,
    @Json(name = "state") val state: String,
)

@JsonClass(generateAdapter = true)
data class PairStatusResponseDto(
    @Json(name = "api_version") val apiVersion: String,
    @Json(name = "state") val state: String,
    @Json(name = "ssid") val ssid: String? = null,
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "sta_mac") val staMac: String? = null,
    @Json(name = "lan_ip") val lanIp: String? = null,
    @Json(name = "owner_token") val ownerToken: String? = null,
    @Json(name = "reason") val reason: String? = null,
)
