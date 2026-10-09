package com.smartplug.app.ui.screens.addserver

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.smartplug.app.ui.components.popIn
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import com.smartplug.app.ui.components.StepperHeader
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.DiscoveredServerSetupAp
import com.smartplug.app.domain.model.HomeWifiNetwork
import com.smartplug.app.domain.model.rssiToSignalPercent
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.ui.components.OrbitLoadingIndicator
import com.smartplug.app.ui.theme.AccentGreen

/** Server setup uses the same visual stages and controls as SmartPlug setup. */
@Composable
fun AddServerScreen(onDone: () -> Unit, onBack: () -> Unit, viewModel: AddServerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { viewModel.refreshPrerequisites(); viewModel.startServerSearch() }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(localized(language, "Tambah Server", "Add Server")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = localized(language, "Batal", "Cancel")) } },
            actions = {
                if (state.step == ServerOnboardingStep.CHOOSING_HOME_WIFI) {
                    IconButton(onClick = viewModel::retryHomeWifiScan) {
                        Icon(Icons.Filled.Refresh, contentDescription = localized(language, "Muat ulang Wi-Fi", "Refresh Wi-Fi"))
                    }
                }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (state.permissionMissing) {
                ServerPermissionContent { permissionLauncher.launch(requiredWifiPermission()) }
                return@Column
            }
            if (state.locationServiceDisabled) {
                ServerLocationDisabledContent(
                    onOpenSettings = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    onRecheck = { viewModel.refreshPrerequisites(); viewModel.startServerSearch() },
                )
                return@Column
            }
            StepperHeader(current = serverStage(state.step), modifier = Modifier.padding(bottom = 16.dp))
            when (state.step) {
                ServerOnboardingStep.SCANNING_SERVER -> ServerScanContent(state.discoveredSetupAps, state.busy, { viewModel.selectSetupServer(it.ssid) }, viewModel::startServerSearch)
                ServerOnboardingStep.SCANNING_HOME_WIFI -> SetupProgress(state.message ?: localized(language, "Membaca daftar Wi-Fi rumah dari server…", "Reading home Wi-Fi networks from server…"))
                ServerOnboardingStep.CHOOSING_HOME_WIFI -> ServerHomeWifiContent(state, viewModel)
                ServerOnboardingStep.CONFIGURING -> SetupProgress(state.message ?: localized(language, "Mengirim konfigurasi ke server…", "Sending configuration to server…"))
                ServerOnboardingStep.WAITING_FOR_CONNECTION -> SetupProgress(state.message ?: localized(language, "Menunggu Server tersambung ke Wi-Fi rumah…", "Waiting for Server to connect to home Wi-Fi…"))
                ServerOnboardingStep.SUCCESS -> ServerSuccessContent(state.message, onDone)
                ServerOnboardingStep.FAILED -> ServerFailedContent(state.message, viewModel::startServerSearch, onBack)
            }
        }
    }
}

private fun requiredWifiPermission(): String = android.Manifest.permission.ACCESS_FINE_LOCATION

@Composable
private fun ServerPermissionContent(onRequest: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.padding(top = 48.dp))
        Text(localized(language, "SmartPlug perlu izin Wi-Fi untuk menemukan perangkat di sekitar Anda.", "SmartPlug needs Wi-Fi permission to find nearby devices."), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text(localized(language, "Berikan izin", "Grant permission")) }
    }
}

