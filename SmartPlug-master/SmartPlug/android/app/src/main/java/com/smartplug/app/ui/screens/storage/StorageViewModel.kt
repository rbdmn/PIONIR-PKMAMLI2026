package com.smartplug.app.ui.screens.storage

import androidx.lifecycle.ViewModel
import com.smartplug.app.data.local.AppPreferences
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.SdUsage
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.HistoryRepository
import com.smartplug.app.domain.repository.StorageRepository
import com.smartplug.app.util.FillEstimate
import com.smartplug.app.util.MonthComparison
import com.smartplug.app.util.SdFillEstimator
import com.smartplug.app.util.SdObservation
import com.smartplug.app.util.StorageAnalysis
import com.smartplug.app.util.StorageAnalyzer
import com.smartplug.app.util.StorageComparison
import com.smartplug.app.util.StorageCsv
import com.smartplug.app.util.StorageDays
import com.smartplug.app.util.StorageResolution
import com.smartplug.app.util.applyEnergyAdjustment
import com.smartplug.app.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** "Semua" or a concrete local-time range. */
data class StoragePeriod(val isAll: Boolean, val fromMs: Long, val toMs: Long) {
    companion object {
        /** Before any SmartPlug existed; with 1d resolution this just means "everything stored". */
        const val ALL_FROM_MS = 1_577_836_800_000L // 2020-01-01T00:00:00Z

        fun all(nowMs: Long) = StoragePeriod(true, ALL_FROM_MS, nowMs)
    }
}

sealed interface StorageError {
    /** Server answered 503 history_unavailable: SD card not ready / write failure. */
    data object HistoryUnavailable : StorageError
    data class Other(val code: String, val message: String?) : StorageError
}

sealed interface ExportState {
    data object Idle : ExportState
    data object Building : ExportState
    data object Empty : ExportState
    data class Ready(val fileName: String, val csv: String) : ExportState
}

