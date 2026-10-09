package com.smartplug.app.ui.screens.diagnostics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.PowerOnPolicy
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.util.formatUptime

/** Hidden screen (opened from Settings > About with a tap sequence and a password). Read-only. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    val clipboard = LocalClipboardManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localized(language, "Diagnostik", "Diagnostics")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = localized(language, "Kembali", "Back")) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val device = state.selected
            if (device == null) {
                Text(localized(language, "Belum ada SmartPlug tersimpan.", "No saved SmartPlug."))
                return@Column
            }
            if (state.devices.size > 1) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.devices.forEach { item ->
                        FilterChip(selected = item.deviceId == device.deviceId, onClick = { viewModel.select(item.deviceId) }, label = { Text(item.displayName) })
                    }
                }
            }
            val dash = "–"
            val status = state.status
            val test = state.test
            SmartPlugCard {
                Text(device.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Line(
                    localized(language, "Koneksi", "Connection"),
                    when {
                        state.running -> localized(language, "Memeriksa…", "Checking…")
                        test == null -> dash
                        test.reachable -> localized(language, "Tersambung (${test.latencyMs} ms)", "Connected (${test.latencyMs} ms)")
                        else -> test.failureReason ?: dash
                    },
                )
                Line(
                    localized(language, "Cara terhubung", "Connected via"),
                    if (device.integrationMode == IntegrationMode.SERVER) localized(language, "Lewat server", "Through server") else localized(language, "Langsung", "Direct"),
                )
                Line(
                    localized(language, "Alamat", "Address"),
                    (if (device.integrationMode == IntegrationMode.SERVER) device.serverHost else device.lanIp) ?: dash,
                )
                Line(localized(language, "Sinyal Wi-Fi HP", "Phone Wi-Fi signal"), state.phoneSignalPercent?.let { "$it%" } ?: dash)
                Line(
                    localized(language, "Data terakhir", "Last data"),
                    status?.takeIf { it.hasSample }?.let { localized(language, "${it.sampleAgeMs / 1000} detik lalu", "${it.sampleAgeMs / 1000} s ago") } ?: dash,
                )
                Line(localized(language, "Versi firmware", "Firmware"), status?.firmwareVersion ?: dash)
                Line(localized(language, "Menyala sejak", "Running for"), if (status?.uptimeSeconds != null) formatUptime(status.uptimeSeconds) else dash)
                Line(localized(language, "Alasan restart", "Restart reason"), status?.resetReason ?: dash)
                Line(localized(language, "Jumlah boot", "Boot count"), status?.bootCount?.toString() ?: dash)
                Line(
                    localized(language, "Proteksi beban", "Load protection"),
                    status?.protection?.let {
                        (if (it.enabled) localized(language, "Aktif", "On") else localized(language, "Nonaktif", "Off")) +
                            if (it.tripped) localized(language, " (sempat memutus)", " (has tripped)") else ""
                    } ?: dash,
                )
                Line(
                    localized(language, "Saat listrik kembali", "When power returns"),
                    when (status?.powerOnPolicy) {
                        PowerOnPolicy.OFF -> localized(language, "Tetap mati", "Stay off")
                        PowerOnPolicy.LAST -> localized(language, "Seperti sebelum mati", "Like before")
                        PowerOnPolicy.ON -> localized(language, "Selalu menyala", "Always on")
                        null -> dash
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::runTest, enabled = !state.running) { Text(localized(language, "Tes koneksi", "Test connection")) }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(viewModel.report())) }) {
                    Text(localized(language, "Salin laporan", "Copy report"))
                }
            }
            Text(
                localized(language, "Strip (–) berarti firmware belum melaporkannya.", "A dash (–) means the firmware does not report it."),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
