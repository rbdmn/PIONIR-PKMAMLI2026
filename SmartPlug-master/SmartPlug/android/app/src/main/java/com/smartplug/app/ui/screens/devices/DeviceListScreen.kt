package com.smartplug.app.ui.screens.devices

import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Power
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.RegisteredServer
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.ui.components.EmptyState
import com.smartplug.app.ui.components.PollWhileVisible
import com.smartplug.app.ui.components.PulsingDot
import com.smartplug.app.ui.components.SheetDialog
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.components.SwipeRevealRow
import com.smartplug.app.ui.components.entrance
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.ui.theme.AccentGreen
import com.smartplug.app.ui.theme.AccentRed
import com.smartplug.app.util.PollingCadence
import java.util.Locale

@Composable
fun DeviceListScreen(
    onOpenDevice: (String) -> Unit,
    onAddSmartPlug: () -> Unit,
    onAddExistingSmartPlug: () -> Unit,
    onAddServer: () -> Unit,
    viewModel: DeviceListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    var addDeviceChooserOpen by remember { mutableStateOf(false) }
    var unpairTarget by remember { mutableStateOf<SmartPlugDevice?>(null) }
    var resetTarget by remember { mutableStateOf<SmartPlugDevice?>(null) }
    var serverUnpairTarget by remember { mutableStateOf<RegisteredServer?>(null) }

    PollWhileVisible(intervalMs = PollingCadence.DEVICE_LIST.intervalMs) {
        viewModel.refreshAll()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localized(language, "Perangkat", "Devices")) },
                actions = {
                    IconButton(onClick = { addDeviceChooserOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = localized(language, "Tambah Perangkat", "Add Device"))
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.rows.isEmpty() && uiState.servers.isEmpty()) {
            EmptyState(
                title = localized(language, "Belum ada SmartPlug", "No SmartPlugs yet"),
                description = localized(language, "Tambahkan SmartPlug pertama Anda untuk mulai memantau energi.", "Add your first SmartPlug to begin monitoring energy."),
                modifier = Modifier.padding(padding),
                action = {
                    Button(onClick = { addDeviceChooserOpen = true }) {
                        Text(localized(language, "Tambah Perangkat", "Add Device"))
                    }
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(uiState.rows, key = { _, row -> row.device.deviceId }) { index, row ->
                    SwipeRevealRow(
                        modifier = Modifier.entrance(index),
                        actions = { close ->
                            ActionCell(localized(language, "Reset", "Reset"), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface) {
                                close(); resetTarget = row.device
                            }
                            ActionCell(localized(language, "Lepas", "Unpair"), AccentRed, Color.White) {
                                close(); unpairTarget = row.device
                            }
                        },
                    ) { open, close ->
                        DeviceRowCard(
                            row = row,
                            busy = row.device.deviceId in uiState.relayBusy,
                            onClick = { if (open) close() else onOpenDevice(row.device.deviceId) },
                            onToggleRelay = { on -> viewModel.setRelay(row.device, on) },
                        )
                    }
                }
                if (uiState.servers.isNotEmpty()) {
                    item {
                        Text(
                            "Server",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp).entrance(uiState.rows.size),
                        )
                    }
                    itemsIndexed(uiState.servers, key = { _, server -> "server-${server.serverId}" }) { index, server ->
                        SwipeRevealRow(
                            modifier = Modifier.entrance(uiState.rows.size + 1 + index),
                            actionsWidth = 78.dp,
                            actions = { close ->
                                ActionCell(localized(language, "Lepas", "Unpair"), AccentRed, Color.White) {
                                    close(); serverUnpairTarget = server
                                }
                            },
                        ) { open, close ->
                            ServerRowCard(server, onClick = { if (open) close() })
                        }
                    }
                }
            }
        }
    }

    if (addDeviceChooserOpen) {
        SheetDialog(
            onDismissRequest = { addDeviceChooserOpen = false },
            title = { Text(localized(language, "Tambah Perangkat", "Add Device")) },
            text = {
                Column {
                    ChooserRow(Icons.Filled.Power, "SmartPlug") { addDeviceChooserOpen = false; onAddSmartPlug() }
                    ChooserRow(Icons.Filled.Group, localized(language, "SmartPlug yang sudah ada", "Existing SmartPlug")) { addDeviceChooserOpen = false; onAddExistingSmartPlug() }
                    ChooserRow(Icons.Filled.Dns, "Server") { addDeviceChooserOpen = false; onAddServer() }
                }
            },
            confirmButton = {},
        )
    }

    unpairTarget?.let { device ->
        SheetDialog(
            onDismissRequest = { unpairTarget = null },
            title = { Text(localized(language, "Lepas ${device.displayName}?", "Unpair ${device.displayName}?")) },
            text = {
                Text(
                    localized(
                        language,
                        "Ini hanya menghapus profil, riwayat, dan kredensial SmartPlug dari HP ini. SmartPlug tidak di-factory-reset.",
                        "This only removes the SmartPlug profile, history, and credential from this phone. The SmartPlug is not factory-reset.",
                    ),
                )
            },
            dismissButton = { TextButton(onClick = { unpairTarget = null }) { Text(localized(language, "Batal", "Cancel")) } },
            confirmButton = {
                TextButton(onClick = { viewModel.unpair(device); unpairTarget = null }) {
                    Text(localized(language, "Lepas", "Unpair"), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    resetTarget?.let { device ->
        var typed by remember(device.deviceId) { mutableStateOf("") }
        SheetDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text(localized(language, "Reset pabrik ${device.displayName}?", "Factory reset ${device.displayName}?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        localized(
                            language,
                            "Wi-Fi, konfigurasi, sesi, dan kredensial perangkat akan dihapus. Perangkat akan reboot ke mode pemasangan. Ketik nama perangkat untuk melanjutkan.",
                            "Wi-Fi, configuration, sessions, and device credentials will be erased. The device reboots into pairing mode. Type the device name to continue.",
                        ),
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text(device.displayName) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { resetTarget = null }) { Text(localized(language, "Batal", "Cancel")) } },
            confirmButton = {
                TextButton(
                    enabled = typed.trim() == device.displayName,
                    onClick = { viewModel.factoryReset(device); resetTarget = null },
                ) { Text(localized(language, "Reset", "Reset"), color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    serverUnpairTarget?.let { server ->
        SheetDialog(
            onDismissRequest = { serverUnpairTarget = null },
            title = { Text(localized(language, "Lepas Server dari aplikasi?", "Unpair Server from this app?")) },
            text = {
                Text(
                    localized(
                        language,
                        "Ini hanya menghapus profil dan token server dari HP ini. Server dan SmartPlug yang terhubung tidak di-reset atau diubah.",
                        "This only removes the server profile and token from this phone. The server and connected SmartPlugs are not reset or changed.",
                    ),
                )
            },
            dismissButton = { TextButton(onClick = { serverUnpairTarget = null }) { Text(localized(language, "Batal", "Cancel")) } },
            confirmButton = {
                TextButton(onClick = { viewModel.unpairServer(server); serverUnpairTarget = null }) {
                    Text(localized(language, "Lepas", "Unpair"), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun ChooserRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RowScope.ActionCell(label: String, background: Color, textColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(78.dp)
            .fillMaxHeight()
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DeviceRowCard(row: DeviceRow, busy: Boolean, onClick: () -> Unit, onToggleRelay: (Boolean) -> Unit) {
    val language = LocalAppLanguage.current
    SmartPlugCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color = if (row.online) AccentGreen else AccentRed, active = row.online)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.device.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (row.online && row.measurement != null) "${String.format(Locale.US, "%.1f", row.measurement.activePowerW.coerceAtLeast(0.0))} W"
                    else localized(language, "Offline", "Offline"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (row.online && row.measurement != null) com.smartplug.app.ui.theme.SmartPlugMono else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.measurement?.let { m ->
                val adjustPercent = com.smartplug.app.util.LocalEnergyAdjustments.current[row.device.deviceId] ?: 0.0
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        com.smartplug.app.util.formatKwhValue(com.smartplug.app.util.applyEnergyAdjustment(m.energyWh.coerceAtLeast(0.0) / 1000.0, adjustPercent), com.smartplug.app.util.LocalKwhDecimals.current),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = com.smartplug.app.ui.theme.SmartPlugMono,
                    )
                    Text("kWh", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.online) {
                Spacer(Modifier.width(12.dp))
                val on = row.status?.relayState == RelayState.ON
                Switch(
                    checked = on,
                    onCheckedChange = { onToggleRelay(it) },
                    enabled = !busy && (row.status?.relayActuationEnabled ?: false),
                )
            }
        }
    }
}

@Composable
private fun ServerRowCard(server: RegisteredServer, onClick: () -> Unit) {
    SmartPlugCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server.host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