@Composable
private fun ServerLocationDisabledContent(onOpenSettings: () -> Unit, onRecheck: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.padding(top = 48.dp))
        Text(localized(language, "Aktifkan Lokasi untuk memindai Server", "Enable Location to scan for Server"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(localized(language, "Android memerlukan Lokasi aktif agar aplikasi dapat membaca hasil pindai Wi-Fi.", "Android requires Location to be enabled before apps can read Wi-Fi scan results."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) { Text(localized(language, "Buka pengaturan Lokasi", "Open Location settings")) }
        TextButton(onClick = onRecheck, modifier = Modifier.padding(top = 4.dp)) { Text(localized(language, "Sudah aktif, coba lagi", "It is enabled, try again")) }
    }
}

@Composable
private fun SetupProgress(message: String) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        OrbitLoadingIndicator()
        Text(message, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ServerScanContent(servers: List<DiscoveredServerSetupAp>, scanning: Boolean, onSelect: (DiscoveredServerSetupAp) -> Unit, onRescan: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(localized(language, "Pilih Server di sekitar Anda", "Choose a nearby Server"), style = MaterialTheme.typography.titleLarge)
        Text(localized(language, "Pastikan Server menyala dan berada dalam mode setup.", "Make sure Server is powered on and in setup mode."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        if (servers.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                if (scanning) {
                    OrbitLoadingIndicator(modifier = Modifier.size(28.dp))
                    Text(localized(language, "Mencari Server…", "Searching for Server…"), modifier = Modifier.padding(top = 12.dp))
                } else {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Text(localized(language, "Belum ditemukan Server di sekitar", "No nearby Server found"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(localized(language, "Pastikan server menyala, dekatkan HP, lalu pindai ulang.", "Make sure it is powered on, move your phone closer, then scan again."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Button(onClick = onRescan, modifier = Modifier.padding(top = 16.dp)) { Text(localized(language, "Pindai ulang", "Scan again")) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.ssid }) { server ->
                    SmartPlugCard(modifier = Modifier.clickable { onSelect(server) }) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Server", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (server.isConnectionProbe) {
                                        localized(language, "Coba hubungkan untuk memverifikasi server", "Connect to verify the server")
                                    } else {
                                        localized(language, "Sinyal ${rssiToSignalPercent(server.rssi)}%", "Signal ${rssiToSignalPercent(server.rssi)}%")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Filled.Wifi, contentDescription = null)
                        }
                    }
                }
            }
            Button(onClick = onRescan, modifier = Modifier.padding(top = 12.dp)) { Text(localized(language, "Pindai ulang", "Scan again")) }
        }
    }
}

@Composable
private fun ServerHomeWifiContent(state: AddServerUiState, viewModel: AddServerViewModel) {
    val language = LocalAppLanguage.current
    val selectedNetwork = state.homeWifiNetworks.firstOrNull { it.ssid == state.selectedSsid }
    val passwordRequired = selectedNetwork?.security != "open"
    Column(modifier = Modifier.fillMaxSize()) {
        Text(localized(language, "Pilih Wi-Fi rumah", "Choose home Wi-Fi"), style = MaterialTheme.typography.titleLarge)
        Text(localized(language, "Server akan tersambung ke jaringan ini untuk menerima data perangkat.", "Server will join this network to receive device data."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        // Reserve the remaining height for the list.  `fill = false` can leave its hit
        // region overlapping the controls below on some Android 16 keyboard layouts.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(state.homeWifiNetworks) { network -> ServerHomeWifiRow(network, state.selectedSsid == network.ssid) { viewModel.selectHomeWifi(network.ssid) } }
        }
        if (state.selectedSsid != null) {
            OutlinedTextField(
                value = state.homeWifiPassword,
                onValueChange = viewModel::setHomeWifiPassword,
                label = { Text(localized(language, "Password Wi-Fi", "Wi-Fi password")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.provision() }),
                visualTransformation = if (state.homeWifiPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleHomeWifiPasswordVisible) {
                        Icon(if (state.homeWifiPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (state.homeWifiPasswordVisible) localized(language, "Sembunyikan password", "Hide password") else localized(language, "Tampilkan password", "Show password"))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(onClick = viewModel::provision, enabled = !passwordRequired || state.homeWifiPassword.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(localized(language, "Hubungkan", "Connect")) }
        }
    }
}

@Composable
private fun ServerHomeWifiRow(network: HomeWifiNetwork, selected: Boolean, onClick: () -> Unit) {
    SmartPlugCard(modifier = Modifier.clickable(onClick = onClick), highlighted = selected) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(network.ssid, fontWeight = FontWeight.SemiBold)
                Text("${network.security} · ${rssiToSignalPercent(network.rssi)}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
    }
}

@Composable
private fun ServerSuccessContent(message: String?, onDone: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.padding(top = 32.dp).size(72.dp).popIn())
        Text(localized(language, "Server siap digunakan", "Server is ready"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) { Text(localized(language, "Lanjut ke perangkat", "Continue to devices")) }
    }
}

@Composable
private fun ServerFailedContent(message: String?, onRetry: () -> Unit, onCancel: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Text(message ?: localized(language, "Pemasangan gagal", "Pairing failed"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 48.dp))
        Row(modifier = Modifier.padding(top = 16.dp)) {
            Button(onClick = onRetry, modifier = Modifier.padding(end = 8.dp)) { Text(localized(language, "Coba lagi", "Try again")) }
            Button(onClick = onCancel) { Text(localized(language, "Batal", "Cancel")) }
        }
    }
}

private fun serverStage(step: ServerOnboardingStep): Int = when (step) {
    ServerOnboardingStep.SCANNING_SERVER -> 1
    ServerOnboardingStep.SCANNING_HOME_WIFI, ServerOnboardingStep.CHOOSING_HOME_WIFI -> 2
    ServerOnboardingStep.CONFIGURING, ServerOnboardingStep.WAITING_FOR_CONNECTION, ServerOnboardingStep.FAILED -> 3
    ServerOnboardingStep.SUCCESS -> 4
}
