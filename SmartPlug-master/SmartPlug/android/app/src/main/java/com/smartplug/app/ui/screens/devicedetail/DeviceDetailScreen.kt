package com.smartplug.app.ui.screens.devicedetail

import com.smartplug.app.ui.components.HoldToConfirmButton
import androidx.compose.material.icons.filled.Dns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.smartplug.app.ui.components.SmartPlugCard
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import com.smartplug.app.ui.components.SheetDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.ElectricalMeasurement
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.ui.components.AnimatedDecimal
import com.smartplug.app.ui.components.FullScreenLoading
import com.smartplug.app.ui.components.RelayPowerButton
import com.smartplug.app.ui.components.entrance
import com.smartplug.app.ui.components.OrbitLoadingIndicator
import com.smartplug.app.ui.components.LiveMeasurementChart
import com.smartplug.app.ui.components.PollWhileVisible
import com.smartplug.app.ui.components.StatusPill
import com.smartplug.app.ui.components.rememberTapFeedback
import com.smartplug.app.ui.components.relayStatusColor
import com.smartplug.app.ui.components.relayStatusLabel
import com.smartplug.app.ui.components.shouldShowScheduleCountdown
import com.smartplug.app.ui.theme.AccentGreen
import com.smartplug.app.ui.theme.AccentRed
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.util.PollingCadence
import com.smartplug.app.util.applyEnergyAdjustment
import kotlinx.coroutines.delay

