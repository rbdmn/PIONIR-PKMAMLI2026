package com.smartplug.app.ui.screens.schedule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.DailyScheduleEntry
import com.smartplug.app.domain.model.DeviceSchedule
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceControlRepository
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ScheduleUiState(
    val device: SmartPlugDevice? = null,
    val schedule: DeviceSchedule? = null,
    val scheduleReceivedAtMs: Long = 0L,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val deviceControlRepository: DeviceControlRepository,
) : ViewModel() {
    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])
    private val _uiState = MutableStateFlow(ScheduleUiState(isLoading = true))
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        safeLaunch {
            val device = deviceRepository.getDevice(deviceId)
            _uiState.value = _uiState.value.copy(device = device)
            refresh()
        }
    }

    fun refresh() = execute { device -> deviceControlRepository.getSchedule(device) }
    fun setEnabled(enabled: Boolean, offsetMinutes: Int) = execute { device ->
        deviceControlRepository.setScheduleEnabled(device, enabled, offsetMinutes)
    }
    fun add(hour: Int, minute: Int, turnOn: Boolean, event: String, offsetMinutes: Int, onSuccess: () -> Unit) =
        execute(onSuccess = onSuccess) { device ->
            deviceControlRepository.addSchedule(device, hour, minute, turnOn, event, offsetMinutes)
        }
    fun delete(index: Int) = execute { device -> deviceControlRepository.deleteSchedule(device, index) }

    /**
     * The device/server schedule API has no in-place edit (only set_enabled/add/delete/move), so an
     * edit is delete + add. If the add is rejected the original entry is put back, so a failed edit
     * never silently loses the schedule.
     */
    fun edit(
        index: Int,
        original: DailyScheduleEntry,
        hour: Int,
        minute: Int,
        turnOn: Boolean,
        event: String,
        offsetMinutes: Int,
        onSuccess: () -> Unit,
    ) = execute(onSuccess = onSuccess) { device ->
        val deleted = deviceControlRepository.deleteSchedule(device, index)
        if (deleted is ApiResult.Failure) return@execute deleted
        val added = deviceControlRepository.addSchedule(device, hour, minute, turnOn, event, offsetMinutes)
        if (added is ApiResult.Failure) {
            deviceControlRepository.addSchedule(device, original.hour, original.minute, original.turnOn, original.event, offsetMinutes)
        }
        added
    }
    fun move(from: Int, to: Int) = execute { device -> deviceControlRepository.moveSchedule(device, from, to) }

    private fun execute(
        onSuccess: () -> Unit = {},
        action: suspend (SmartPlugDevice) -> ApiResult<DeviceSchedule>,
    ) {
        val device = _uiState.value.device ?: return
        safeLaunch(onError = { _uiState.value = _uiState.value.copy(isLoading = false, error = "Unexpected schedule error.") }) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = action(device)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(schedule = result.value, scheduleReceivedAtMs = System.currentTimeMillis(), isLoading = false)
                    onSuccess()
                }
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(isLoading = false, error = result.error.errorCode)
            }
        }
    }
}
