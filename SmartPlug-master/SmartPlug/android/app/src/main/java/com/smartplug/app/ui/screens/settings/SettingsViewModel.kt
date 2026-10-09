package com.smartplug.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.local.AppPreferences
import com.smartplug.app.data.local.AppSettings
import com.smartplug.app.data.local.AppThemeMode
import com.smartplug.app.data.local.TapSoundStyle
import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.data.local.db.HistoryDao
import com.smartplug.app.data.local.db.LoadSignatureDao
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.safeApiCall
import com.smartplug.app.domain.model.RegisteredServer
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceControlRepository
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.ui.localization.AppLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import javax.inject.Inject

data class AppResetUiState(
    val isRunning: Boolean = false,
    val summary: String? = null,
    /** A successful global reset invalidates any open device-detail route. */
    val completed: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val deviceRepository: DeviceRepository,
    private val deviceControlRepository: DeviceControlRepository,
    private val historyDao: HistoryDao,
    private val loadSignatureDao: LoadSignatureDao,
    private val secureTokenStore: SecureTokenStore,
    private val serverProfileStore: ServerProfileStore,
    private val apiClientFactory: ApiClientFactory,
    private val soundManager: com.smartplug.app.util.SoundManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appPreferences.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings(),
    )
    val registeredDevices: StateFlow<List<SmartPlugDevice>> = deviceRepository.observeDevices().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    private val _registeredServers = MutableStateFlow(serverProfileStore.all())
    val registeredServers: StateFlow<List<RegisteredServer>> = _registeredServers
    private val _appResetState = kotlinx.coroutines.flow.MutableStateFlow(AppResetUiState())
    val appResetState: StateFlow<AppResetUiState> = _appResetState

    fun setSoundEnabled(enabled: Boolean) = viewModelScope.launch { appPreferences.setSoundEnabled(enabled) }
    fun setKwhAdjustPercent(deviceId: String, percent: Double) =
        viewModelScope.launch { appPreferences.setKwhAdjustPercent(deviceId, percent) }

    /** Raw kWh straight from the device (read-only GET), for the adjustment preview. */
    suspend fun rawKwh(device: SmartPlugDevice): Double? =
        (deviceRepository.fetchMeasurement(device) as? ApiResult.Success)?.value?.energyWh?.div(1000.0)

    fun setDailySummaryEnabled(enabled: Boolean) = viewModelScope.launch { appPreferences.setDailySummaryEnabled(enabled) }
    fun setKwhDecimals(decimals: Int) = viewModelScope.launch { appPreferences.setKwhDecimals(decimals) }
    fun setCostEnabled(enabled: Boolean) = viewModelScope.launch { appPreferences.setCostEnabled(enabled) }
    fun setTariffPerKwh(tariff: Double) = viewModelScope.launch { appPreferences.setTariffPerKwh(tariff) }
    fun setSoundStyle(style: TapSoundStyle) = viewModelScope.launch {
        appPreferences.setSoundStyle(style)
        soundManager.preview(style)
    }
    fun setHapticEnabled(enabled: Boolean) = viewModelScope.launch { appPreferences.setHapticEnabled(enabled) }
    fun setThemeMode(mode: AppThemeMode) = viewModelScope.launch { appPreferences.setThemeMode(mode) }
    fun setLanguage(language: AppLanguage) = viewModelScope.launch { appPreferences.setLanguage(language) }

    /** Local unpair deliberately does not send reset/configuration requests to the physical unit. */
    fun unpairSmartPlug(device: SmartPlugDevice) = viewModelScope.launch {
        historyDao.clearForDevice(device.deviceId)
        loadSignatureDao.clearForDevice(device.deviceId)
        deviceRepository.removeDevice(device.deviceId)
    }

    /** Local unpair deliberately keeps the physical ServerSmartPlug and connected plugs untouched. */
    fun unpairServer(server: RegisteredServer) {
        serverProfileStore.remove(server.serverId)
        _registeredServers.value = serverProfileStore.all()
    }

    /**
     * SmartPlug and ServerSmartPlug are both reset before app credentials are cleared. If a
     * target is offline/fails, its profile remains locally so the user can retry instead of
     * silently losing control of a device that never accepted the reset.
     */
    fun resetAppAndRegisteredDevices() = viewModelScope.launch {
        if (_appResetState.value.isRunning) return@launch
        _appResetState.value = AppResetUiState(isRunning = true)
        val devices = deviceRepository.observeDevices().first()
        val servers = serverProfileStore.all()
        val failedNames = mutableListOf<String>()
        var resetCount = 0
        devices.forEach { device ->
            when (deviceControlRepository.factoryResetForGlobalReset(device)) {
                is ApiResult.Success -> {
                    historyDao.clearForDevice(device.deviceId)
                    loadSignatureDao.clearForDevice(device.deviceId)
                    deviceRepository.removeDevice(device.deviceId)
                    resetCount += 1
                }
                is ApiResult.Failure -> failedNames += device.displayName
            }
        }
        // A SERVER-mode SmartPlug receives the broker command above. Give its normal MQTT
        // delivery path time to execute before its broker is factory-reset as well.
        if (devices.any { it.integrationMode.name == "SERVER" }) delay(2_000)
        servers.forEach { server ->
            val token = serverProfileStore.apiToken(server.serverId)
            if (token == null) {
                failedNames += server.displayName
            } else {
                val result = safeApiCall {
                    apiClientFactory.serverApi(ApiClientFactory.hostBaseUrl(server.host))
                        .factoryResetServer("Bearer $token", factoryConfirmation())
                }
                if (result is ApiResult.Success) {
                    resetCount += 1
                } else {
                    failedNames += server.displayName
                }
            }
        }
        if (failedNames.isEmpty()) {
            secureTokenStore.clearAll()
            serverProfileStore.refresh()
            appPreferences.reset()
            _appResetState.value = AppResetUiState(
                summary = if (devices.isEmpty() && servers.isEmpty()) "Data aplikasi direset." else "$resetCount target SmartPlug/server di-factory-reset dan data aplikasi dihapus.",
                completed = true,
            )
        } else {
            _appResetState.value = AppResetUiState(
                summary = "$resetCount target direset. Masih tidak tersedia: ${failedNames.joinToString()}.",
            )
        }
    }

    private fun factoryConfirmation() = mapOf(
        "confirm_1" to "FACTORY_RESET",
        "confirm_2" to "FACTORY_RESET",
        "confirm_3" to "FACTORY_RESET",
    )
}
