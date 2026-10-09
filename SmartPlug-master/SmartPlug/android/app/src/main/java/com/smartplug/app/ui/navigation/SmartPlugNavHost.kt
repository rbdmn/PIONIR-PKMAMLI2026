package com.smartplug.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.smartplug.app.ui.components.AppScaffold
import com.smartplug.app.ui.screens.addplug.AddSmartPlugScreen
import com.smartplug.app.ui.screens.addexisting.AddExistingSmartPlugScreen
import com.smartplug.app.ui.screens.addserver.AddServerScreen
import com.smartplug.app.ui.screens.devicedetail.DeviceDetailScreen
import com.smartplug.app.ui.screens.diagnostics.DiagnosticsScreen
import com.smartplug.app.ui.screens.devices.DeviceListScreen
import com.smartplug.app.ui.screens.history.EnergyHistoryScreen
import com.smartplug.app.ui.screens.home.HomeScreen
import com.smartplug.app.ui.screens.settings.SettingsScreen
import com.smartplug.app.ui.screens.storage.StorageScreen
import com.smartplug.app.ui.screens.schedule.ScheduleScreen

@Composable
fun SmartPlugNavHost(
    sidebarHidden: Boolean,
    onToggleSidebarHidden: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val navShell: NavShellViewModel = hiltViewModel()
    val showStorage by navShell.showStorage.collectAsStateWithLifecycle()

    // The tab disappears with the last SERVER-mode device; never leave the user on an orphan screen.
    LaunchedEffect(showStorage, currentRoute) {
        if (!showStorage && currentRoute == Routes.Storage.route) {
            navController.navigate(Routes.Home.route) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    AppScaffold(
        destinations = topLevelDestinations(showStorage),
        currentRoute = currentRoute,
        onNavigate = { route ->
            // A top-level tap must actually leave a detail page.  `popBackStack` avoids a
            // launchSingleTop no-op that left monitoring visible after tapping Home.
            if (!navController.popBackStack(route, inclusive = false)) {
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
        railHidden = sidebarHidden,
        onToggleRailHidden = onToggleSidebarHidden,
    ) { modifier ->
        NavHost(
            navController = navController,
            startDestination = Routes.Home.route,
            modifier = modifier,
        ) {
            composable(Routes.Home.route) {
                HomeScreen(
                    onOpenDevice = { deviceId ->
                        navController.navigate(Routes.DeviceDetail.createRoute(deviceId))
                    },
                    onAddDevice = { navController.navigate(Routes.Devices.route) },
                )
            }
            composable(Routes.Devices.route) {
                DeviceListScreen(
                    onOpenDevice = { deviceId ->
                        navController.navigate(Routes.DeviceDetail.createRoute(deviceId))
                    },
                    onAddSmartPlug = { navController.navigate(Routes.AddSmartPlug.route) },
                    onAddExistingSmartPlug = { navController.navigate(Routes.AddExistingSmartPlug.route) },
                    onAddServer = { navController.navigate(Routes.AddServer.route) },
                )
            }
            composable(Routes.AddSmartPlug.route) {
                AddSmartPlugScreen(
                    onFinished = { deviceId ->
                        navController.navigate(Routes.DeviceDetail.createRoute(deviceId)) {
                            popUpTo(Routes.AddSmartPlug.route) { inclusive = true }
                        }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(Routes.AddExistingSmartPlug.route) {
                AddExistingSmartPlugScreen(
                    onFinished = { deviceId ->
                        navController.navigate(Routes.DeviceDetail.createRoute(deviceId)) {
                            popUpTo(Routes.AddExistingSmartPlug.route) { inclusive = true }
                        }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable(Routes.AddServer.route) {
                AddServerScreen(onDone = { navController.popBackStack() }, onBack = { navController.popBackStack() })
            }
            composable(Routes.Diagnostics.route) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Storage.route) {
                StorageScreen()
            }
            composable(Routes.Settings.route) {
                SettingsScreen(
                    onOpenDiagnostics = { navController.navigate(Routes.Diagnostics.route) },
                    onResetCompleted = {
                        navController.navigate(Routes.Devices.route) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.DeviceDetail.route) { entry ->
                val deviceId = entry.arguments?.getString(Routes.DeviceDetail.ARG_DEVICE_ID).orEmpty()
                DeviceDetailScreen(
                    deviceId = deviceId,
                    onOpenHistory = { navController.navigate(Routes.EnergyHistory.createRoute(deviceId)) },
                    onOpenSchedule = { navController.navigate(Routes.Schedule.createRoute(deviceId)) },
                    onBack = { navController.popBackStack() },
                    onDeviceRemoved = {
                        navController.navigate(Routes.Devices.route) {
                            popUpTo(Routes.Home.route)
                        }
                    },
                )
            }
            composable(Routes.EnergyHistory.route) { entry ->
                val deviceId = entry.arguments?.getString(Routes.EnergyHistory.ARG_DEVICE_ID).orEmpty()
                EnergyHistoryScreen(deviceId = deviceId, onBack = { navController.popBackStack() })
            }
            composable(Routes.Schedule.route) {
                ScheduleScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
