package com.smartplug.app.data.remote

import com.smartplug.app.data.remote.dto.ServerCommandStatusDto
import com.smartplug.app.data.remote.dto.ServerDeviceDto
import com.smartplug.app.data.remote.dto.ServerEnergyDto
import com.smartplug.app.data.remote.dto.ServerHistoryResponseDto
import com.smartplug.app.data.remote.dto.ServerLatestDto
import com.smartplug.app.data.remote.dto.ServerRelayResponseDto
import com.smartplug.app.data.remote.dto.ServerStatusDto
import com.smartplug.app.data.remote.dto.DeviceScheduleDto
import com.smartplug.app.data.remote.dto.MqttAuthDeviceRequestDto
import com.smartplug.app.data.remote.dto.ServerTimerRequestDto
import com.smartplug.app.data.remote.dto.ServerScheduleRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * ServerSmartPlug's application REST API (design.md "REST API aplikasi ke server"). Bound to the
 * discovered `srvrplug-<server_sta_mac>.local` host. All calls carry the app's API token.
 */
interface ServerApi {

    /**
     * Registers (or replaces) the per-device SPMQTT2 HMAC secret before the
     * SmartPlug is switched to MQTT mode.  The server never returns it.
     */
    @POST("/api/v1/mqtt-auth/devices/{device_id}")
    suspend fun registerMqttAuthDevice(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
        @Body request: MqttAuthDeviceRequestDto,
    ): Response<Unit>

    /** Compensating action when local SmartPlug provisioning does not succeed. */
    @DELETE("/api/v1/mqtt-auth/devices/{device_id}")
    suspend fun deleteMqttAuthDevice(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
    ): Response<Unit>

    @POST("/api/v1/factory-reset")
    suspend fun factoryResetServer(
        @Header("Authorization") bearerToken: String,
        @Body confirmations: Map<String, String>,
    ): Response<Unit>

    @GET("/api/v1/status")
    suspend fun getServerStatus(
        @Header("Authorization") bearerToken: String,
    ): Response<Unit>

    /** Same endpoint as [getServerStatus], read for the optional proposed SD "storage" object. */
    @GET("/api/v1/status")
    suspend fun getServerStatusDetail(
        @Header("Authorization") bearerToken: String,
        /** PROPOSED: ?storage=1 asks the server to include SD capacity (it is slow to compute otherwise). */
        @Query("storage") storage: Int,
    ): Response<ServerStatusDto>

    /**
     * PROPOSED endpoint (server R3.8.21, not yet a design.md contract): deletes the server's
     * measurement history file to free SD space. Body carries three "RESET_HISTORY" confirmations.
     */
    @POST("/api/v1/history/reset")
    suspend fun resetHistory(
        @Header("Authorization") bearerToken: String,
        @Body confirmations: Map<String, String>,
    ): Response<Unit>

    @GET("/api/v1/devices")
    suspend fun listDevices(
        @Header("Authorization") bearerToken: String,
    ): Response<List<ServerDeviceDto>>

    @GET("/api/v1/devices/{device_id}/latest")
    suspend fun getLatest(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
    ): Response<ServerLatestDto>

    @GET("/api/v1/devices/{device_id}/energy")
    suspend fun getEnergy(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
    ): Response<ServerEnergyDto>

    @GET("/api/v1/devices/{device_id}/history")
    suspend fun getHistory(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
        @Query("from") fromUtc: String,
        @Query("to") toUtc: String,
        @Query("resolution") resolution: String,
    ): Response<ServerHistoryResponseDto>

    @POST("/api/v1/devices/{device_id}/relay")
    suspend fun setRelay(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
        @Body request: Map<String, String>,
    ): Response<ServerRelayResponseDto>

    @POST("/api/v1/devices/{device_id}/energy/reset")
    suspend fun resetEnergy(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
        @Body confirmations: Map<String, String>,
    ): Response<Unit>

    @POST("/api/v1/devices/{device_id}/factory-reset")
    suspend fun factoryReset(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
        @Body confirmations: Map<String, String>,
    ): Response<Unit>

    @POST("/api/v1/devices/{device_id}/timer")
    suspend fun setTimer(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
        @Body values: ServerTimerRequestDto,
    ): Response<Unit>

    /**
     * Clears server-owned timer/schedule state before a device is attached to
     * this server or detached back to Direct mode.  This is intentionally not
     * a relay command and never changes the current relay state.
     */
    @POST("/api/v1/devices/{device_id}/automation/reset")
    suspend fun resetAutomation(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
    ): Response<Unit>

    @GET("/api/v1/devices/{device_id}/schedule")
    suspend fun getSchedule(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
    ): Response<DeviceScheduleDto>

    @POST("/api/v1/devices/{device_id}/schedule")
    suspend fun setSchedule(
        @Header("Authorization") bearerToken: String,
        @Path("device_id") deviceId: String,
        @Body values: ServerScheduleRequestDto,
    ): Response<DeviceScheduleDto>

    @GET("/api/v1/commands/{command_id}")
    suspend fun getCommandStatus(
        @Header("Authorization") bearerToken: String,
        @Path("command_id") commandId: String,
    ): Response<ServerCommandStatusDto>
}
