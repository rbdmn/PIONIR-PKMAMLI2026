package com.smartplug.app.ui.screens.home

import androidx.lifecycle.ViewModel
import com.smartplug.app.data.local.AppPreferences
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.HistoryRepository
import com.smartplug.app.util.DailySummary
import com.smartplug.app.util.DailySummaryCalc
import com.smartplug.app.util.applyEnergyAdjustment
import com.smartplug.app.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.Calendar
import javax.inject.Inject

data class DailySummaryUiState(
    val summary: DailySummary? = null,
    val deviceNames: Map<String, String> = emptyMap(),
)

/** Reads history that already exists (coarse resolution) at most once a minute; never polls raw data. */
@HiltViewModel
class DailySummaryViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val historyRepository: HistoryRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DailySummaryUiState())
    val uiState: StateFlow<DailySummaryUiState> = _uiState.asStateFlow()

    private var running = false
    private var lastRunMs = 0L

    fun refresh() {
        val now = System.currentTimeMillis()
        if (running || now - lastRunMs < MIN_INTERVAL_MS) return
        running = true
        lastRunMs = now
        safeLaunch(onError = { running = false }) {
            try {
                val devices = deviceRepository.observeDevices().first()
                val adjustments = appPreferences.settings.first().kwhAdjustments
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val from = todayStart - 86_400_000L
                val perDevice = LinkedHashMap<String, List<EnergyHistoryPoint>>()
                for (device in devices) {
                    val result = historyRepository.fetchHistory(device, from, now, HistoryResolution.FIVE_MINUTES)
                    if (result is ApiResult.Success) {
                        val percent = adjustments[device.deviceId] ?: 0.0
                        perDevice[device.deviceId] = if (percent == 0.0) result.value
                        else result.value.map { it.copy(energyWh = applyEnergyAdjustment(it.energyWh, percent)) }
                    }
                }
                _uiState.value = DailySummaryUiState(
                    summary = DailySummaryCalc.compute(perDevice, todayStart, now),
                    deviceNames = devices.associate { it.deviceId to it.displayName },
                )
            } finally {
                running = false
            }
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}
