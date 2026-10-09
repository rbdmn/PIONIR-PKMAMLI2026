package com.smartplug.app.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.HistoryRepository
import com.smartplug.app.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class HistoryUiState(
    val device: SmartPlugDevice? = null,
    val resolution: HistoryResolution = HistoryResolution.ONE_HOUR,
    val range: HistoryRange = HistoryRange.LAST_DAY,
    val points: List<EnergyHistoryPoint> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** Windows the app lets the user pick, mapped to a resolution coarse enough that the request
 * never asks the server for raw per-500ms samples (design.md "aplikasi tidak memuat seluruh data
 * mentah untuk menggambar grafik"). */
enum class HistoryRange(val durationMs: Long, val resolution: HistoryResolution) {
    LAST_5_MIN(TimeUnit.MINUTES.toMillis(5), HistoryResolution.ONE_SECOND),
    LAST_15_MIN(TimeUnit.MINUTES.toMillis(15), HistoryResolution.ONE_SECOND),
    LAST_HOUR(TimeUnit.HOURS.toMillis(1), HistoryResolution.ONE_MINUTE),
    LAST_DAY(TimeUnit.DAYS.toMillis(1), HistoryResolution.FIVE_MINUTES),
    LAST_WEEK(TimeUnit.DAYS.toMillis(7), HistoryResolution.ONE_HOUR),
    LAST_MONTH(TimeUnit.DAYS.toMillis(30), HistoryResolution.ONE_DAY),
}

@HiltViewModel
class EnergyHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        safeLaunch {
            val device = deviceRepository.getDevice(deviceId)
            _uiState.value = _uiState.value.copy(device = device)
            loadRange(HistoryRange.LAST_DAY)
        }
    }

    fun loadRange(range: HistoryRange) {
        val device = _uiState.value.device ?: return
        safeLaunch(onError = {
            _uiState.value = _uiState.value.copy(isLoading = false, error = "Terjadi kesalahan tak terduga.")
        }) {
            _uiState.value = _uiState.value.copy(isLoading = true, resolution = range.resolution, range = range, error = null)
            val toMs = System.currentTimeMillis()
            val fromMs = toMs - range.durationMs
            when (val result = historyRepository.fetchHistory(device, fromMs, toMs, range.resolution)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(points = result.value, isLoading = false)
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error.message ?: "Riwayat tidak tersedia (${result.error.errorCode})",
                )
            }
        }
    }
}
