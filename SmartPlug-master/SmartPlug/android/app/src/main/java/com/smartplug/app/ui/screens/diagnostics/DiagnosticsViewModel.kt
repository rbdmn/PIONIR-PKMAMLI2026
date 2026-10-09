package com.smartplug.app.ui.screens.diagnostics

import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.ViewModel
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.model.rssiToSignalPercent
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.util.ConnectionTest
import com.smartplug.app.util.DiagnosticsReport
import com.smartplug.app.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class DiagnosticsUiState(
    val devices: List<SmartPlugDevice> = emptyList(),
    val selectedId: String? = null,
    val running: Boolean = false,
    val test: ConnectionTest? = null,
    val status: DeviceStatus? = null,
    val phoneSignalPercent: Int? = null,
) {
    val selected: SmartPlugDevice? get() = devices.firstOrNull { it.deviceId == selectedId }
}

/** Read-only checks (status reads only); never sends a relay or settings command. */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        safeLaunch {
            val devices = deviceRepository.observeDevices().first()
            _uiState.value = _uiState.value.copy(devices = devices, selectedId = devices.firstOrNull()?.deviceId)
            runTest()
        }
    }

    fun select(deviceId: String) {
        _uiState.value = _uiState.value.copy(selectedId = deviceId, test = null, status = null)
        runTest()
    }

    fun runTest() {
        val device = _uiState.value.selected ?: return
        if (_uiState.value.running) return
        _uiState.value = _uiState.value.copy(running = true)
        safeLaunch(onError = {
            _uiState.value = _uiState.value.copy(
                running = false,
                test = ConnectionTest(false, 0L, "Terjadi kesalahan saat memeriksa"),
            )
        }) {
            val started = System.nanoTime()
            val result = deviceRepository.fetchStatus(device)
            val latencyMs = (System.nanoTime() - started) / 1_000_000L
            _uiState.value = _uiState.value.copy(
                running = false,
                phoneSignalPercent = phoneSignal(),
                test = when (result) {
                    is ApiResult.Success -> ConnectionTest(true, latencyMs)
                    is ApiResult.Failure -> ConnectionTest(false, latencyMs, DiagnosticsReport.plainReason(result.error.errorCode))
                },
                status = (result as? ApiResult.Success)?.value ?: _uiState.value.status,
            )
        }
    }

    fun report(): String {
        val state = _uiState.value
        val device = state.selected ?: return ""
        return DiagnosticsReport.build(device, state.test, state.status, state.phoneSignalPercent)
    }

    @Suppress("DEPRECATION")
    private fun phoneSignal(): Int? = runCatching {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        manager.connectionInfo?.rssi?.takeIf { it > -127 }?.let(::rssiToSignalPercent)
    }.getOrNull()
}