@Composable
fun DeviceDetailScreen(
    deviceId: String,
    onOpenHistory: () -> Unit,
    onOpenSchedule: () -> Unit,
    onBack: () -> Unit,
    onDeviceRemoved: () -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    val adjustPercent = com.smartplug.app.util.LocalEnergyAdjustments.current[deviceId] ?: 0.0
    val tapFeedback = rememberTapFeedback()
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    var showProtectionDialog by remember { mutableStateOf(false) }
    var showPowerPolicyDialog by remember { mutableStateOf(false) }
    var confirmTripRestart by remember { mutableStateOf(false) }
    val limitToastAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    var limitToastShown by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.limitBlockedNonce) {
        if (uiState.limitBlockedNonce > 0) {
            limitToastShown = true
            limitToastAlpha.snapTo(1f)
            delay(1300)
            limitToastAlpha.animateTo(0f, androidx.compose.animation.core.tween(500))
            limitToastShown = false
        }
    }
    var showServerConnectionDialog by remember { mutableStateOf(false) }
    var showNameLoadDialog by remember { mutableStateOf(false) }
    var showMemberInvitationDialog by remember { mutableStateOf(false) }
    var showMemberManagementDialog by remember { mutableStateOf(false) }
    var memberPendingRevocation by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1_000) } }

    // Live (1 s) monitoring is always on; there is no longer a switch for it.
    LaunchedEffect(Unit) { viewModel.setLiveMode(true) }

    LaunchedEffect(uiState.isRemoved) {
        if (uiState.isRemoved) onDeviceRemoved()
    }

    // ServerSmartPlug keeps the latest MQTT sample in RAM at 500 ms.  Only the
    // visible server-backed detail monitor uses that faster REST cadence; other
    // screens remain lower-frequency to avoid unnecessary network traffic.
    val cadence = if (uiState.device?.integrationMode == IntegrationMode.SERVER) {
        PollingCadence.SERVER_MONITORING
    } else if (uiState.liveModeRequested) {
        PollingCadence.LIVE
    } else {
        PollingCadence.NORMAL
    }
    PollWhileVisible(
        intervalMs = cadence.intervalMs,
        key = uiState.liveModeRequested to uiState.device?.integrationMode,
        fixedRate = uiState.device?.integrationMode == IntegrationMode.SERVER,
    ) {
        viewModel.refresh()
    }

    val device = uiState.device
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.displayName ?: "SmartPlug") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = localized(language, "Kembali", "Back")) }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = localized(language, "Riwayat energi", "Energy history"))
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = localized(language, "Menu", "Menu"))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(localized(language, "Ganti nama", "Rename")) }, onClick = {
                            showMenu = false
                            showRenameDialog = true
                        })
                        DropdownMenuItem(
                            text = { Text(localized(language, "Timer", "Timer")) },
                            onClick = { showMenu = false; showTimerDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(localized(language, "Jadwal", "Schedule")) },
                            onClick = { showMenu = false; onOpenSchedule() },
                        )
                        DropdownMenuItem(
                            text = { Text(localized(language, "Set Limit", "Set Limit")) },
                            onClick = { showMenu = false; showLimitDialog = true },
                        )
                        // Owner-only, and only for firmware that reports these settings.
                        if (uiState.canManageServer && uiState.status?.protection != null) {
                            DropdownMenuItem(
                                text = { Text(localized(language, "Proteksi beban", "Load protection")) },
                                onClick = { showMenu = false; showProtectionDialog = true },
                            )
                        }
                        if (uiState.canManageServer && uiState.status?.powerOnPolicy != null) {
                            DropdownMenuItem(
                                text = { Text(localized(language, "Saat listrik kembali", "When power returns")) },
                                onClick = { showMenu = false; showPowerPolicyDialog = true },
                            )
                        }
                        if (uiState.canManageServer) {
                            DropdownMenuItem(
                                text = { Text(localized(language, "Koneksi Server", "Server Connection")) },
                                onClick = { showMenu = false; showServerConnectionDialog = true },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(localized(language, "Tambah HP", "Add phone")) },
                            onClick = {
                                showMenu = false
                                showMemberInvitationDialog = true
                                viewModel.createMemberInvitation()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(localized(language, "Kelola HP", "Manage phones")) },
                            onClick = {
                                showMenu = false
                                showMemberManagementDialog = true
                                viewModel.loadManagedMembers()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(localized(language, "Reset kWh", "Reset kWh")) },
                            onClick = { showMenu = false; confirmAction = ConfirmAction.RESET_ENERGY },
                        )
                        DropdownMenuItem(
                            text = { Text(localized(language, "Reset pabrik", "Factory reset")) },
                            onClick = { showMenu = false; confirmAction = ConfirmAction.FACTORY_RESET },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (device == null) {
            FullScreenLoading(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Only surface connection pills when something is wrong; "Live" lives in the hero.
            uiState.status?.takeIf { !it.fresh || !it.hasSample || !it.wifiConnected }?.let {
                Box(Modifier.entrance(0)) { StatusSection(uiState) }
            }
            LoadWarningBanners(
                currentA = uiState.measurement?.currentA,
                overcurrentFlag = uiState.status?.overcurrentWarning,
                tripped = uiState.status?.protection?.tripped == true,
                canRestart = uiState.canManageServer,
                onRestart = { confirmTripRestart = true },
            )
            uiState.measurement?.let { measurement ->
                Box(Modifier.entrance(1)) {
                    MonitoringHero(
                        measurement = measurement,
                        status = uiState.status,
                        serverBacked = device.integrationMode == IntegrationMode.SERVER,
                        adjustPercent = adjustPercent,
                    )
                }
            }

            uiState.measurement?.let { measurement ->
                if (uiState.limit.enabled && uiState.limit.kwh > 0.0) {
                    val kwhNow = applyEnergyAdjustment(measurement.energyWh.coerceAtLeast(0.0) / 1000.0, adjustPercent)
                    val progress = uiState.limit.progress(kwhNow)
                    val reached = uiState.limit.isReached(kwhNow)
                    SmartPlugCard(modifier = Modifier.entrance(2)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                localized(language, "Limit ${formatLimit(uiState.limit.kwh)} kWh", "Limit ${formatLimit(uiState.limit.kwh)} kWh"),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (reached) localized(language, "Tercapai", "Reached") else "${Math.round(progress * 100)}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (reached) AccentRed else MaterialTheme.colorScheme.primary,
                            )
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { progress.toFloat() },
                            color = if (reached) AccentRed else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }

            Column(Modifier.entrance(2), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RelaySection(
                    relayState = uiState.status?.relayState ?: RelayState.UNKNOWN,
                    relayUiState = uiState.relayUiState,
                    relayActuationEnabled = uiState.status?.relayActuationEnabled ?: true,
                    onSetRelay = { targetOn ->
                        tapFeedback.onConfirm()
                        viewModel.setRelay(targetOn)
                    },
                )
            }

            // Timer and next schedule only appear while they exist.
            val timerStatus = uiState.status?.takeIf { it.timerRemainingMs > 0 }
            val scheduleStatus = uiState.status?.takeIf { it.scheduleRemainingMs > 0 && it.scheduleTurnOn != null }
            val scheduleRemainingMs = scheduleStatus?.let { countdownRemaining(it.scheduleRemainingMs, uiState.statusReceivedAtMs, nowMs) }
            val showSchedule = scheduleRemainingMs != null && shouldShowScheduleCountdown(scheduleRemainingMs)
            if (timerStatus != null || showSchedule) {
                Row(Modifier.fillMaxWidth().entrance(3), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    timerStatus?.let { status ->
                        // An armed timer is intentionally waiting for relay ON, so its duration is
                        // not running yet; only a running timer counts down locally.
                        val remainingMs = displayTimerRemaining(
                            sourceMs = status.timerRemainingMs,
                            armed = status.timerArmed,
                            receivedAtMs = uiState.statusReceivedAtMs,
                            nowMs = nowMs,
                        )
                        QuickInfoCard(
                            icon = Icons.Filled.Timer,
                            value = formatDuration(remainingMs),
                            caption = if (status.timerArmed) localized(language, "Mulai saat relay ON", "Starts when relay is ON") else localized(language, "Timer aktif", "Timer active"),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (showSchedule && scheduleStatus != null && scheduleRemainingMs != null) {
                        QuickInfoCard(
                            icon = Icons.Filled.CalendarMonth,
                            value = formatDuration(scheduleRemainingMs),
                            caption = localized(language, "Berikutnya: ${if (scheduleStatus.scheduleTurnOn == true) "ON" else "OFF"}", "Next: ${if (scheduleStatus.scheduleTurnOn == true) "ON" else "OFF"}"),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Voltage / current / power factor sit right after the timer and schedule cards.
            uiState.measurement?.let { measurement ->
                Box(Modifier.entrance(4)) { ElectricalStatCard(measurement) }
            }

            uiState.detectedLoadName?.let { name ->
                Text(localized(language, "Perkiraan beban aktif: $name", "Estimated active load: $name"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            if (uiState.canSaveLoadSignature) {
                TextButton(onClick = { showNameLoadDialog = true }) {
                    Text(localized(language, "Beri nama beban aktif", "Name active load"))
                }
            }
            if (uiState.vampireEnergySuspected) {
                Text(
                    localized(language, "Kemungkinan energi vampire: daya rendah dan stabil selama 15 menit.", "Possible standby / vampire energy: low, stable power has continued for 15 minutes."),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (uiState.status?.relayState == RelayState.ON && !uiState.canSaveLoadSignature) {
                Text(localized(language, "Nama beban tersedia setelah arus stabil selama 1 menit.", "Load naming is available after current is stable for one minute."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            LiveMeasurementChart(
                points = uiState.livePoints,
                onClear = viewModel::clearLiveGraph,
                energyAdjustPercent = adjustPercent,
                modifier = Modifier.fillMaxWidth().entrance(5),
            )


            uiState.lastError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            initialName = device?.displayName.orEmpty(),
            onConfirm = { newName ->
                viewModel.renameDevice(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    confirmAction?.let { action ->
        TripleConfirmDialog(
            action = action,
            deviceName = device?.displayName.orEmpty(),
            onComplete = {
                when (action) {
                    ConfirmAction.RESET_ENERGY -> viewModel.resetEnergy()
                    ConfirmAction.FACTORY_RESET -> viewModel.factoryReset()
                }
                confirmAction = null
            },
            onDismiss = { confirmAction = null },
        )
    }
    if (limitToastShown) {
        androidx.compose.ui.window.Popup(
            alignment = Alignment.BottomCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, -300),
        ) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.graphicsLayer(alpha = limitToastAlpha.value),
            ) {
                Text(
                    localized(language, "Tidak bisa menyalakan: batas kWh tercapai", "Can't turn on: kWh limit reached"),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
    if (showLimitDialog) {
        SetLimitDialog(
            currentKwh = applyEnergyAdjustment((uiState.measurement?.energyWh ?: 0.0).coerceAtLeast(0.0) / 1000.0, adjustPercent),
            limit = uiState.limit,
            onSave = { enabled, kwh ->
                tapFeedback.onConfirm()
                viewModel.setEnergyLimit(enabled, kwh)
                showLimitDialog = false
            },
            onDismiss = { showLimitDialog = false },
        )
    }
    if (showTimerDialog) {
        TimerDialog(
            initialRemainingMs = uiState.status?.timerRemainingMs ?: 0L,
            onApply = { hours, minutes, seconds, onSuccess ->
                tapFeedback.onConfirm()
                viewModel.applyTimer(hours, minutes, seconds, onSuccess)
            },
            onReset = { onSuccess ->
                tapFeedback.onConfirm()
                viewModel.resetTimer(onSuccess)
            },
            onDismiss = { showTimerDialog = false },
        )
    }
    if (showNameLoadDialog) {
        NameLoadDialog(
            onSave = { name -> viewModel.saveLoadSignature(name); showNameLoadDialog = false },
            onDismiss = { showNameLoadDialog = false },
        )
    }
    if (showMemberInvitationDialog) {
        MemberInvitationDialog(
            invitation = uiState.memberInvitation,
            expiresAtMs = uiState.memberInvitationExpiresAtMs,
            nowMs = nowMs,
            isLoading = uiState.isCreatingMemberInvitation,
            error = uiState.memberInvitationError,
            onRetry = viewModel::createMemberInvitation,
            onDismiss = { showMemberInvitationDialog = false },
        )
    }
    if (showMemberManagementDialog) {
        MemberManagementDialog(
            members = uiState.managedMembers,
            isLoading = uiState.isLoadingManagedMembers,
            revokingMemberId = uiState.revokingMemberId,
            error = uiState.lastError,
            onRefresh = viewModel::loadManagedMembers,
            onRequestRevoke = { memberPendingRevocation = it },
            onDismiss = { showMemberManagementDialog = false },
        )
    }
    memberPendingRevocation?.let { credentialId ->
        SheetDialog(
            onDismissRequest = { memberPendingRevocation = null },
            title = { Text(localized(language, "Cabut akses HP?", "Revoke phone access?")) },
            text = { Text(localized(language, "HP ini tidak akan bisa lagi membaca atau mengontrol SmartPlug sampai didaftarkan ulang.", "This phone will no longer be able to read or control this SmartPlug until it is enrolled again.")) },
            confirmButton = {
                TextButton(onClick = {
                    tapFeedback.onConfirm()
                    viewModel.revokeManagedMember(credentialId)
                    memberPendingRevocation = null
                }) { Text(localized(language, "Cabut akses", "Revoke"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { memberPendingRevocation = null }) { Text(localized(language, "Batal", "Cancel")) } },
        )
    }
    if (uiState.serverDisconnectFailed) {
        SheetDialog(
            onDismissRequest = viewModel::dismissForceDisconnect,
            title = { Text(localized(language, "Putuskan paksa?", "Force disconnect?")) },
            text = {
                Text(
                    localized(
                        language,
                        "Server tidak bisa dihubungi atau menolak. SmartPlug tetap dikembalikan ke mode Direct, tetapi timer/jadwal yang masih tersimpan di server untuk SmartPlug ini tidak dibersihkan.",
                        "The server is unreachable or refused. The SmartPlug is still returned to Direct mode, but any timer/schedule the server holds for it is not cleared.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.disconnectFromServer(force = true) }) {
                    Text(localized(language, "Putuskan paksa", "Force disconnect"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissForceDisconnect) { Text(localized(language, "Batal", "Cancel")) } },
        )
    }
    if (showProtectionDialog) {
        ProtectionDialog(
            enabled = uiState.status?.protection?.enabled == true,
            error = uiState.lastError,
            onChange = { viewModel.setProtection(it) },
            onDismiss = { showProtectionDialog = false },
        )
    }
    if (showPowerPolicyDialog) {
        PowerPolicyDialog(
            current = uiState.status?.powerOnPolicy ?: com.smartplug.app.domain.model.PowerOnPolicy.OFF,
            currentDelaySeconds = uiState.status?.restoreDelaySeconds ?: 3,
            error = uiState.lastError,
            onSave = { policy, delay -> viewModel.setPowerPolicy(policy, delay) { showPowerPolicyDialog = false } },
            onDismiss = { showPowerPolicyDialog = false },
        )
    }
    if (confirmTripRestart) {
        SheetDialog(
            onDismissRequest = { confirmTripRestart = false },
            title = { Text(localized(language, "Nyalakan lagi?", "Turn on again?")) },
            text = {
                Text(localized(
                    language,
                    "Pastikan beban yang tersambung sudah dikurangi, lalu nyalakan lagi.",
                    "Make sure you have reduced what is plugged in, then turn it on again.",
                ))
            },
            confirmButton = {
                TextButton(onClick = { confirmTripRestart = false; viewModel.setRelay(true) }) {
                    Text(localized(language, "Nyalakan", "Turn on"))
                }
            },
            dismissButton = { TextButton(onClick = { confirmTripRestart = false }) { Text(localized(language, "Batal", "Cancel")) } },
        )
    }
    if (showServerConnectionDialog && device != null) {
        ServerConnectionDialog(
            device = device,
            savedServers = uiState.registeredServers,
            onSelectSaved = viewModel::connectSavedServer,
            onDisconnect = {
                viewModel.disconnectFromServer()
                showServerConnectionDialog = false
            },
            onDismiss = { showServerConnectionDialog = false },
        )
    }
}

@Composable
private fun MemberInvitationDialog(
    invitation: com.smartplug.app.domain.model.MemberInvitation?,
    expiresAtMs: Long,
    nowMs: Long,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val remainingSeconds = ((expiresAtMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Tambahkan HP", "Add phone")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    isLoading -> OrbitLoadingIndicator(modifier = Modifier.size(36.dp))
                    invitation != null && remainingSeconds > 0L -> {
                        Text(localized(language, "Masukkan kode ini di HP kedua melalui Tambahkan SmartPlug yang sudah ada.", "Enter this code on the second phone through Add an existing SmartPlug."))
                        Text(
                            invitation.code.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                .padding(vertical = 16.dp),
                        )
                        Text(localized(language, "Berlaku ${remainingSeconds}s. Kode hanya dapat digunakan sekali.", "Valid for ${remainingSeconds}s. This code can be used once."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    invitation != null -> Text(localized(language, "Kode sudah kedaluwarsa. Buat kode baru.", "The code has expired. Create a new one."))
                    else -> Text(error ?: localized(language, "Kode undangan belum tersedia.", "Invitation code is not available."))
                }
            }
        },
        confirmButton = {
            if (!isLoading && (invitation == null || remainingSeconds == 0L)) {
                TextButton(onClick = onRetry) { Text(localized(language, "Buat ulang", "Create new code")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Tutup", "Close")) } },
    )
}

/** Owner-only endpoint. The displayed suffix is an identifier for choosing an old phone, never a credential. */
@Composable
private fun MemberManagementDialog(
    members: List<com.smartplug.app.domain.model.ManagedMember>,
    isLoading: Boolean,
    revokingMemberId: String?,
    error: String?,
    onRefresh: () -> Unit,
    onRequestRevoke: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Kelola HP", "Manage phones")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(localized(language, "Daftar ini hanya dapat dibuka oleh pemilik SmartPlug. Kredensial rahasia tidak pernah ditampilkan.", "Only the SmartPlug owner can open this list. Secret credentials are never shown."))
                when {
                    isLoading -> OrbitLoadingIndicator(modifier = Modifier.size(36.dp))
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    members.isEmpty() -> Text(localized(language, "Belum ada HP anggota yang terdaftar.", "No member phones are enrolled."))
                    else -> members.forEachIndexed { index, member ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                localized(language, "HP anggota ${index + 1} · …${member.credentialId.takeLast(4)}", "Member phone ${index + 1} · …${member.credentialId.takeLast(4)}"),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = revokingMemberId == null,
                                onClick = { onRequestRevoke(member.credentialId) },
                            ) {
                                if (revokingMemberId == member.credentialId) OrbitLoadingIndicator(modifier = Modifier.size(20.dp))
                                else Text(localized(language, "Cabut", "Revoke"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isLoading) TextButton(onClick = onRefresh) { Text(localized(language, "Muat ulang", "Refresh")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Tutup", "Close")) } },
    )
}

/** One menu entry deliberately owns Connect, Change and Disconnect so the overflow menu stays compact. */
@Composable
private fun ServerConnectionDialog(
    device: com.smartplug.app.domain.model.SmartPlugDevice,
    savedServers: List<com.smartplug.app.domain.model.RegisteredServer>,
    onSelectSaved: (com.smartplug.app.domain.model.RegisteredServer) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val connected = device.integrationMode == IntegrationMode.SERVER
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Koneksi Server", "Connect to Server")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (connected) localized(language, "Terhubung ke ${device.serverId ?: "server"}. Pilih server tersimpan lain untuk mengganti koneksi, atau putuskan untuk kembali ke Direct.", "Connected to ${device.serverId ?: "server"}. Choose another saved server to change the connection, or disconnect to return to Direct.")
                    else localized(language, "Pilih ServerSmartPlug yang sudah terdaftar di aplikasi.", "Choose a ServerSmartPlug already registered in this app."),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (savedServers.isEmpty()) {
                    Text(
                        localized(language, "Tambahkan ServerSmartPlug terlebih dahulu dari menu Perangkat.", "Add ServerSmartPlug first from the Devices menu."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(localized(language, "Pilih server yang tersimpan:", "Choose a saved server:"), style = MaterialTheme.typography.labelSmall)
                    savedServers.forEach { saved ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelectSaved(saved); onDismiss() }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(saved.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(saved.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.RadioButton(
                                selected = device.serverId == saved.serverId,
                                onClick = { onSelectSaved(saved); onDismiss() },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row {
                if (connected) TextButton(onClick = onDisconnect) { Text(localized(language, "Putuskan", "Disconnect")) }
                TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) }
            }
        },
    )
}

@Composable
private fun StatusSection(uiState: DeviceDetailUiState) {
    val language = LocalAppLanguage.current
    val status = uiState.status
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        StatusPill(
            label = if (status?.fresh == true) localized(language, "Data aktif", "Live data") else localized(language, "Data tidak segar", "Stale data"),
            color = if (status?.fresh == true) AccentGreen else AccentRed,
        )
        StatusPill(
            label = when {
                status?.hasSample == true -> localized(language, "Sensor aktif", "Sensor active")
                status != null -> localized(language, "Sensor menunggu", "Sensor waiting")
                else -> localized(language, "Sensor belum diperiksa", "Sensor unchecked")
            },
            color = if (status?.hasSample == true) AccentGreen else AccentRed,
        )
        StatusPill(
            label = if (status?.wifiConnected == true) localized(language, "Wi-Fi tersambung", "Wi-Fi connected") else localized(language, "Wi-Fi terputus", "Wi-Fi disconnected"),
            color = if (status?.wifiConnected == true) AccentGreen else AccentRed,
        )
    }
}

@Composable
private fun RelaySection(
    relayState: RelayState,
    relayUiState: RelayUiState,
    relayActuationEnabled: Boolean,
    onSetRelay: (Boolean) -> Unit,
) {
    val language = LocalAppLanguage.current
    val isBusy = relayUiState == RelayUiState.SENDING || relayUiState == RelayUiState.WAITING_SETTLE
    SmartPlugCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    localized(language, "Kontrol SmartPlug", "SmartPlug control"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (relayState == RelayState.ON) localized(language, "Menyala", "On") else localized(language, "Mati", "Off"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = relayStatusColor(relayState),
                )
            }
            RelayPowerButton(
                on = relayState == RelayState.ON,
                enabled = !isBusy && relayActuationEnabled,
                onClick = { onSetRelay(relayState != RelayState.ON) },
                contentDescription = localized(language, "Kontrol SmartPlug", "SmartPlug control"),
            )
        }
    }
    if (isBusy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        OrbitLoadingIndicator(modifier = Modifier.padding(end = 8.dp).size(22.dp))
        Text(if (relayUiState == RelayUiState.SENDING) localized(language, "Mengirim perintah...", "Sending command...") else localized(language, "Menunggu status...", "Waiting for status..."))
    }
    if (!relayActuationEnabled) {
        Text(
            localized(language, "Kontrol relay dinonaktifkan pada firmware ini", "Relay control is disabled in this firmware"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RenameDialog(initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    var name by remember { mutableStateOf(initialName) }
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Ganti nama SmartPlug", "Rename SmartPlug")) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(localized(language, "Simpan", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
    )
}

private enum class ConfirmAction { RESET_ENERGY, FACTORY_RESET }

@Composable
private fun TripleConfirmDialog(action: ConfirmAction, deviceName: String, onComplete: () -> Unit, onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    val energy = action == ConfirmAction.RESET_ENERGY
    var typed by remember(action) { mutableStateOf("") }
    SheetDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (energy) localized(language, "Reset kWh ke 0?", "Reset kWh to 0?")
                else localized(language, "Reset pabrik", "Factory reset"),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (energy) localized(language, "Counter energi perangkat akan kembali ke nol. Riwayat server sebelum reset tetap disimpan.", "The device energy counter will return to zero. Server history before the reset is retained.")
                    else localized(language, "Wi-Fi, konfigurasi, sesi, dan kredensial perangkat akan dihapus. Perangkat akan reboot ke mode pemasangan. Ketik nama perangkat untuk melanjutkan.", "Wi-Fi, configuration, sessions, and device credentials will be erased. The device reboots into pairing mode. Type the device name to continue."),
                )
                if (!energy) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text(deviceName) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
        confirmButton = {
            if (energy) {
                HoldToConfirmButton(label = localized(language, "Tahan untuk reset", "Hold to reset"), onConfirmed = onComplete)
            } else {
                TextButton(enabled = deviceName.isNotBlank() && typed.trim() == deviceName, onClick = onComplete) {
                    Text(localized(language, "Reset", "Reset"), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

private fun formatLimit(value: Double): String {
    val text = String.format(java.util.Locale.US, "%.3f", value)
    return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SetLimitDialog(
    currentKwh: Double,
    limit: com.smartplug.app.data.local.EnergyLimit,
    onSave: (Boolean, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    var enabled by remember { mutableStateOf(limit.enabled) }
    // Start from the live kWh so the user adds on top of it, unless a higher limit is already set.
    var text by remember { mutableStateOf(formatLimit(if (limit.enabled && limit.kwh > currentKwh) limit.kwh else currentKwh)) }
    val value = text.toDoubleOrNull()
    val valid = value != null && value > 0.0
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Set Limit kWh", "Set kWh limit")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localized(language, "Aktifkan limit", "Enable limit"), modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(
                    localized(language, "kWh saat ini: ${formatLimit(currentKwh)}", "Current kWh: ${formatLimit(currentKwh)}"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        val cleaned = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
                        if (cleaned.count { it == '.' } <= 1) text = cleaned
                    },
                    label = { Text(localized(language, "Limit (kWh)", "Limit (kWh)")) },
                    singleLine = true,
                    isError = !valid,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf(0.5, 1.0, 2.0, 5.0, 10.0, 100.0).forEach { step ->
                        androidx.compose.material3.AssistChip(
                            onClick = { text = formatLimit((text.toDoubleOrNull() ?: currentKwh) + step) },
                            label = { Text("+${formatLimit(step)} kWh") },
                        )
                    }
                    androidx.compose.material3.AssistChip(
                        onClick = { text = formatLimit(currentKwh) },
                        label = { Text(localized(language, "= kWh sekarang", "= current kWh")) },
                    )
                }
                if (enabled && valid && value != null && value <= currentKwh) {
                    Text(
                        localized(language, "Limit sudah tercapai: relay akan dimatikan dan tidak bisa dinyalakan.", "Limit already reached: the relay will be turned off and cannot be turned on."),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    localized(language, "Limit bekerja saat aplikasi terbuka.", "The limit acts while the app is open."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(enabled, value ?: limit.kwh) },
                enabled = !enabled || valid,
            ) { Text(localized(language, "Simpan", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
    )
}

@Composable
private fun TimerDialog(
    initialRemainingMs: Long,
    onApply: (Int, Int, Int, () -> Unit) -> Unit,
    onReset: (() -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    // The UI deliberately has no day wheel. It presents total hours like the
    // handset Clock app; the view model converts them back to days + hours.
    val initialSeconds = (initialRemainingMs / 1_000L).coerceIn(0L, 359_999L)
    var hours by remember(initialSeconds) { mutableStateOf((initialSeconds / 3_600L).toInt()) }
    var minutes by remember(initialSeconds) { mutableStateOf(((initialSeconds % 3_600L) / 60L).toInt()) }
    var seconds by remember(initialSeconds) { mutableStateOf((initialSeconds % 60L).toInt()) }
    // A zero duration is rejected by the firmware contract.  Do not issue a
    // request that is guaranteed to fail; the user can use Reset + stop for
    // the explicit zero/reset operation instead.
    val hasDuration = hours != 0 || minutes != 0 || seconds != 0
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Timer SmartPlug", "SmartPlug timer")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    localized(language, "Timer disimpan di SmartPlug/server. Jika disetel saat relay OFF, hitung mundur mulai ketika relay ON.", "The timer is stored on the SmartPlug/server. If set while the relay is OFF, countdown starts when the relay turns ON."),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TimerWheel(localized(language, "Jam", "Hours"), 0..99, hours) { hours = it }
                    TimerWheel(localized(language, "Menit", "Minutes"), 0..59, minutes) { minutes = it }
                    TimerWheel(localized(language, "Detik", "Seconds"), 0..59, seconds) { seconds = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(hours, minutes, seconds, onDismiss) },
                enabled = hasDuration,
            ) { Text(localized(language, "Terapkan ke SmartPlug", "Apply to SmartPlug")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onReset(onDismiss) }) { Text(localized(language, "Reset + stop", "Reset + stop")) }
                TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) }
            }
        },
    )
}

@Composable
private fun TimerWheel(label: String, range: IntRange, selected: Int, onSelect: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        com.smartplug.app.ui.components.NumberWheel(range = range, value = selected, onValueChange = onSelect)
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val days = totalSeconds / 86_400
    val hours = totalSeconds % 86_400 / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (days > 0) "%dd %02d:%02d:%02d".format(days, hours, minutes, seconds)
    else "%02d:%02d:%02d".format(hours, minutes, seconds)
}

/**
 * The cumulative kWh is intentionally the visual anchor.  Current electrical
 * values remain live diagnostics below it instead of competing with it.
 */
@Composable
private fun MonitoringHero(
    measurement: ElectricalMeasurement,
    status: DeviceStatus?,
    serverBacked: Boolean,
    adjustPercent: Double = 0.0,
) {
    val language = LocalAppLanguage.current
    val live = status?.fresh == true
    val shownKwh = applyEnergyAdjustment(measurement.energyWh.coerceAtLeast(0.0) / 1000.0, adjustPercent)
    Box(
        modifier = Modifier.fillMaxWidth().background(
            Brush.verticalGradient(
                0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                0.75f to MaterialTheme.colorScheme.surface,
                1f to MaterialTheme.colorScheme.surface,
            ),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        ).padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                localized(language, "Total konsumsi", "Total consumption"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                AnimatedDecimal(
                    value = shownKwh,
                    decimals = com.smartplug.app.util.LocalKwhDecimals.current,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    " kWh",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = com.smartplug.app.ui.theme.SmartPlugMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val costConfig = com.smartplug.app.ui.components.LocalCostConfig.current
            if (costConfig.active) {
                Text(
                    "≈ ${costConfig.format(shownKwh)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (measurement.activePowerW > 0.0) {
                // Instantaneous rate from active power (W / 60000 = kWh per minute), 4 decimals.
                val kwhPerMinute = applyEnergyAdjustment(measurement.activePowerW / 60_000.0, adjustPercent)
                Text(
                    if (kwhPerMinute < 0.0001) "+<0.0001 kWh/min"
                    else "+${String.format(java.util.Locale.US, "%.4f", kwhPerMinute)} kWh/min",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = com.smartplug.app.ui.theme.SmartPlugMono,
                    color = AccentGreen,
                )
            }
            Text(
                localized(
                    language,
                    "Daya saat ini ${String.format(java.util.Locale.US, "%.1f", measurement.activePowerW.coerceAtLeast(0.0))} W",
                    "Current power ${String.format(java.util.Locale.US, "%.1f", measurement.activePowerW.coerceAtLeast(0.0))} W",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                StatusPill(
                    label = if (live) localized(language, "Live", "Live") else localized(language, "Menunggu data", "Waiting for data"),
                    color = if (live) AccentGreen else AccentRed,
                    pulse = live,
                )
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        if (serverBacked) "SERVER" else "DIRECT",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickInfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, caption: String, modifier: Modifier = Modifier) {
    SmartPlugCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, fontFamily = com.smartplug.app.ui.theme.SmartPlugMono)
                Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ElectricalStatCard(measurement: ElectricalMeasurement) {
    val language = LocalAppLanguage.current
    SmartPlugCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            HeroMetric(String.format(java.util.Locale.US, "%.0f", measurement.voltageV), localized(language, "Tegangan V", "Voltage V"), Modifier.weight(1f))
            HeroMetric(String.format(java.util.Locale.US, "%.2f", measurement.currentA.coerceAtLeast(0.0)), localized(language, "Arus A", "Current A"), Modifier.weight(1f))
            HeroMetric(String.format(java.util.Locale.US, "%.0f%%", measurement.powerFactor * 100.0), "PF", Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, fontFamily = com.smartplug.app.ui.theme.SmartPlugMono, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun countdownRemaining(sourceMs: Long, receivedAtMs: Long, nowMs: Long): Long =
    (sourceMs - (nowMs - receivedAtMs).coerceAtLeast(0L)).coerceAtLeast(0L)

/** The server keeps an armed duration unchanged until relay ON; only running timers count down. */
internal fun displayTimerRemaining(sourceMs: Long, armed: Boolean, receivedAtMs: Long, nowMs: Long): Long =
    if (armed) sourceMs.coerceAtLeast(0L) else countdownRemaining(sourceMs, receivedAtMs, nowMs)

@Composable
private fun NameLoadDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val language = LocalAppLanguage.current
    var name by remember { mutableStateOf("") }
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Beri nama beban", "Name this load")) },
        text = { Column { Text(localized(language, "Pencocokan memakai arus dan power factor, sehingga hasilnya adalah perkiraan.", "Matching uses current and power factor, so it is an estimate."), style = MaterialTheme.typography.bodySmall); OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(localized(language, "Contoh: Pompa air", "Example: Water pump")) }, singleLine = true) } },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(localized(language, "Simpan", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
    )
}
