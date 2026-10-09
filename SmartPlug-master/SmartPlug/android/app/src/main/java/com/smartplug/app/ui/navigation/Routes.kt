package com.smartplug.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Routes(val route: String) {
    data object Home : Routes("home")
    data object Devices : Routes("devices")
    data object AddSmartPlug : Routes("add_smartplug")
    data object AddExistingSmartPlug : Routes("add_existing_smartplug")
    data object AddServer : Routes("add_server")
    data object Settings : Routes("settings")
    data object Storage : Routes("storage")
    data object Diagnostics : Routes("diagnostics")

    data object DeviceDetail : Routes("device/{deviceId}") {
        fun createRoute(deviceId: String) = "device/$deviceId"
        const val ARG_DEVICE_ID = "deviceId"
    }

    data object EnergyHistory : Routes("device/{deviceId}/history") {
        fun createRoute(deviceId: String) = "device/$deviceId/history"
        const val ARG_DEVICE_ID = "deviceId"
    }

    data object Schedule : Routes("device/{deviceId}/schedule") {
        fun createRoute(deviceId: String) = "device/$deviceId/schedule"
        const val ARG_DEVICE_ID = "deviceId"
    }
}

/** Top-level destinations shown in the nav rail / bottom bar / drawer. */
data class TopLevelDestination(
    val route: String,
    val labelIndonesian: String,
    val labelEnglish: String,
    val icon: ImageVector,
)

private val homeDestination = TopLevelDestination(Routes.Home.route, "Beranda", "Home", Icons.Filled.Home)
private val devicesDestination = TopLevelDestination(Routes.Devices.route, "Perangkat", "Devices", Icons.Filled.Power)
private val storageDestination = TopLevelDestination(Routes.Storage.route, "Storage", "Storage", Icons.Filled.Storage)
private val settingsDestination = TopLevelDestination(Routes.Settings.route, "Pengaturan", "Settings", Icons.Filled.Settings)

/** Home, Devices, [Storage only when a SERVER-mode SmartPlug is saved], Settings. */
fun topLevelDestinations(showStorage: Boolean): List<TopLevelDestination> =
    if (showStorage) listOf(homeDestination, devicesDestination, storageDestination, settingsDestination)
    else listOf(homeDestination, devicesDestination, settingsDestination)
