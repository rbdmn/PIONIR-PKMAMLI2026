package com.smartplug.app.ui.screens.addplug

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
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import com.smartplug.app.ui.components.StepperHeader
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.DiscoveredSmartPlugAp
import com.smartplug.app.domain.model.HomeWifiNetwork
import com.smartplug.app.domain.model.rssiToSignalPercent
import com.smartplug.app.ui.components.FullScreenLoading
import com.smartplug.app.ui.components.OrbitLoadingIndicator
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.theme.AccentGreen
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized

@Composable
fun AddSmartPlugScreen(
    onFinished: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: AddSmartPlugViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalAppLanguage.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onPermissionGranted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localized(language, "Tambah SmartPlug", "Add SmartPlug")) },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.ArrowBack, contentDescription = localized(language, "Batal", "Cancel")) }
                },
                actions = {
                    if (uiState.step == OnboardingStep.CHOOSING_HOME_WIFI) {
                        IconButton(onClick = viewModel::refreshHomeWifi) {
                            Icon(Icons.Filled.Refresh, contentDescription = localized(language, "Muat ulang Wi-Fi", "Refresh Wi-Fi"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (uiState.permissionMissing) {
                PermissionRequestContent(onRequest = {
                    permissionLauncher.launch(requiredWifiPermission())
                })
                return@Scaffold
            }

            if (uiState.locationServiceDisabled) {
                LocationServiceDisabledContent(
                    onOpenSettings = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        )
                    },
                    onRecheck = viewModel::startScan,
                )
                return@Scaffold
            }

            StepperHeader(current = pairingStage(uiState.step), modifier = Modifier.padding(bottom = 16.dp))

            when (uiState.step) {
                OnboardingStep.SCANNING_DEVICES -> ScanningDevicesContent(
                    aps = uiState.discoveredAps,
                    isScanning = uiState.isScanningDevices,
                    onSelect = viewModel::selectAp,
                    onRescan = viewModel::startScan,
                )
                OnboardingStep.CONNECTING_TO_DEVICE -> StepProgress(localized(language, "Menghubungkan ke ${uiState.selectedAp?.ssid}...", "Connecting to ${uiState.selectedAp?.ssid}..."))
                OnboardingStep.SCANNING_HOME_WIFI -> StepProgress(localized(language, "Membaca daftar Wi-Fi rumah dari perangkat...", "Reading home Wi-Fi networks from the device..."))
                OnboardingStep.CHOOSING_HOME_WIFI -> ChoosingHomeWifiContent(uiState, viewModel)
                OnboardingStep.CONFIGURING -> StepProgress(localized(language, "Mengirim konfigurasi ke perangkat...", "Sending configuration to the device..."))
                OnboardingStep.WAITING_FOR_CONNECTION -> StepProgress(localized(language, "Menunggu SmartPlug tersambung ke Wi-Fi rumah...", "Waiting for SmartPlug to connect to home Wi-Fi..."))
                OnboardingStep.VERIFYING -> StepProgress(localized(language, "Memverifikasi perangkat di Wi-Fi rumah...", "Verifying the device on home Wi-Fi..."))
                OnboardingStep.SUCCESS -> SuccessContent(uiState, viewModel, onFinished)
                OnboardingStep.FAILED -> FailedContent(uiState.errorMessage, onRetry = viewModel::retry, onCancel = onCancel)
            }
        }
    }
}

private fun requiredWifiPermission(): String = android.Manifest.permission.ACCESS_FINE_LOCATION

@Composable
private fun PermissionRequestContent(onRequest: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.padding(top = 48.dp))
        Text(
            localized(language, "SmartPlug perlu izin Wi-Fi untuk menemukan perangkat di sekitar Anda.", "SmartPlug needs Wi-Fi permission to find nearby devices."),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text(localized(language, "Berikan izin", "Grant permission")) }
    }
}

@Composable
private fun LocationServiceDisabledContent(onOpenSettings: () -> Unit, onRecheck: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.padding(top = 48.dp))
        Text(
            localized(language, "Aktifkan Lokasi untuk memindai SmartPlug", "Enable Location to scan for SmartPlug"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            localized(language,
                "Android mewajibkan Lokasi aktif agar aplikasi dapat membaca hasil pindai Wi-Fi, meskipun izin Wi-Fi sudah diberikan. Ini pengaturan sistem, bukan pelacakan lokasi oleh SmartPlug.",
                "Android requires Location to be enabled before apps can read Wi-Fi scan results, even after Wi-Fi permission is granted. This is a system setting, not SmartPlug location tracking."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) { Text(localized(language, "Buka pengaturan Lokasi", "Open Location settings")) }
        androidx.compose.material3.TextButton(onClick = onRecheck, modifier = Modifier.padding(top = 4.dp)) {
            Text(localized(language, "Sudah aktif, coba lagi", "It is enabled, try again"))
        }
    }
}

