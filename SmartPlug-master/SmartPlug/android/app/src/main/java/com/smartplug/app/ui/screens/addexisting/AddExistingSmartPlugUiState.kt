package com.smartplug.app.ui.screens.addexisting

import com.smartplug.app.domain.model.DiscoveredExistingSmartPlug

enum class ExistingSmartPlugStep {
    DISCOVERING,
    SELECTING,
    ENTER_INVITATION,
    ENROLLING,
    SUCCESS,
    FAILED,
}

data class AddExistingSmartPlugUiState(
    val step: ExistingSmartPlugStep = ExistingSmartPlugStep.DISCOVERING,
    val discoveredDevices: List<DiscoveredExistingSmartPlug> = emptyList(),
    val selectedDevice: DiscoveredExistingSmartPlug? = null,
    val invitationCode: String = "",
    val resultDeviceId: String? = null,
    val errorMessage: String? = null,
)
