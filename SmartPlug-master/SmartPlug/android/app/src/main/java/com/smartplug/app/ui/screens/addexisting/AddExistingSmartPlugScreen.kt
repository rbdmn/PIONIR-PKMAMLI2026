package com.smartplug.app.ui.screens.addexisting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.DiscoveredExistingSmartPlug
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.ui.components.OrbitLoadingIndicator
import com.smartplug.app.ui.theme.AccentGreen

@Composable
fun AddExistingSmartPlugScreen(
    onFinished: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: AddExistingSmartPlugViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localized(language, "Tambah SmartPlug yang sudah ada", "Add existing SmartPlug")) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = localized(language, "Kembali", "Back"))
                    }
                },
                actions = {
                    if (uiState.step == ExistingSmartPlugStep.SELECTING) {
                        IconButton(onClick = viewModel::discover) {
                            Icon(Icons.Filled.Refresh, contentDescription = localized(language, "Pindai ulang", "Scan again"))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when (uiState.step) {
                ExistingSmartPlugStep.DISCOVERING, ExistingSmartPlugStep.ENROLLING -> Progress(
                    if (uiState.step == ExistingSmartPlugStep.DISCOVERING) {
                        localized(language, "Mencari SmartPlug di Wi-Fi rumah...", "Searching for SmartPlugs on home Wi-Fi...")
                    } else {
                        localized(language, "Mendaftarkan akses anggota...", "Registering member access...")
                    },
                )
                ExistingSmartPlugStep.SELECTING -> DeviceChooser(
                    devices = uiState.discoveredDevices,
                    onSelect = viewModel::selectDevice,
                    onRescan = viewModel::discover,
                )
                ExistingSmartPlugStep.ENTER_INVITATION -> InvitationCode(
                    device = uiState.selectedDevice,
                    code = uiState.invitationCode,
                    onCodeChanged = viewModel::setInvitationCode,
                    onEnroll = viewModel::enroll,
                )
                ExistingSmartPlugStep.SUCCESS -> Success(uiState.resultDeviceId, onFinished)
                ExistingSmartPlugStep.FAILED -> Failure(uiState.errorMessage, viewModel::retry, onCancel)
            }
        }
    }
}

@Composable
private fun Progress(message: String) = Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
) {
    OrbitLoadingIndicator()
    Text(message, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun DeviceChooser(
    devices: List<DiscoveredExistingSmartPlug>,
    onSelect: (String) -> Unit,
    onRescan: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(localized(language, "Pilih SmartPlug yang sudah aktif", "Choose an active SmartPlug"), style = MaterialTheme.typography.titleLarge)
        Text(
            localized(language, "Pemilik SmartPlug harus membuat kode undangan enam digit terlebih dahulu.", "The SmartPlug owner must create a six-digit invitation code first."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        if (devices.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
                Icon(Icons.Filled.Wifi, contentDescription = null)
                Text(localized(language, "Belum ada SmartPlug ditemukan", "No SmartPlug found"), modifier = Modifier.padding(top = 12.dp))
                Text(
                    localized(language, "Pastikan HP dan SmartPlug berada pada Wi-Fi rumah yang sama, lalu pindai ulang.", "Make sure the phone and SmartPlug are on the same home Wi-Fi, then scan again."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(onClick = onRescan, modifier = Modifier.padding(top = 16.dp)) { Text(localized(language, "Pindai ulang", "Scan again")) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(devices, key = { it.deviceId }) { device ->
                    SmartPlugCard(modifier = Modifier.clickable { onSelect(device.deviceId) }) {
                        Column {
                            Text("SmartPlug ${device.deviceId.takeLast(4)}", fontWeight = FontWeight.SemiBold)
                            Text(device.deviceId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvitationCode(
    device: DiscoveredExistingSmartPlug?,
    code: String,
    onCodeChanged: (String) -> Unit,
    onEnroll: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(localized(language, "Masukkan kode undangan", "Enter invitation code"), style = MaterialTheme.typography.titleLarge)
        Text(
            localized(language, "Masukkan kode enam digit dari pemilik untuk SmartPlug ${device?.deviceId ?: ""}.", "Enter the six-digit code from the owner for SmartPlug ${device?.deviceId ?: ""}."),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChanged,
            label = { Text(localized(language, "Kode undangan", "Invitation code")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        Button(
            onClick = onEnroll,
            enabled = code.length == 6,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text(localized(language, "Daftarkan SmartPlug", "Register SmartPlug")) }
    }
}

@Composable
private fun Success(deviceId: String?, onFinished: (String) -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.padding(top = 32.dp))
        Text(localized(language, "SmartPlug berhasil ditambahkan", "SmartPlug added"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text(
            localized(language, "Akses pada HP ini memakai kredensial anggota sendiri.", "This phone uses its own member credential."),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = { deviceId?.let(onFinished) }, enabled = deviceId != null, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Text(localized(language, "Lanjut ke monitoring", "Continue to monitoring"))
        }
    }
}

@Composable
private fun Failure(message: String?, onRetry: () -> Unit, onCancel: () -> Unit) {
    val language = LocalAppLanguage.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
        Text(message ?: localized(language, "Pendaftaran gagal", "Registration failed"), modifier = Modifier.padding(top = 48.dp))
        Row(modifier = Modifier.padding(top = 16.dp)) {
            Button(onClick = onRetry, modifier = Modifier.padding(end = 8.dp)) { Text(localized(language, "Coba lagi", "Try again")) }
            Button(onClick = onCancel) { Text(localized(language, "Batal", "Cancel")) }
        }
    }
}
