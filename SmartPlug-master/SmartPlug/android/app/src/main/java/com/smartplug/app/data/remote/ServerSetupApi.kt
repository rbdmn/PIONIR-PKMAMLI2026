package com.smartplug.app.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** AP-local ServerSmartPlug setup contract. Calls are made only while the app is bound to its AP. */
interface ServerSetupApi {
    @GET("/setup/status")
    suspend fun status(
        @Header("Authorization") authorization: String = SERVER_SETUP_BASIC_AUTH,
    ): Response<ServerSetupStatusDto>

    @FormUrlEncoded
    @POST("/setup")
    suspend fun apply(
        @FieldMap values: Map<String, String>,
        @Header("Authorization") authorization: String = SERVER_SETUP_BASIC_AUTH,
    ): Response<Unit>

    @POST("/setup/scan-wifi")
    suspend fun scanWifi(
        @Header("Authorization") authorization: String = SERVER_SETUP_BASIC_AUTH,
    ): Response<ServerSetupScanWifiResponseDto>
}

/** `admin:SmartPlugSetup`, used only while the phone is bound to the WPA2-protected setup AP. */
private const val SERVER_SETUP_BASIC_AUTH = "Basic YWRtaW46U21hcnRQbHVnU2V0dXA="

@JsonClass(generateAdapter = true)
data class ServerSetupStatusDto(
    @Json(name = "server_id") val serverId: String = "",
    @Json(name = "mdns_host") val mdnsHost: String = "",
    @Json(name = "mqtt_port") val mqttPort: Int = 1883,
    val station: ServerSetupStationDto = ServerSetupStationDto(),
)

@JsonClass(generateAdapter = true)
data class ServerSetupStationDto(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val ip: String = "",
)

@JsonClass(generateAdapter = true)
data class ServerSetupScanWifiResponseDto(
    val networks: List<ServerSetupWifiNetworkDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ServerSetupWifiNetworkDto(
    val ssid: String = "",
    val rssi: Int = -100,
    val security: String = "secured",
)
