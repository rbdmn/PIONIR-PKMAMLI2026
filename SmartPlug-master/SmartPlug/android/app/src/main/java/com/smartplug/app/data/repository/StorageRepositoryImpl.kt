package com.smartplug.app.data.repository

import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.map
import com.smartplug.app.data.remote.safeApiCall
import com.smartplug.app.domain.model.ApiFailure
import com.smartplug.app.domain.model.SdUsage
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.StorageRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepositoryImpl @Inject constructor(
    private val tokenStore: SecureTokenStore,
    private val apiClientFactory: ApiClientFactory,
) : StorageRepository {

    override suspend fun fetchSdUsage(device: SmartPlugDevice): ApiResult<SdUsage> {
        val host = device.serverHost ?: return ApiResult.Failure(ApiFailure(0, "missing_server_host"))
        val bearer = device.serverId?.let(tokenStore::serverApiToken)?.let { "Bearer $it" }
            ?: return ApiResult.Failure(ApiFailure(0, "missing_server_token"))
        val api = apiClientFactory.serverApi(ApiClientFactory.hostBaseUrl(host, device.serverPort))
        return safeApiCall { api.getServerStatusDetail(bearer, 1) }.map { dto ->
            SdUsage(
                usedBytes = dto.storage?.sdUsedBytes,
                totalBytes = dto.storage?.sdTotalBytes,
                deviceBytes = dto.storage?.historyByDevice.orEmpty()
                    .mapNotNull { item -> item.bytes?.let { item.deviceId to it } }.toMap(),
                deviceBytesComplete = dto.storage?.historyScanComplete ?: true,
            )
        }
    }

    override suspend fun resetServerHistory(device: SmartPlugDevice): ApiResult<Unit> {
        val host = device.serverHost ?: return ApiResult.Failure(ApiFailure(0, "missing_server_host"))
        val bearer = device.serverId?.let(tokenStore::serverApiToken)?.let { "Bearer $it" }
            ?: return ApiResult.Failure(ApiFailure(0, "missing_server_token"))
        val api = apiClientFactory.serverApi(ApiClientFactory.hostBaseUrl(host, device.serverPort))
        val confirmations = mapOf("confirm_1" to "RESET_HISTORY", "confirm_2" to "RESET_HISTORY", "confirm_3" to "RESET_HISTORY")
        return safeApiCall { api.resetHistory(bearer, confirmations) }
    }
}
