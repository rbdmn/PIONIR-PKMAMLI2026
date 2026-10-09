package com.smartplug.app.ui.screens.addserver

import androidx.lifecycle.ViewModel
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.safeApiCall
import com.smartplug.app.domain.model.DiscoveredServerSetupAp
import com.smartplug.app.domain.model.HomeWifiNetwork
import com.smartplug.app.domain.model.RegisteredServer
import com.smartplug.app.domain.repository.WifiOnboardingRepository
import com.smartplug.app.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.inject.Inject

enum class ServerOnboardingStep { SCANNING_SERVER, SCANNING_HOME_WIFI, CHOOSING_HOME_WIFI, CONFIGURING, WAITING_FOR_CONNECTION, SUCCESS, FAILED }

data class AddServerUiState(
    val step: ServerOnboardingStep = ServerOnboardingStep.SCANNING_SERVER,
    val permissionMissing: Boolean = false,
    val locationServiceDisabled: Boolean = false,
    val discoveredSetupAps: List<DiscoveredServerSetupAp> = emptyList(),
    val selectedSetupSsid: String? = null,
    val homeWifiNetworks: List<HomeWifiNetwork> = emptyList(),
    val selectedSsid: String? = null,
    val homeWifiPassword: String = "",
    val homeWifiPasswordVisible: Boolean = false,
    val serverId: String? = null,
    val mqttPort: Int = 1883,
    val busy: Boolean = false,
    val completed: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val wifi: WifiOnboardingRepository,
    private val clients: ApiClientFactory,
    private val serverProfiles: ServerProfileStore,
    private val tokenStore: SecureTokenStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddServerUiState())
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()

    // Security is generated and stored by the app, never exposed as user input.
    private val serverApiToken = randomHex(24)
    private val mqttUsername = "sp_" + randomHex(6)
    private val mqttPassword = randomHex(24)
    // Captured before Android binds this process to the server's no-internet setup AP. Android
    // 16 may otherwise redact/replace the home-network cache while that binding is active.
    private var homeWifiSeenBeforeBinding: List<HomeWifiNetwork> = emptyList()

    init {
        refreshPrerequisites()
        if (!_uiState.value.permissionMissing && !_uiState.value.locationServiceDisabled) startServerSearch()
    }

    fun refreshPrerequisites() {
        _uiState.value = _uiState.value.copy(
            permissionMissing = !wifi.hasRequiredPermission(),
            locationServiceDisabled = wifi.isLocationServiceRequiredAndDisabled(),
        )
    }

    fun startServerSearch() {
        if (!wifi.hasRequiredPermission() || wifi.isLocationServiceRequiredAndDisabled()) {
            refreshPrerequisites()
            return
        }
        safeLaunch(onError = { error ->
            _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Pencarian server gagal: ${error.message ?: "wifi_scan_error"}")
        }) {
            _uiState.value = AddServerUiState(step = ServerOnboardingStep.SCANNING_SERVER, busy = true, message = "Mencari Server yang menyala…")
            val result = wifi.scanForServerSetupAps()
            _uiState.value = if (result.isSuccess) {
                val servers = result.getOrDefault(emptyList())
                _uiState.value.copy(busy = false, discoveredSetupAps = servers, message = if (servers.isEmpty()) "Server belum ditemukan. Pastikan server menyala dan mode setup aktif." else "Pilih Server yang ditemukan.")
            } else {
                _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Pencarian server gagal: ${result.exceptionOrNull()?.message ?: "wifi_scan_error"}")
            }
        }
    }

    fun selectSetupServer(ssid: String) {
        _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.SCANNING_HOME_WIFI, selectedSetupSsid = ssid, busy = true, message = "Menghubungkan ke $ssid…")
        safeLaunch(onError = { error ->
            wifi.releaseApBinding()
            _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Server tidak dapat diverifikasi: ${error.message ?: "network_error"}")
        }) {
            // Read the phone's normal home-Wi-Fi view *before* requestNetwork() binds the
            // process to the setup AP. This is more reliable than a server RF scan, which can
            // temporarily interrupt the ESP32 access point carrying this HTTP session.
            homeWifiSeenBeforeBinding = wifi.scanNearbyHomeWifi().getOrDefault(emptyList())
            if (wifi.connectToAp(ssid, SETUP_PASSWORD).isFailure) {
                // This is a ServerSmartPlug-only retry. Explicitly retire its failed request so
                // it cannot remain registered when the user goes back to direct SmartPlug setup.
                wifi.releaseApBinding()
                _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Tidak dapat terhubung ke $ssid. Pastikan server masih mode setup.")
                return@safeLaunch
            }
            val setup = (safeApiCall { clients.serverSetupApi().status() } as? ApiResult.Success)?.value
            if (setup == null || setup.serverId.isBlank()) {
                wifi.releaseApBinding()
                _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Perangkat yang dipilih bukan ServerSmartPlug yang didukung.")
                return@safeLaunch
            }
            _uiState.value = _uiState.value.copy(serverId = setup.serverId, mqttPort = setup.mqttPort, message = "Server ditemukan. Memindai Wi-Fi rumah dari server…")
            scanHomeWifi()
        }
    }

    private fun scanHomeWifi() {
        safeLaunch(onError = { error ->
            _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Pemindaian Wi-Fi rumah gagal: ${error.message ?: "wifi_scan_error"}")
        }) {
            // Android can return a restricted/incomplete scan cache while this process is bound
            // to a WifiNetworkSpecifier AP. Merge that current view with the full Wi-Fi list
            // captured before the binding rather than disturbing the ESP32 AP with an RF scan.
            val setupSsid = _uiState.value.selectedSetupSsid
            val result = wifi.scanNearbyHomeWifi()
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    step = ServerOnboardingStep.FAILED,
                    busy = false,
                    message = "Pemindaian Wi-Fi rumah gagal: ${result.exceptionOrNull()?.message ?: "wifi_scan_error"}",
                )
                return@safeLaunch
            }
            val phoneNetworks = result.getOrDefault(emptyList())
            val networks = (homeWifiSeenBeforeBinding + phoneNetworks)
                .filter { it.ssid.isNotBlank() }
                .groupBy { it.ssid }
                .map { (_, sameSsid) -> sameSsid.maxBy { it.rssi } }
                .filter {
                it.ssid != setupSsid && !it.ssid.startsWith("SP-")
                }
                .sortedByDescending { it.rssi }
            _uiState.value = _uiState.value.copy(
                step = ServerOnboardingStep.CHOOSING_HOME_WIFI,
                busy = false,
                homeWifiNetworks = networks,
                message = if (networks.isEmpty()) "Wi-Fi rumah tidak ditemukan di sekitar server." else "Pilih Wi-Fi rumah.",
            )
        }
    }

    fun retryHomeWifiScan() {
        _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.SCANNING_HOME_WIFI, busy = true)
        scanHomeWifi()
    }

    /**
     * Match the proven direct-SmartPlug flow: when the user already provisioned a
     * SmartPlug on this SSID, reuse the encrypted local Wi-Fi credential rather
     * than making them type it again.  This is deliberately Server-only and does
     * not change the existing SmartPlug onboarding implementation.
     */
    fun selectHomeWifi(ssid: String) {
        val savedPassword = tokenStore.savedWifiPassword(ssid).orEmpty()
        _uiState.value = _uiState.value.copy(
            selectedSsid = ssid,
            homeWifiPassword = savedPassword,
            homeWifiPasswordVisible = false,
        )
    }
    fun setHomeWifiPassword(password: String) { _uiState.value = _uiState.value.copy(homeWifiPassword = password) }
    fun toggleHomeWifiPasswordVisible() {
        _uiState.value = _uiState.value.copy(homeWifiPasswordVisible = !_uiState.value.homeWifiPasswordVisible)
    }

    fun provision() {
        val state = _uiState.value
        val setupSsid = state.selectedSetupSsid ?: return
        val homeSsid = state.selectedSsid ?: return
        val serverId = state.serverId ?: return
        _uiState.value = state.copy(step = ServerOnboardingStep.CONFIGURING, busy = true, homeWifiPassword = "", homeWifiPasswordVisible = false, message = "Mengirim konfigurasi Wi-Fi ke server…")
        safeLaunch(onError = { error ->
            wifi.releaseApBinding()
            _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Provisioning server gagal: ${error.message ?: "network_error"}")
        }) {
            val result = safeApiCall { clients.serverSetupApi().apply(mapOf("ssid" to homeSsid, "wifi_password" to state.homeWifiPassword, "api_token" to serverApiToken, "broker_username" to mqttUsername, "broker_password" to mqttPassword)) }
            if (result is ApiResult.Failure) {
                _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Konfigurasi ditolak: ${result.error.errorCode}")
                return@safeLaunch
            }
            // Persist only after the server has accepted the payload, consistent
            // with AddSmartPlugViewModel and avoiding storage of rejected input.
            tokenStore.saveWifiPassword(homeSsid, state.homeWifiPassword)
            _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.WAITING_FOR_CONNECTION, message = "Menunggu $setupSsid tersambung ke Wi-Fi rumah…")
            var stationIp = ""
            var attempt = 0
            while (stationIp.isBlank() && attempt < STATION_IP_POLL_COUNT) {
                delay(STATION_IP_POLL_INTERVAL_MS)
                stationIp = (safeApiCall { clients.serverSetupApi().status() } as? ApiResult.Success)?.value?.station?.ip.orEmpty()
                attempt++
            }
            wifi.releaseApBinding()
            if (stationIp.isBlank()) {
                _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.FAILED, busy = false, message = "Server menyimpan konfigurasi, tetapi belum memperoleh IP Wi-Fi rumah.")
                return@safeLaunch
            }
            serverProfiles.save(RegisteredServer(serverId = serverId, displayName = serverId, host = stationIp, mqttPort = state.mqttPort, mqttUsername = mqttUsername, mqttPassword = mqttPassword), serverApiToken)
            _uiState.value = _uiState.value.copy(step = ServerOnboardingStep.SUCCESS, busy = false, completed = true, message = "Server $serverId tersimpan dan siap digunakan.")
        }
    }

    override fun onCleared() {
        wifi.releaseApBinding()
        super.onCleared()
    }

    companion object {
        private const val SETUP_PASSWORD = "SmartPlugSetup"
        private const val STATION_IP_POLL_COUNT = 20
        private const val STATION_IP_POLL_INTERVAL_MS = 1_000L

        private fun randomHex(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
