package com.smartplug.app.data.remote

import com.smartplug.app.data.remote.dto.PairConfigureRequestDto
import com.smartplug.app.data.remote.dto.PairConfigureResponseDto
import com.smartplug.app.data.remote.dto.PairInfoDto
import com.smartplug.app.data.remote.dto.PairScanWifiResponseDto
import com.smartplug.app.data.remote.dto.PairStatusResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * SmartPlug pairing endpoints, reachable only while the phone is bound to the SmartPlug AP
 * (`192.168.4.1`). See design.md "Pairing API". Every call after [getPairInfo] must carry the
 * `pairing_token` it returned.
 */
interface PairingApi {

    @GET("/api/v1/pair/info")
    suspend fun getPairInfo(): Response<PairInfoDto>

    @POST("/api/v1/pair/scan-wifi")
    suspend fun scanWifi(
        @Header("X-Pairing-Token") pairingToken: String,
    ): Response<PairScanWifiResponseDto>

    @POST("/api/v1/pair/configure")
    suspend fun configure(
        @Header("X-Pairing-Token") pairingToken: String,
        @Body request: PairConfigureRequestDto,
    ): Response<PairConfigureResponseDto>

    @GET("/api/v1/pair/status")
    suspend fun getStatus(
        @Header("X-Pairing-Token") pairingToken: String,
        @Query("configuration_id") configurationId: String,
    ): Response<PairStatusResponseDto>
}
