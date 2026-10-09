package com.smartplug.app.data.repository

import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.dto.PairConfigureRequestDto
import com.smartplug.app.data.remote.map
import com.smartplug.app.data.remote.safeApiCall
import com.smartplug.app.domain.model.HomeWifiNetwork
import com.smartplug.app.domain.model.PairInfo
import com.smartplug.app.domain.model.PairingFailureReason
import com.smartplug.app.domain.model.PairingState
import com.smartplug.app.domain.model.PairingStatus
import com.smartplug.app.domain.repository.PairingRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val apiClientFactory: ApiClientFactory,
) : PairingRepository {

    private val api get() = apiClientFactory.pairingApi()

    override suspend fun fetchPairInfo(): ApiResult<PairInfo> =
        safeApiCall { api.getPairInfo() }.map { dto ->
            PairInfo(
                apiVersion = dto.apiVersion,
                product = dto.product,
                protocol = dto.protocol,
                deviceId = dto.deviceId,
                staMac = dto.staMac,
                state = dto.state,
                pairingToken = dto.pairingToken,
                tokenExpiresInS = dto.tokenExpiresInS,
            )
        }

    override suspend fun scanHomeWifi(pairingToken: String): ApiResult<List<HomeWifiNetwork>> =
        safeApiCall { apiClientFactory.pairingScanApi().scanWifi(pairingToken) }.map { dto ->
            dto.networks.map { HomeWifiNetwork(it.ssid, it.rssi, it.security) }
        }

    override suspend fun configureDirect(
        pairingToken: String,
        ssid: String,
        password: String,
    ): ApiResult<String> {
        val request = PairConfigureRequestDto(
            ssid = ssid,
            password = password,
            connectionProfile = mapOf("type" to "direct"),
        )
        return safeApiCall { api.configure(pairingToken, request) }.map { it.configurationId }
    }

    override fun pollStatus(pairingToken: String, configurationId: String): Flow<ApiResult<PairingStatus>> = flow {
        while (true) {
            val mapped = fetchStatus(api, pairingToken, configurationId)
            emit(mapped)

            val terminal = when (mapped) {
                is ApiResult.Success -> mapped.value.state == PairingState.CONNECTED ||
                    mapped.value.state == PairingState.FAILED
                is ApiResult.Failure -> true
            }
            if (terminal) break

            // design.md: "Polling dijalankan berurutan, satu request aktif, tiap satu detik".
            delay(1_000)
        }
    }

    override suspend fun fetchStatusAt(
        baseUrl: String,
        pairingToken: String,
        configurationId: String,
    ): ApiResult<PairingStatus> =
        fetchStatus(apiClientFactory.pairingApi(baseUrl), pairingToken, configurationId)

    private suspend fun fetchStatus(
        targetApi: com.smartplug.app.data.remote.PairingApi,
        pairingToken: String,
        configurationId: String,
    ): ApiResult<PairingStatus> =
        safeApiCall { targetApi.getStatus(pairingToken, configurationId) }.map { dto ->
            PairingStatus(
                state = parseState(dto.state),
                deviceId = dto.deviceId,
                staMac = dto.staMac,
                lanIp = dto.lanIp,
                ownerToken = dto.ownerToken,
                ssid = dto.ssid,
                failureReason = dto.reason?.let(PairingFailureReason::from),
            )
        }

    private fun parseState(wireState: String): PairingState = when (wireState) {
        "unprovisioned" -> PairingState.UNPROVISIONED
        "pairing" -> PairingState.PAIRING
        "connecting" -> PairingState.CONNECTING
        "connected" -> PairingState.CONNECTED
        "failed" -> PairingState.FAILED
        else -> PairingState.FAILED
    }
}