data class StorageUiState(
    val serverDevices: List<SmartPlugDevice> = emptyList(),
    val period: StoragePeriod? = null,
    val resolution: HistoryResolution? = null,
    val resolutionRaised: Boolean = false,
    val isLoading: Boolean = false,
    val error: StorageError? = null,
    /** False when the last status/history request could not reach the server. */
    val serverReachable: Boolean = true,
    val hasLoadedOnce: Boolean = false,
    val sd: SdUsage? = null,
    /** deviceId -> SD bytes used by that SmartPlug's history (merged across servers). */
    val deviceSdBytes: Map<String, Long> = emptyMap(),
    val deviceSdBytesComplete: Boolean = true,
    val nearlyFull: Boolean = false,
    val fill: FillEstimate = FillEstimate.NotEnoughData,
    val global: StorageAnalysis? = null,
    val perDevice: List<StorageAnalysis> = emptyList(),
    val deviceNames: Map<String, String> = emptyMap(),
    val globalComparison: MonthComparison? = null,
    val deviceComparison: Map<String, MonthComparison> = emptyMap(),
    val export: ExportState = ExportState.Idle,
    /** Error code of the last failed storage reset (null = none). */
    val resetError: String? = null,
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val historyRepository: HistoryRepository,
    private val storageRepository: StorageRepository,
    private val appPreferences: AppPreferences,
    private val serverProfileStore: ServerProfileStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    /** Display-adjusted points of the last successful load, kept for CSV export. */
    private var lastSeries: Map<String, List<EnergyHistoryPoint>> = emptyMap()
    private var lastRange: Pair<Long, Long> = 0L to 0L

    init {
        refresh()
    }

    fun selectAll() = load(StoragePeriod.all(System.currentTimeMillis()))

    /** [fromDayStartMs]..[toDayStartMs] are local day starts; the end day is included up to "now". */
    fun selectRange(fromDayStartMs: Long, toDayStartMs: Long) {
        val now = System.currentTimeMillis()
        val endOfDay = toDayStartMs + 86_400_000L - 1L
        load(StoragePeriod(false, fromDayStartMs, minOf(endOfDay, now).coerceAtLeast(fromDayStartMs)))
    }

    fun refresh() {
        val current = _uiState.value.period
        load(if (current == null || current.isAll) StoragePeriod.all(System.currentTimeMillis()) else current)
    }

    private fun load(period: StoragePeriod) {
        safeLaunch(onError = { e ->
            _uiState.update { it.copy(isLoading = false, error = StorageError.Other("unexpected_error", e.message)) }
        }) {
            val nowMs = System.currentTimeMillis()
            val knownServers = serverProfileStore.profiles.value.map { it.serverId }.toSet()
            val devices = deviceRepository.observeDevices().first()
                .filter { it.integrationMode == IntegrationMode.SERVER && it.serverId in knownServers }
            val choice = StorageResolution.choose(period.fromMs, period.toMs, nowMs)
            _uiState.update {
                it.copy(
                    serverDevices = devices, period = period, resolution = choice.resolution,
                    resolutionRaised = choice.raised, isLoading = true, error = null,
                )
            }
            if (devices.isEmpty()) {
                _uiState.update { it.copy(isLoading = false) }
                return@safeLaunch
            }

            val adjustments = appPreferences.settings.first().kwhAdjustments
            fun adjusted(deviceId: String, pts: List<EnergyHistoryPoint>): List<EnergyHistoryPoint> {
                val percent = adjustments[deviceId] ?: 0.0
                return if (percent == 0.0) pts else pts.map { it.copy(energyWh = applyEnergyAdjustment(it.energyWh, percent)) }
            }

            var reachable = true
            var error: StorageError? = null
            val series = LinkedHashMap<String, List<EnergyHistoryPoint>>()
            for (device in devices) {
                when (val result = historyRepository.fetchHistory(device, period.fromMs, period.toMs, choice.resolution)) {
                    is ApiResult.Success -> series[device.deviceId] = adjusted(device.deviceId, result.value)
                    is ApiResult.Failure -> {
                        val code = result.error.errorCode
                        if (code == "network_timeout" || code == "no_connectivity") reachable = false
                        if (error == null) {
                            error = if (result.error.httpCode == 503 && code == "history_unavailable") StorageError.HistoryUnavailable
                            else StorageError.Other(code, result.error.message)
                        }
                    }
                }
            }

            val sd = loadSd(devices)
            if (sd.first != null) reachable = true else if (sd.second) reachable = false

            if (series.isEmpty()) {
                // Keep whatever loaded last time; just report why this refresh failed.
                _uiState.update {
                    it.copy(
                        isLoading = false, error = error, serverReachable = reachable, hasLoadedOnce = true,
                        sd = sd.first ?: it.sd,
                    )
                }
                return@safeLaunch
            }

            // Month comparison needs last month + this month at day resolution (always within retention).
            val comparison = loadComparison(devices, nowMs, adjustments)

            val perDevice = devices.filter { it.deviceId in series }.map { d ->
                StorageAnalyzer.analyze(d.deviceId, series.getValue(d.deviceId), period.fromMs, period.toMs, trimLeadingGaps = period.isAll)
            }
            val global = StorageAnalyzer.combine(perDevice, perDevice.map { series.getValue(it.deviceId!!) })
            lastSeries = series
            lastRange = period.fromMs to period.toMs
            val usage = sd.first
            val observations = appPreferences.sdObservations.first()
            val fill = SdFillEstimator.estimate(observations, usage?.usedBytes, usage?.totalBytes)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = error,
                    serverReachable = reachable,
                    hasLoadedOnce = true,
                    sd = usage ?: it.sd,
                    deviceSdBytes = usage?.deviceBytes ?: it.deviceSdBytes,
                    deviceSdBytesComplete = usage?.deviceBytesComplete ?: it.deviceSdBytesComplete,
                    nearlyFull = SdFillEstimator.isNearlyFull(usage?.usedBytes, usage?.totalBytes),
                    fill = fill,
                    global = global,
                    perDevice = perDevice,
                    deviceNames = devices.associate { d -> d.deviceId to d.displayName },
                    globalComparison = comparison?.first,
                    deviceComparison = comparison?.second.orEmpty(),
                )
            }
        }
    }

    /** Returns (usage or null, whether the server was unreachable). Records a sample when complete. */
    private suspend fun loadSd(devices: List<SmartPlugDevice>): Pair<SdUsage?, Boolean> {
        var used = 0L
        var total = 0L
        var any = false
        val deviceBytes = HashMap<String, Long>()
        var complete = true
        var unreachable = false
        // One request per distinct server, not per SmartPlug.
        for (device in devices.distinctBy { it.serverId to it.serverHost }) {
            when (val result = storageRepository.fetchSdUsage(device)) {
                is ApiResult.Success -> {
                    val u = result.value.usedBytes
                    val t = result.value.totalBytes
                    if (u != null && t != null && t > 0) { used += u; total += t; any = true }
                    deviceBytes.putAll(result.value.deviceBytes)
                    if (!result.value.deviceBytesComplete) complete = false
                }
                is ApiResult.Failure -> if (result.error.errorCode == "network_timeout" || result.error.errorCode == "no_connectivity") unreachable = true
            }
        }
        if (!any) return null to unreachable
        appPreferences.recordSdObservation(SdObservation(System.currentTimeMillis(), used, total))
        return SdUsage(used, total, deviceBytes, complete) to unreachable
    }

    private suspend fun loadComparison(
        devices: List<SmartPlugDevice>,
        nowMs: Long,
        adjustments: Map<String, Double>,
    ): Pair<MonthComparison, Map<String, MonthComparison>>? {
        val from = StorageDays.startOfMonth(nowMs, 1)
        val perDevice = LinkedHashMap<String, MonthComparison>()
        var thisKwh = 0.0
        var lastKwh = 0.0
        for (device in devices) {
            val result = historyRepository.fetchHistory(device, from, nowMs, HistoryResolution.ONE_DAY)
            if (result !is ApiResult.Success) continue
            val percent = adjustments[device.deviceId] ?: 0.0
            val pts = if (percent == 0.0) result.value else result.value.map { it.copy(energyWh = applyEnergyAdjustment(it.energyWh, percent)) }
            val cmp = StorageComparison.compare(pts, nowMs)
            perDevice[device.deviceId] = cmp
            thisKwh += cmp.thisMonthKwh
            lastKwh += cmp.lastMonthKwh
        }
        return if (perDevice.isEmpty()) null else MonthComparison(thisKwh, lastKwh) to perDevice
    }

    /** Builds the CSV for the loaded period: all devices ([deviceId] null) or one. */
    fun requestExport(deviceId: String?) {
        if (_uiState.value.export == ExportState.Building) return
        _uiState.update { it.copy(export = ExportState.Building) }
        safeLaunch(onError = { _uiState.update { s -> s.copy(export = ExportState.Empty) } }) {
            val chosen = if (deviceId == null) lastSeries else lastSeries.filterKeys { it == deviceId }
            if (!StorageCsv.hasData(chosen)) {
                _uiState.update { it.copy(export = ExportState.Empty) }
                return@safeLaunch
            }
            val csv = StorageCsv.build(_uiState.value.deviceNames, chosen)
            val firstRecord = chosen.values.flatten().minOf { it.timestampUtcMs }
            val name = StorageCsv.fileName(deviceId, lastRange.first.coerceAtLeast(firstRecord), lastRange.second)
            _uiState.update { it.copy(export = ExportState.Ready(name, csv)) }
        }
    }

    /**
     * Deletes the history on every distinct server (PROPOSED endpoint) and, only if that worked,
     * the on-phone cache and SD usage samples. A failed server call leaves everything untouched so
     * the user can retry; [StorageUiState.resetError] says why.
     */
    fun resetStorage() {
        _uiState.update { it.copy(resetError = null, isLoading = true) }
        safeLaunch(onError = { e -> _uiState.update { it.copy(isLoading = false, resetError = e.message ?: "unexpected_error") } }) {
            val devices = _uiState.value.serverDevices
            for (device in devices.distinctBy { it.serverId to it.serverHost }) {
                val result = storageRepository.resetServerHistory(device)
                if (result is ApiResult.Failure) {
                    val code = if (result.error.httpCode == 404) "server_unsupported" else result.error.errorCode
                    _uiState.update { it.copy(isLoading = false, resetError = code) }
                    return@safeLaunch
                }
            }
            devices.forEach { historyRepository.clearCachedHistory(it.deviceId) }
            appPreferences.clearSdObservations()
            lastSeries = emptyMap()
            _uiState.update {
                it.copy(
                    global = null, perDevice = emptyList(), globalComparison = null, deviceComparison = emptyMap(),
                    fill = FillEstimate.NotEnoughData, error = null, hasLoadedOnce = false, resetError = null,
                )
            }
            refresh()
        }
    }

    fun dismissResetError() = _uiState.update { it.copy(resetError = null) }

    fun consumeExport() = _uiState.update { it.copy(export = ExportState.Idle) }
}