@Composable
private fun StepProgress(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OrbitLoadingIndicator()
        Text(message, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ScanningDevicesContent(
    aps: List<DiscoveredSmartPlugAp>,
    isScanning: Boolean,
    onSelect: (DiscoveredSmartPlugAp) -> Unit,
    onRescan: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(localized(language, "Pilih SmartPlug di sekitar Anda", "Choose a nearby SmartPlug"), style = MaterialTheme.typography.titleLarge)
        Text(
            localized(language, "Pastikan SmartPlug sudah menyala dan belum pernah dipasangkan.", "Make sure the SmartPlug is powered on and has not been paired before."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        if (aps.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                if (isScanning) {
                    OrbitLoadingIndicator(modifier = Modifier.size(28.dp))
                    Text(localized(language, "Mencari SmartPlug...", "Searching for SmartPlug..."), modifier = Modifier.padding(top = 12.dp))
                } else {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Text(
                        localized(language, "Belum ditemukan SmartPlug di sekitar", "No nearby SmartPlug found"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        localized(language, "Pastikan perangkat menyala, dekatkan HP ke SmartPlug, lalu pindai ulang.", "Make sure it is powered on, move your phone closer to the SmartPlug, then scan again."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Button(onClick = onRescan, modifier = Modifier.padding(top = 16.dp)) { Text(localized(language, "Pindai ulang", "Scan again")) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(aps, key = { it.ssid }) { ap ->
                    SmartPlugCard(modifier = Modifier.clickable { onSelect(ap) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("SmartPlug ${ap.unitId}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    localized(language, "Sinyal ${rssiToSignalPercent(ap.rssi)}%", "Signal ${rssiToSignalPercent(ap.rssi)}%"),
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
private fun ChoosingHomeWifiContent(uiState: AddSmartPlugUiState, viewModel: AddSmartPlugViewModel) {
    val language = LocalAppLanguage.current
    val selectedNetwork = uiState.homeWifiNetworks.firstOrNull { it.ssid == uiState.selectedSsid }
    val passwordRequired = selectedNetwork?.security != "open"
    Column(modifier = Modifier.fillMaxSize()) {
        Text(localized(language, "Pilih Wi-Fi rumah", "Choose home Wi-Fi"), style = MaterialTheme.typography.titleLarge)
        Text(
            localized(language, "SmartPlug akan tersambung ke jaringan ini untuk pemantauan sehari-hari.", "SmartPlug will connect to this network for everyday monitoring."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        // Give the list all remaining height: long scan results stay scrollable instead of
        // looking as though only the first handful of SSIDs exist.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            // No caller-supplied key here: a malformed/older firmware response
            // must never turn repeated SSIDs into a Compose runtime crash.
            items(uiState.homeWifiNetworks) { network ->
                HomeWifiRow(
                    network = network,
                    selected = uiState.selectedSsid == network.ssid,
                    onClick = { viewModel.selectSsid(network.ssid) },
                )
            }
        }

        if (uiState.selectedSsid != null) {
            OutlinedTextField(
                value = uiState.homeWifiPassword,
                onValueChange = viewModel::setHomeWifiPassword,
                label = { Text(localized(language, "Password Wi-Fi", "Wi-Fi password")) },
                singleLine = true,
                visualTransformation = if (uiState.homeWifiPasswordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = viewModel::toggleHomeWifiPasswordVisible) {
                        Icon(
                            if (uiState.homeWifiPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (uiState.homeWifiPasswordVisible) localized(language, "Sembunyikan password", "Hide password") else localized(language, "Tampilkan password", "Show password"),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Button(
                onClick = viewModel::confirmAndConnect,
                enabled = !passwordRequired || uiState.homeWifiPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text(localized(language, "Hubungkan", "Connect")) }
        }
    }
}

@Composable
private fun HomeWifiRow(network: HomeWifiNetwork, selected: Boolean, onClick: () -> Unit) {
    SmartPlugCard(modifier = Modifier.clickable(onClick = onClick), highlighted = selected) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(network.ssid, fontWeight = FontWeight.SemiBold)
                Text(
                    "${network.security} · ${rssiToSignalPercent(network.rssi)}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
    }
}

@Composable
private fun SuccessContent(uiState: AddSmartPlugUiState, viewModel: AddSmartPlugViewModel, onFinished: (String) -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.padding(top = 32.dp).size(72.dp).popIn(),
        )
        Text(localized(language, "SmartPlug siap digunakan", "SmartPlug is ready"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = uiState.resultDisplayName,
            onValueChange = viewModel::setResultDisplayName,
            label = { Text(localized(language, "Nama perangkat", "Device name")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        Button(
            onClick = {
                viewModel.finishWithResultName(onFinished)
            },
            enabled = uiState.resultDisplayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text(localized(language, "Lanjut ke monitoring", "Continue to monitoring")) }
    }
}

@Composable
private fun FailedContent(message: String?, onRetry: () -> Unit, onCancel: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Text(
            message ?: localized(language, "Pemasangan gagal", "Pairing failed"),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 48.dp),
        )
        Row(modifier = Modifier.padding(top = 16.dp)) {
            Button(onClick = onRetry, modifier = Modifier.padding(end = 8.dp)) { Text(localized(language, "Coba lagi", "Try again")) }
            Button(onClick = onCancel) { Text(localized(language, "Batal", "Cancel")) }
        }
    }
}

private fun pairingStage(step: OnboardingStep): Int = when (step) {
    OnboardingStep.SCANNING_DEVICES -> 1
    OnboardingStep.CONNECTING_TO_DEVICE, OnboardingStep.SCANNING_HOME_WIFI, OnboardingStep.CHOOSING_HOME_WIFI -> 2
    OnboardingStep.CONFIGURING, OnboardingStep.WAITING_FOR_CONNECTION, OnboardingStep.VERIFYING, OnboardingStep.FAILED -> 3
    OnboardingStep.SUCCESS -> 4
}
