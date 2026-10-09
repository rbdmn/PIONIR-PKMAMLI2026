package com.smartplug.app.data.repository

import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.local.db.HistoryDao
import com.smartplug.app.data.local.db.HistoryPointEntity
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.map
import com.smartplug.app.data.remote.safeApiCall
import com.smartplug.app.domain.model.ApiFailure
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.HistoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Server-mode only (design.md: "Halaman riwayat hanya memakai endpoint server"); a device on
 * DIRECT/REST-only mode has no history endpoint. Caches successful reads in Room so the chart
 * renders instantly on re-open and survives a brief connectivity gap. */
@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val historyDao: HistoryDao,
    private val tokenStore: SecureTokenStore,
    private val apiClientFactory: ApiClientFactory,
) : HistoryRepository {

    override suspend fun fetchHistory(
        device: SmartPlugDevice,
        fromUtcMs: Long,
        toUtcMs: Long,
        resolution: HistoryResolution,
    ): ApiResult<List<EnergyHistoryPoint>> {
        if (device.integrationMode == IntegrationMode.DIRECT) {
            return ApiResult.Success(
                historyDao.getRange(device.deviceId, LOCAL_HISTORY_RESOLUTION, fromUtcMs, toUtcMs)
                    .map { it.toDomain() },
            )
        }
        val host = device.serverHost
            ?: return cachedOrFailure(device, fromUtcMs, toUtcMs, resolution, ApiFailure(0, "missing_server_host"))
        val bearer = device.serverId?.let(tokenStore::serverApiToken)?.let { "Bearer $it" }
            ?: return cachedOrFailure(device, fromUtcMs, toUtcMs, resolution, ApiFailure(0, "missing_server_token"))

        val api = apiClientFactory.serverApi(ApiClientFactory.hostBaseUrl(host, device.serverPort))
        val result = safeApiCall {
            // ServerSmartPlug parses these query values with strtoul(), i.e. Unix seconds.
            // An ISO-8601 value made `to` parse as e.g. 2026 seconds after epoch, filtering
            // out all present-day records.
            api.getHistory(bearer, device.deviceId, epochSeconds(fromUtcMs), epochSeconds(toUtcMs), resolution.wireValue)
        }.map { dto ->
            dto.points.map { point ->
                EnergyHistoryPoint(
                    timestampUtcMs = point.timestampUtcMs,
                    voltageV = point.voltageV,
                    currentA = point.currentA,
                    activePowerW = point.activePowerW,
                    apparentPowerVa = point.apparentPowerVa,
                    powerFactor = point.powerFactor,
                    energyWh = point.energyWh,
                )
            }
        }

        return when (result) {
            is ApiResult.Success -> {
                historyDao.upsertAll(result.value.map { it.toEntity(device.deviceId, resolution) })
                result
            }
            is ApiResult.Failure -> cachedOrFailure(device, fromUtcMs, toUtcMs, resolution, result.error)
        }
    }

    override suspend fun clearCachedHistory(deviceId: String) {
        historyDao.clearForDevice(deviceId)
    }

    private suspend fun cachedOrFailure(
        device: SmartPlugDevice,
        fromUtcMs: Long,
        toUtcMs: Long,
        resolution: HistoryResolution,
        failure: ApiFailure,
    ): ApiResult<List<EnergyHistoryPoint>> {
        val cached = historyDao.getRange(device.deviceId, resolution.wireValue, fromUtcMs, toUtcMs)
        return if (cached.isNotEmpty()) {
            ApiResult.Success(cached.map { it.toDomain() })
        } else {
            ApiResult.Failure(failure)
        }
    }

    private fun epochSeconds(epochMs: Long): String = (epochMs / 1_000L).toString()

    private companion object {
        const val LOCAL_HISTORY_RESOLUTION = "local_1m"
    }
}

private fun HistoryPointEntity.toDomain() = EnergyHistoryPoint(
    timestampUtcMs = timestampUtcMs,
    voltageV = voltageV,
    currentA = currentA,
    activePowerW = activePowerW,
    apparentPowerVa = apparentPowerVa,
    powerFactor = powerFactor,
    energyWh = energyWh,
)

private fun EnergyHistoryPoint.toEntity(deviceId: String, resolution: HistoryResolution) = HistoryPointEntity(
    deviceId = deviceId,
    resolution = resolution.wireValue,
    timestampUtcMs = timestampUtcMs,
    voltageV = voltageV,
    currentA = currentA,
    activePowerW = activePowerW,
    apparentPowerVa = apparentPowerVa,
    powerFactor = powerFactor,
    energyWh = energyWh,
)
