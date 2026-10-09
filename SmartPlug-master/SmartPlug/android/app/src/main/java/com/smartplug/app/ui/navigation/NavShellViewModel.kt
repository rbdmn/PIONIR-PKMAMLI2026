package com.smartplug.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.util.shouldShowStorageTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Drives navigation chrome from saved connection modes, not from whether a server is reachable. */
@HiltViewModel
class NavShellViewModel @Inject constructor(
    deviceRepository: DeviceRepository,
    serverProfileStore: ServerProfileStore,
) : ViewModel() {
    val showStorage: StateFlow<Boolean> = combine(
        deviceRepository.observeDevices(),
        serverProfileStore.profiles,
    ) { devices, servers -> shouldShowStorageTab(devices, servers.map { it.serverId }.toSet()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
