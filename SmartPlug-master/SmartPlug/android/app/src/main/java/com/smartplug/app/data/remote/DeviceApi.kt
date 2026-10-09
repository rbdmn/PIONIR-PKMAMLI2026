package com.smartplug.app.data.remote

import com.smartplug.app.data.remote.dto.AllParametersResponseDto
import com.smartplug.app.data.remote.dto.DeviceStatusDto
import com.smartplug.app.data.remote.dto.DeviceScheduleDto
import com.smartplug.app.data.remote.dto.RelayCommandRequestDto
import com.smartplug.app.data.remote.dto.RelayCommandResponseDto
import com.smartplug.app.data.remote.dto.AccessEnrollRequestDto
import com.smartplug.app.data.remote.dto.AccessEnrollResponseDto
import com.smartplug.app.data.remote.dto.AccessInvitationRequestDto
import com.smartplug.app.data.remote.dto.AccessInvitationResponseDto
import com.smartplug.app.data.remote.dto.DeviceAccessProfileDto
import com.smartplug.app.data.remote.dto.AccessCredentialsResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * SmartPlug's operational REST API used once the device has an owner token (design.md
 * "REST operasional"). Bound to whatever `lan_ip` onboarding/mDNS discovery last resolved.
 */
interface DeviceApi {

    @GET("/api/v1/status")
    suspend fun getStatus(
        @Header("Authorization") bearerToken: String,
    ): Response<DeviceStatusDto>

    @GET("/api/v1/measurements/allparameters")
    suspend fun getAllParameters(
        @Header("Authorization") bearerToken: String,
    ): Response<AllParametersResponseDto>

    @POST("/api/v1/relay")
    suspend fun setRelay(
        @Header("Authorization") bearerToken: String,
        @Body request: RelayCommandRequestDto,
    ): Response<RelayCommandResponseDto>

    @FormUrlEncoded
    @POST("/api/v1/settings/mqtt")
    suspend fun setMqttSettings(
        @Header("Authorization") bearerToken: String,
        @FieldMap values: Map<String, String>,
    ): Response<Unit>

    @FormUrlEncoded
    @POST("/api/v1/settings/display-name")
    suspend fun setDisplayName(
        @Header("Authorization") bearerToken: String,
        @FieldMap values: Map<String, String>,
    ): Response<Unit>

    @FormUrlEncoded
    @POST("/api/v1/energy/reset")
    suspend fun resetEnergy(
        @Header("Authorization") bearerToken: String,
        @FieldMap confirmations: Map<String, String>,
    ): Response<Unit>

    @FormUrlEncoded
    @POST("/api/v1/factory-reset")
    suspend fun factoryReset(
        @Header("Authorization") bearerToken: String,
        @FieldMap confirmations: Map<String, String>,
    ): Response<Unit>

    @FormUrlEncoded
    @POST("/api/v1/timer")
    suspend fun setTimer(
        @Header("Authorization") bearerToken: String,
        @FieldMap values: Map<String, String>,
    ): Response<Unit>

    @GET("/api/v1/schedule")
    suspend fun getSchedule(
        @Header("Authorization") bearerToken: String,
    ): Response<DeviceScheduleDto>

    @FormUrlEncoded
    @POST("/api/v1/schedule")
    suspend fun setSchedule(
        @Header("Authorization") bearerToken: String,
        @FieldMap values: Map<String, String>,
    ): Response<DeviceScheduleDto>

    @GET("/api/v1/access/profile")
    suspend fun getAccessProfile(
        @Header("Authorization") bearerToken: String,
    ): Response<DeviceAccessProfileDto>

    /** PROPOSED (firmware task): not part of the current contract; older firmware answers 404. */
    @POST("/api/v1/power-policy")
    suspend fun setPowerPolicy(
        @Header("Authorization") bearerToken: String,
        @Body request: com.smartplug.app.data.remote.dto.PowerPolicyRequestDto,
    ): Response<Unit>

    /** PROPOSED (firmware task): overcurrent protection switch. */
    @POST("/api/v1/protection")
    suspend fun setProtection(
        @Header("Authorization") bearerToken: String,
        @Body request: com.smartplug.app.data.remote.dto.ProtectionRequestDto,
    ): Response<Unit>

    @POST("/api/v1/access/invitations")
    suspend fun createAccessInvitation(
        @Header("Authorization") bearerToken: String,
        @Body request: AccessInvitationRequestDto,
    ): Response<AccessInvitationResponseDto>

    /** Owner-only list.  The firmware returns credential IDs and roles only, never credentials. */
    @GET("/api/v1/access/credentials")
    suspend fun getAccessCredentials(
        @Header("Authorization") bearerToken: String,
    ): Response<AccessCredentialsResponseDto>

    /** Owner-only.  Only member credential IDs can be revoked by firmware. */
    @DELETE("/api/v1/access/credentials")
    suspend fun revokeAccessCredential(
        @Header("Authorization") bearerToken: String,
        @Query("credential_id") credentialId: String,
    ): Response<Unit>

    @POST("/api/v1/access/enroll")
    suspend fun enrollAccess(
        @Body request: AccessEnrollRequestDto,
    ): Response<AccessEnrollResponseDto>
}
