package com.smartplug.app.ui.screens.settings

import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.BuildConfig
import com.smartplug.app.data.local.AppThemeMode
import com.smartplug.app.data.local.TapSoundStyle
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.localization.AppLanguage
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.domain.model.RegisteredServer
import com.smartplug.app.domain.model.SmartPlugDevice

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onResetCompleted: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val appResetState by viewModel.appResetState.collectAsStateWithLifecycle()
    val registeredDevices by viewModel.registeredDevices.collectAsStateWithLifecycle()
    val registeredServers by viewModel.registeredServers.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    var showAppResetConfirmation by remember { mutableStateOf(false) }
    var smartPlugPendingUnpair by remember { mutableStateOf<SmartPlugDevice?>(null) }
    var serverPendingUnpair by remember { mutableStateOf<RegisteredServer?>(null) }

    // A reset deletes the credentials backing any device-detail screen.  Return to the
    // safe device list instead of leaving an obsolete detail route that can show
    // `missing_owner_token` after a successful reset.
    LaunchedEffect(appResetState.completed) {
        if (appResetState.completed) onResetCompleted()
    }

    Scaffold(topBar = { TopAppBar(title = { androidx.compose.material3.Text(localized(language, "Pengaturan", "Settings")) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmartPlugCard {
                Text(localized(language, "Tampilan", "Appearance"), style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    AppThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = AppThemeMode.entries.size),
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                        ) {
                            Text(themeModeLabel(mode, language))
                        }
                    }
                }
            }

            SmartPlugCard {
                Text(localized(language, "Bahasa", "Language"), style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    AppLanguage.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = AppLanguage.entries.size),
                            selected = settings.language == item,
                            onClick = { viewModel.setLanguage(item) },
                        ) { Text(if (item == AppLanguage.INDONESIAN) "Bahasa Indonesia" else "English") }
                    }
                }
            }

            SmartPlugCard {
                SettingsToggleRow(
                    label = localized(language, "Suara tap", "Tap sound"),
                    checked = settings.soundEnabled,
                    onCheckedChange = viewModel::setSoundEnabled,
                )
                if (settings.soundEnabled) {
                    Text(
                        localized(language, "Gaya suara (ketuk untuk mencoba)", "Sound style (tap to preview)"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        TapSoundStyle.entries.forEach { style ->
                            androidx.compose.material3.FilterChip(
                                selected = settings.soundStyle == style,
                                onClick = { viewModel.setSoundStyle(style) },
                                label = { Text(if (language == AppLanguage.INDONESIAN) style.labelId else style.labelEn) },
                            )
                        }
                    }
                }
            }

            SmartPlugCard {
                KwhFormatCard(
                    decimals = settings.kwhDecimals,
                    onChange = viewModel::setKwhDecimals,
                    language = language,
                )
            }

            SmartPlugCard {
                SettingsToggleRow(
                    label = localized(language, "Tampilkan ringkasan harian di Beranda", "Show daily summary on Home"),
                    checked = settings.dailySummaryEnabled,
                    onCheckedChange = viewModel::setDailySummaryEnabled,
                )
            }

            SmartPlugCard {
                Text(localized(language, "Estimasi biaya", "Cost estimate"), style = MaterialTheme.typography.titleMedium)
                SettingsToggleRow(
                    label = localized(language, "Tampilkan biaya (Rp)", "Show cost (Rp)"),
                    checked = settings.costEnabled,
                    onCheckedChange = viewModel::setCostEnabled,
                )
                // Until the user types, show the saved tariff; afterwards show exactly what they typed.
                var tariffText by remember { mutableStateOf<String?>(null) }
                val shown = tariffText ?: tariffToText(settings.tariffPerKwh)
                val parsed = shown.toDoubleOrNull()
                val valid = parsed != null && parsed.isFinite() && parsed > 0.0
                androidx.compose.material3.OutlinedTextField(
                    value = shown,
                    onValueChange = { raw ->
                        val cleaned = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
                        if (cleaned.count { it == '.' } > 1) return@OutlinedTextField
                        tariffText = cleaned
                        val value = cleaned.toDoubleOrNull()
                        viewModel.setTariffPerKwh(if (value != null && value > 0.0) value else 0.0)
                    },
                    label = { Text(localized(language, "Tarif per kWh (Rp)", "Tariff per kWh (Rp)")) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    supportingText = {
                        Text(
                            if (valid) localized(language, "Biaya = kWh × tarif.", "Cost = kWh × tariff.")
                            else localized(language, "Isi tarif lebih dari 0 agar estimasi aktif.", "Enter a tariff above 0 to enable the estimate."),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            var showAdjustDialog by remember { mutableStateOf(false) }
            SmartPlugCard {
                Text("Advanced", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .clickable { showAdjustDialog = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(localized(language, "Penyesuaian kWh", "kWh adjustment"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            localized(language, "Koreksi tampilan kWh per perangkat (hanya di HP ini)", "Correct the displayed kWh per device (this phone only)"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (showAdjustDialog) {
                KwhAdjustmentDialog(
                    devices = registeredDevices,
                    adjustments = settings.kwhAdjustments,
                    loadRawKwh = { viewModel.rawKwh(it) },
                    onSet = viewModel::setKwhAdjustPercent,
                    onDismiss = { showAdjustDialog = false },
                )
            }

            SmartPlugCard {
                Text(localized(language, "Perangkat tersimpan", "Saved devices"), style = MaterialTheme.typography.titleMedium)
                Text(
                    localized(
                        language,
                        "Daftar utama tetap hanya menampilkan SmartPlug. Kelola profil SmartPlug dan ServerSmartPlug dari sini.",
                        "The main device list stays SmartPlug-only. Manage SmartPlug and ServerSmartPlug profiles here.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (registeredDevices.isEmpty() && registeredServers.isEmpty()) {
                    Text(localized(language, "Belum ada perangkat tersimpan.", "No saved devices."), modifier = Modifier.padding(top = 12.dp))
                } else {
                    if (registeredDevices.isNotEmpty()) {
                        Text(
                            "SmartPlug",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    registeredDevices.forEach { device ->
                        SavedProfileRow(
                            title = device.displayName,
                            subtitle = localized(
                                language,
                                "SmartPlug · ${if (device.integrationMode.name == "SERVER") "Server" else "Direct"}",
                                "SmartPlug · ${if (device.integrationMode.name == "SERVER") "Server" else "Direct"}",
                            ),
                            onUnpair = { smartPlugPendingUnpair = device },
                        )
                    }
                    if (registeredServers.isNotEmpty()) {
                        Text(
                            "Server",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    registeredServers.forEach { server ->
                        SavedProfileRow(
                            title = server.displayName,
                            subtitle = "Server · ${server.host}",
                            onUnpair = { serverPendingUnpair = server },
                        )
                    }
                }
            }

            SmartPlugCard {
                Text(localized(language, "Reset aplikasi", "Reset app"), style = MaterialTheme.typography.titleMedium)
                Text(
                    localized(
                        language,
                        "Factory reset seluruh SmartPlug dan ServerSmartPlug yang terdaftar, lalu hapus perangkat, riwayat, nama beban, dan kredensial dari aplikasi.",
                        "Factory-reset every registered SmartPlug and ServerSmartPlug, then remove devices, history, load names, and credentials from the app.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                Button(
                    onClick = { showAppResetConfirmation = true },
                    enabled = !appResetState.isRunning,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) {
                    Text(if (appResetState.isRunning) localized(language, "Mereset…", "Resetting…") else localized(language, "Reset aplikasi & perangkat", "Reset app & devices"))
                }
                appResetState.summary?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }

            SmartPlugCard {
                SettingsToggleRow(
                    label = localized(language, "Getaran (haptic)", "Haptic feedback"),
                    checked = settings.hapticEnabled,
                    onCheckedChange = viewModel::setHapticEnabled,
                )
            }

            val tapSequence = remember { com.smartplug.app.util.TapSequence() }
            var showDiagnosticsGate by remember { mutableStateOf(false) }
            SmartPlugCard {
                // Five quick taps on the title open the hidden diagnostics gate.
                Text(
                    localized(language, "Tentang", "About"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { if (tapSequence.tap()) showDiagnosticsGate = true },
                )
                Text(
                    "SmartPlug ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showDiagnosticsGate) {
                DiagnosticsGateDialog(
                    onDismiss = { showDiagnosticsGate = false },
                    onUnlocked = { showDiagnosticsGate = false; onOpenDiagnostics() },
                )
            }
        }
    }

    if (showAppResetConfirmation) {
        AppResetConfirmationDialog(
            language = language,
            onDismiss = { showAppResetConfirmation = false },
            onConfirm = {
                showAppResetConfirmation = false
                viewModel.resetAppAndRegisteredDevices()
            },
        )
    }
    smartPlugPendingUnpair?.let { device ->
        LocalUnpairConfirmationDialog(
            language = language,
            title = localized(language, "Lepas SmartPlug dari aplikasi?", "Unpair SmartPlug from this app?"),
            description = localized(
                language,
                "Ini hanya menghapus profil, riwayat, dan kredensial SmartPlug dari HP ini. SmartPlug tidak di-factory-reset.",
                "This only removes the SmartPlug profile, history, and credential from this phone. The SmartPlug is not factory-reset.",
            ),
            onConfirm = { viewModel.unpairSmartPlug(device); smartPlugPendingUnpair = null },
            onDismiss = { smartPlugPendingUnpair = null },
        )
    }
    serverPendingUnpair?.let { server ->
        LocalUnpairConfirmationDialog(
            language = language,
            title = localized(language, "Lepas ServerSmartPlug dari aplikasi?", "Unpair ServerSmartPlug from this app?"),
            description = localized(
                language,
                "Ini hanya menghapus profil dan token server dari HP ini. Server dan SmartPlug yang terhubung tidak di-reset atau diubah.",
                "This only removes the server profile and token from this phone. The server and connected SmartPlugs are not reset or changed.",
            ),
            onConfirm = { viewModel.unpairServer(server); serverPendingUnpair = null },
            onDismiss = { serverPendingUnpair = null },
        )
    }
}

@Composable
private fun SavedProfileRow(title: String, subtitle: String, onUnpair: () -> Unit) {
    val language = LocalAppLanguage.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onUnpair) {
            Text(localized(language, "Lepas", "Unpair"), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun LocalUnpairConfirmationDialog(
    language: AppLanguage,
    title: String,
    description: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(localized(language, "Lepas", "Unpair"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
    )
}

@Composable
private fun AppResetConfirmationDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Reset aplikasi, SmartPlug, dan server?", "Reset app, SmartPlug, and servers?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    localized(
                        language,
                        "Setiap SmartPlug dan ServerSmartPlug yang masih dapat dihubungi akan menerima factory reset dengan konfirmasi tiga tahap. Target offline tidak dihapus agar dapat dicoba lagi.",
                        "Each reachable SmartPlug and ServerSmartPlug receives a triple-confirmed factory reset. Offline targets remain in the app so they can be retried.",
                    ),
                )
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text(localized(language, "Ketik RESET APP", "Type RESET APP")) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = phrase == "RESET APP") {
                Text(localized(language, "Reset semua", "Reset all"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) }
        },
    )
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun tariffToText(tariff: Double): String = when {
    tariff <= 0.0 -> ""
    tariff == Math.floor(tariff) -> tariff.toLong().toString()
    else -> tariff.toString()
}

private fun themeModeLabel(mode: AppThemeMode, language: AppLanguage): String = when (mode) {
    AppThemeMode.SYSTEM -> localized(language, "Sistem", "System")
    AppThemeMode.LIGHT -> localized(language, "Terang", "Light")
    AppThemeMode.DARK -> localized(language, "Gelap", "Dark")
}

/** Decimal-places picker for every kWh value, with a live preview instead of a plain list of buttons. */
@Composable
private fun KwhFormatCard(decimals: Int, onChange: (Int) -> Unit, language: AppLanguage) {
    val sample = 12.345678
    Text(localized(language, "Format kWh", "kWh format"), style = MaterialTheme.typography.titleMedium)
    Text(
        localized(language, "Jumlah angka di belakang koma untuk semua nilai kWh.", "Number of decimals for every kWh value."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    // Live preview
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            com.smartplug.app.util.formatKwhValue(sample, decimals),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            fontFamily = com.smartplug.app.ui.theme.SmartPlugMono,
        )
        Text(
            " kWh",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Text(
        com.smartplug.app.util.KwhFormat.pattern(decimals),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontFamily = com.smartplug.app.ui.theme.SmartPlugMono,
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.FilledTonalIconButton(onClick = { onChange(decimals - 1) }, enabled = decimals > 0) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Remove, contentDescription = localized(language, "Kurangi", "Fewer"))
        }
        androidx.compose.material3.Slider(
            value = decimals.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..com.smartplug.app.util.KwhFormat.MAX_DECIMALS.toFloat(),
            steps = com.smartplug.app.util.KwhFormat.MAX_DECIMALS - 1,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        androidx.compose.material3.FilledTonalIconButton(
            onClick = { onChange(decimals + 1) },
            enabled = decimals < com.smartplug.app.util.KwhFormat.MAX_DECIMALS,
        ) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Add, contentDescription = localized(language, "Tambah", "More"))
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        (0..com.smartplug.app.util.KwhFormat.MAX_DECIMALS).forEach { value ->
            Text(
                value.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (value == decimals) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (value == decimals) androidx.compose.ui.text.font.FontWeight.Bold else null,
            )
        }
    }
    Text(
        localized(
            language,
            "Dipakai di Beranda, daftar perangkat, detail SmartPlug, riwayat, tren live, dan Storage.",
            "Used on Home, the device list, SmartPlug detail, history, live trend and Storage.",
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Password prompt in front of the hidden diagnostics. Wrong passwords wait a second; five in a row lock it for 30 s. */
@Composable
internal fun DiagnosticsGateDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val language = LocalAppLanguage.current
    val limiter = remember { com.smartplug.app.util.AttemptLimiter() }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Diagnostik", "Diagnostics")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; message = null },
                    label = { Text(localized(language, "Password", "Password")) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = message != null,
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && !checking,
                onClick = {
                    if (!limiter.canTry()) {
                        message = localized(language, "Coba lagi sebentar lagi.", "Try again in a moment.")
                        return@TextButton
                    }
                    checking = true
                    scope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            com.smartplug.app.util.DiagnosticsGate.verify(password)
                        }
                        if (ok) {
                            limiter.recordSuccess()
                            onUnlocked()
                        } else {
                            limiter.recordFailure()
                            kotlinx.coroutines.delay(1_000)
                            message = if (limiter.canTry()) localized(language, "Password salah.", "Wrong password.")
                            else localized(language, "Terlalu banyak percobaan. Tunggu 30 detik.", "Too many tries. Wait 30 seconds.")
                            password = ""
                        }
                        checking = false
                    }
                },
            ) { Text(localized(language, "Buka", "Open")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
    )
}
