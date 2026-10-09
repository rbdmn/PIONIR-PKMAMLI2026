package com.smartplug.app.ui.screens.addplug

import com.smartplug.app.domain.model.DiscoveredSmartPlugAp
import com.smartplug.app.domain.model.HomeWifiNetwork

enum class OnboardingStep {
    SCANNING_DEVICES,
    CONNECTING_TO_DEVICE,
    SCANNING_HOME_WIFI,
    CHOOSING_HOME_WIFI,
    CONFIGURING,
    WAITING_FOR_CONNECTION,
    VERIFYING,
    SUCCESS,
    FAILED,
}

data class AddSmartPlugUiState(
    val step: OnboardingStep = OnboardingStep.SCANNING_DEVICES,
    val isScanningDevices: Boolean = true,
    val discoveredAps: List<DiscoveredSmartPlugAp> = emptyList(),
    val selectedAp: DiscoveredSmartPlugAp? = null,
    val pairingToken: String? = null,
    val expectedDeviceId: String? = null,
    val homeWifiNetworks: List<HomeWifiNetwork> = emptyList(),
    val selectedSsid: String? = null,
    val homeWifiPassword: String = "",
    val homeWifiPasswordVisible: Boolean = false,
    val configurationId: String? = null,
    val resultDisplayName: String = "",
    val resultDeviceId: String? = null,
    val resultLanIp: String? = null,
    val errorMessage: String? = null,
    val permissionMissing: Boolean = false,
    val locationServiceDisabled: Boolean = false,
)
