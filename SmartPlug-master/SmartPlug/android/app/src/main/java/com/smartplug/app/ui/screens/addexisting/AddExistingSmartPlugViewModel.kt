package com.smartplug.app.ui.screens.addexisting

import androidx.lifecycle.ViewModel
import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.dto.AccessEnrollRequestDto
import com.smartplug.app.data.remote.dto.DeviceAccessProfileDto
import com.smartplug.app.data.remote.safeApiCall
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.DiscoveryRepository
import com.smartplug.app.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

private const val EXISTING_DEVICE_DISCOVERY_TIMEOUT_MS = 5_000L

/**
 * HP2+ path only.  It deliberately does not use setup-AP Wi-Fi binding or PairingRepository:
 * this phone joins an already-provisioned SmartPlug using an owner-created, short-lived invite.
 */
@HiltViewModel
class AddExistingSmartPlugViewModel @Inject constructor(
    private val discoveryRepository: DiscoveryRepository,
    private val deviceRepository: DeviceRepository,
    private val apiClientFactory: ApiClientFactory,
    private val tokenStore: SecureTokenStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddExistingSmartPlugUiState())
    val uiState: StateFlow<AddExistingSmartPlugUiState> = _uiState.asStateFlow()

    init {
        discover()
    }

    fun discover() {
        _uiState.value = _uiState.value.copy(
            step = ExistingSmartPlugStep.DISCOVERING,
            discoveredDevices = emptyList(),
            selectedDevice = null,
            invitationCode = "",
            errorMessage = null,
        )
        launchSafely {
            val devices = discoveryRepository.discoverExistingDevices(EXISTING_DEVICE_DISCOVERY_TIMEOUT_MS)
            _uiState.value = _uiState.value.copy(
                step = ExistingSmartPlugStep.SELECTING,
                discoveredDevices = devices,
            )
        }
    }

    fun selectDevice(deviceId: String) {
        val device = _uiState.value.discoveredDevices.firstOrNull { it.deviceId == deviceId } ?: return
        launchSafely {
            if (deviceRepository.getDevice(device.deviceId) != null) {
                _uiState.value = _uiState.value.copy(
                    step = ExistingSmartPlugStep.FAILED,
                    errorMessage = "SmartPlug ini sudah terdaftar di HP ini.",
                )
                return@launchSafely
            }
            _uiState.value = _uiState.value.copy(
                step = ExistingSmartPlugStep.ENTER_INVITATION,
                selectedDevice = device,
                invitationCode = "",
                errorMessage = null,
            )
        }
    }

    fun setInvitationCode(value: String) {
        _uiState.value = _uiState.value.copy(invitationCode = value.filter(Char::isDigit).take(6))
    }

    fun enroll() {
        val state = _uiState.value
        val device = state.selectedDevice ?: return
        val code = state.invitationCode
        if (code.length != 6) return
        _uiState.value = state.copy(step = ExistingSmartPlugStep.ENROLLING, errorMessage = null)
        launchSafely {
            // Repeat this check immediately before the network mutation so selecting a device
            // during another Room update cannot replace an existing local credential.
            if (deviceRepository.getDevice(device.deviceId) != null) {
                _uiState.value = _uiState.value.copy(
                    step = ExistingSmartPlugStep.FAILED,
                    errorMessage = "SmartPlug ini sudah terdaftar di HP ini.",
                )
                return@launchSafely
            }

            val api = apiClientFactory.deviceApi(ApiClientFactory.lanBaseUrl(device.host))
            val enrollment = safeApiCall {
                api.enrollAccess(AccessEnrollRequestDto(device.deviceId, code))
            }
            when (enrollment) {
                is ApiResult.Failure -> fail("Kode undangan atau koneksi perangkat tidak valid (${enrollment.error.errorCode}).")
                is ApiResult.Success -> {
                    val response = enrollment.value
                    if (response.role != "member" || response.credential.isBlank() || response.profile.deviceId != device.deviceId) {
                        fail("Respons enrolment SmartPlug tidak valid.")
                        return@launchSafely
                    }

                    // The invitation is single-use. Once the SmartPlug has issued this
                    // member credential, retain it in the encrypted store before any
                    // follow-up request so a brief Wi-Fi loss cannot strand the member
                    // with an already-consumed invitation code.
                    tokenStore.setMemberCredential(response.profile.deviceId, response.credential)

                    // Do not trust the enrol response alone.  Prove the freshly issued member
                    // credential can read the safe profile. If the verification request is
                    // interrupted, use the safe profile bundled in the enrol response
                    // as a recoverable local baseline; the normal status/profile poll will
                    // verify the credential again when connectivity returns.
                    when (val verifiedProfile = safeApiCall {
                        api.getAccessProfile("Bearer ${response.credential}")
                    }) {
                        is ApiResult.Failure -> {
                            deviceRepository.saveDevice(registeredDevice(response.profile, device.host))
                            _uiState.value = _uiState.value.copy(
                                step = ExistingSmartPlugStep.SUCCESS,
                                resultDeviceId = response.profile.deviceId,
                                invitationCode = "",
                            )
                        }
                        is ApiResult.Success -> {
                            val profile = verifiedProfile.value
                            if (profile.deviceId != device.deviceId) {
                                tokenStore.clearAccessCredential(response.profile.deviceId)
                                fail("Identitas SmartPlug tidak cocok dengan hasil pencarian.")
                                return@launchSafely
                            }
                            deviceRepository.saveDevice(registeredDevice(profile, device.host))
                            _uiState.value = _uiState.value.copy(
                                step = ExistingSmartPlugStep.SUCCESS,
                                resultDeviceId = profile.deviceId,
                                invitationCode = "",
                            )
                        }
                    }
                }
            }
        }
    }

    fun retry() = discover()

    private fun fail(message: String) {
        _uiState.value = _uiState.value.copy(step = ExistingSmartPlugStep.FAILED, errorMessage = message, invitationCode = "")
    }

    private fun registeredDevice(profile: DeviceAccessProfileDto, host: String): SmartPlugDevice {
        val isServerMode = profile.integrationMode == "mqtt" || profile.integrationMode == "server"
        return SmartPlugDevice(
            deviceId = profile.deviceId,
            // The safe profile intentionally does not disclose STA MAC. This sentinel is
            // never sent to the device and avoids inventing a hardware identity.
            staMac = "not-disclosed",
            displayName = profile.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: "SmartPlug ${profile.deviceId.takeLast(4)}",
            lanIp = host,
            integrationMode = if (isServerMode) IntegrationMode.SERVER else IntegrationMode.DIRECT,
            serverId = null,
            serverHost = profile.serverHost,
            serverPort = profile.serverPort ?: 80,
            apUnitId = null,
        )
    }

    private fun launchSafely(block: suspend () -> Unit) = safeLaunch(
        onError = { error -> fail("Terjadi kesalahan tak terduga: ${error.message ?: error::class.simpleName}") },
        block = block,
    )
}
