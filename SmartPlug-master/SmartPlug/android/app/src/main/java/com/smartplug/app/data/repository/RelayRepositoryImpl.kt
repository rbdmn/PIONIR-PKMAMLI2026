package com.smartplug.app.data.repository

import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.dto.RelayCommandRequestDto
import com.smartplug.app.data.remote.map
import com.smartplug.app.data.remote.safeApiCall
import com.smartplug.app.domain.model.ApiFailure
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.RelayCommandResult
import com.smartplug.app.domain.model.RelayCommandStatus
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.RelayRepository
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayRepositoryImpl @Inject constructor(
    private val tokenStore: SecureTokenStore,
    private val apiClientFactory: ApiClientFactory,
) : RelayRepository {

    override suspend fun setRelay(device: SmartPlugDevice, targetOn: Boolean): ApiResult<RelayCommandResult> {
        val targetState = if (targetOn) "on" else "off"
        return when (device.integrationMode) {
            IntegrationMode.DIRECT -> setRelayDirect(device, targetState)
            IntegrationMode.SERVER -> setRelayServer(device, targetState)
        }
    }

    override suspend fun awaitSettledState(
        device: SmartPlugDevice,
        commandId: String,
        targetState: RelayState,
    ): ApiResult<RelayCommandResult> = when (device.integrationMode) {
        IntegrationMode.DIRECT -> awaitDirectSettled(device, commandId, targetState)
        // A member learns Server mode from the safe profile but never receives another
        // phone's ServerSmartPlug API token.  Such a member can still operate the local
        // SmartPlug using its own device-scoped credential, including settle polling.
        IntegrationMode.SERVER -> if (hasServerToken(device)) awaitServerSettled(device, commandId)
        else awaitDirectSettled(device, commandId, targetState)
    }

    private suspend fun setRelayDirect(device: SmartPlugDevice, targetState: String): ApiResult<RelayCommandResult> {
        val lanIp = device.lanIp ?: return ApiResult.Failure(ApiFailure(0, "missing_lan_ip"))
        val bearer = tokenStore.ownerToken(device.deviceId)?.let { "Bearer $it" }
            ?: return ApiResult.Failure(ApiFailure(0, "missing_owner_token"))
        val api = apiClientFactory.deviceApi(ApiClientFactory.lanBaseUrl(lanIp))
        return safeApiCall { api.setRelay(bearer, RelayCommandRequestDto(targetState)) }.map { dto ->
            RelayCommandResult(
                commandId = dto.commandId,
                status = RelayCommandStatus.QUEUED,
                state = DeviceRepositoryImpl.parseRelayState(dto.state),
            )
        }
    }

    private suspend fun setRelayServer(device: SmartPlugDevice, targetState: String): ApiResult<RelayCommandResult> {
        if (!hasServerToken(device)) return setRelayDirect(device, targetState)
        val host = device.serverHost ?: return ApiResult.Failure(ApiFailure(0, "missing_server_host"))
        val bearer = device.serverId?.let(tokenStore::serverApiToken)?.let { "Bearer $it" }
            ?: return ApiResult.Failure(ApiFailure(0, "missing_server_token"))
        val api = apiClientFactory.serverApi(ApiClientFactory.hostBaseUrl(host, device.serverPort))
        return safeApiCall { api.setRelay(bearer, device.deviceId, mapOf("state" to targetState)) }.map { dto ->
            RelayCommandResult(
                commandId = dto.commandId,
                status = RelayCommandStatus.QUEUED,
                state = DeviceRepositoryImpl.parseRelayState(dto.state),
            )
        }
    }

    /**
     * SmartPlug's REST API doesn't expose a per-command status lookup (only `/api/v1/status`'s
     * running `relay_state`), so "settled" for DIRECT mode means the reported state caught up
     * with what we asked for. design.md: "Aplikasi menganggap command selesai hanya setelah state
     * baru itu diterima."
     */
    private suspend fun awaitDirectSettled(
        device: SmartPlugDevice,
        commandId: String,
        targetState: RelayState,
    ): ApiResult<RelayCommandResult> {
        val lanIp = device.lanIp ?: return ApiResult.Failure(ApiFailure(0, "missing_lan_ip"))
        val bearer = tokenStore.ownerToken(device.deviceId)?.let { "Bearer $it" }
            ?: return ApiResult.Failure(ApiFailure(0, "missing_owner_token"))
        val api = apiClientFactory.deviceApi(ApiClientFactory.lanBaseUrl(lanIp))

        var elapsedMs = 0L
        while (elapsedMs < DIRECT_SETTLE_TIMEOUT_MS) {
            val result = safeApiCall { api.getStatus(bearer) }
            if (result is ApiResult.Success) {
                if (result.value.relayCommandResult == "zero_cross_timeout") {
                    return ApiResult.Success(
                        RelayCommandResult(commandId, RelayCommandStatus.REJECTED, RelayState.UNKNOWN),
                    )
                }
                if (DeviceRepositoryImpl.parseRelayState(result.value.relayState) == targetState) {
                    return ApiResult.Success(RelayCommandResult(commandId, RelayCommandStatus.COMPLETED, targetState))
                }
            }
            delay(SETTLE_POLL_INTERVAL_MS)
            elapsedMs += SETTLE_POLL_INTERVAL_MS
        }
        return ApiResult.Success(RelayCommandResult(commandId, RelayCommandStatus.TIMEOUT, RelayState.UNKNOWN))
    }

    private suspend fun awaitServerSettled(
        device: SmartPlugDevice,
        commandId: String,
    ): ApiResult<RelayCommandResult> {
        val host = device.serverHost ?: return ApiResult.Failure(ApiFailure(0, "missing_server_host"))
        val bearer = device.serverId?.let(tokenStore::serverApiToken)?.let { "Bearer $it" }
            ?: return ApiResult.Failure(ApiFailure(0, "missing_server_token"))
        val api = apiClientFactory.serverApi(ApiClientFactory.hostBaseUrl(host, device.serverPort))

        var elapsedMs = 0L
        while (elapsedMs < SERVER_SETTLE_TIMEOUT_MS) {
            val result = safeApiCall { api.getCommandStatus(bearer, commandId) }
            if (result is ApiResult.Success) {
                val status = when (result.value.status) {
                    "completed" -> RelayCommandStatus.COMPLETED
                    "rejected" -> RelayCommandStatus.REJECTED
                    "timeout" -> RelayCommandStatus.TIMEOUT
                    else -> null
                }
                if (status != null) {
                    val state = result.value.state?.let { DeviceRepositoryImpl.parseRelayState(it) } ?: RelayState.UNKNOWN
                    return ApiResult.Success(RelayCommandResult(commandId, status, state))
                }
            }
            delay(SETTLE_POLL_INTERVAL_MS)
            elapsedMs += SETTLE_POLL_INTERVAL_MS
        }
        return ApiResult.Success(RelayCommandResult(commandId, RelayCommandStatus.TIMEOUT, RelayState.UNKNOWN))
    }

    private fun hasServerToken(device: SmartPlugDevice): Boolean =
        device.serverId?.let(tokenStore::serverApiToken) != null

    companion object {
        private const val SETTLE_POLL_INTERVAL_MS = 500L
        private const val DIRECT_SETTLE_TIMEOUT_MS = 5_000L
        // design.md: server marks a command `timeout` after 5s without an ack; poll slightly
        // past that so the terminal status has time to land before we give up client-side.
        private const val SERVER_SETTLE_TIMEOUT_MS = 7_000L
    }
}
