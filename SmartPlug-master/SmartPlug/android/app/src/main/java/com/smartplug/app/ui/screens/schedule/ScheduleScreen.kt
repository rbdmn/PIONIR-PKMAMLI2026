package com.smartplug.app.ui.screens.schedule

import com.smartplug.app.ui.theme.AccentGreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import com.smartplug.app.ui.components.SheetDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import com.smartplug.app.ui.components.ScheduleRail
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.DailyScheduleEntry
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.components.shouldShowScheduleCountdown
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

@Composable
fun ScheduleScreen(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<IndexedValue<DailyScheduleEntry>?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1_000) } }
    // Refresh the firmware-owned next occurrence so the countdown rolls to tomorrow after an
    // event fires, even while this page stays open.
    LaunchedEffect(Unit) { while (true) { delay(10_000); viewModel.refresh() } }
    val timezoneOffset = TimeZone.getDefault().getOffset(nowMs) / 60_000
    val schedule = uiState.schedule
    // Use the exact timezone that will be sent with the Add action. A newly
    // configured SmartPlug may still report UTC (offset 0), while the first
    // added entry will atomically establish the user's local schedule offset.
    val addDefaultTime = schedule?.let { scheduleEntryDefaultTime(it, timezoneOffset, nowMs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localized(language, "Jadwal", "Schedule")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, localized(language, "Kembali", "Back")) } },
                actions = { IconButton(onClick = { showAdd = true }, enabled = schedule != null) { Icon(Icons.Filled.Add, localized(language, "Tambah jadwal", "Add schedule")) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            schedule?.let { value ->
                SmartPlugCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(localized(language, "Aktifkan jadwal", "Enable schedule"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (value.clockSynchronized) localized(language, "Waktu SmartPlug tersinkronisasi", "SmartPlug time is synchronized")
                                else localized(language, "Menunggu waktu internet (NTP)", "Waiting for internet time (NTP)"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = value.enabled, onCheckedChange = { viewModel.setEnabled(it, timezoneOffset) })
                    }
                    val nowLocal = Instant.ofEpochMilli(nowMs)
                        .atOffset(ZoneOffset.ofTotalSeconds((if (value.timezoneOffsetMinutes != 0) value.timezoneOffsetMinutes else timezoneOffset).coerceIn(-720, 840) * 60))
                    ScheduleRail(
                        entries = value.entries,
                        nowMinute = nowLocal.hour * 60 + nowLocal.minute,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    val remainingMs = (value.nextRemainingSeconds * 1_000L -
                        (nowMs - uiState.scheduleReceivedAtMs).coerceAtLeast(0L)).coerceAtLeast(0L)
                    if (value.enabled && value.nextTurnOn != null && shouldShowScheduleCountdown(remainingMs)) {
                        val seconds = remainingMs / 1_000L
                        Text(
                            localized(language, "Berikutnya: ${if (value.nextTurnOn) "ON" else "OFF"} dalam ${formatCountdown(seconds)}", "Next: ${if (value.nextTurnOn) "ON" else "OFF"} in ${formatCountdown(seconds)}"),
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // The schedule is a daily timeline, not a manually-ranked list.
                // Retain the device index for Delete while always rendering
                // 00:00 through 23:59 to the user.
                val nextRemainingMs = (value.nextRemainingSeconds * 1_000L -
                    (nowMs - uiState.scheduleReceivedAtMs).coerceAtLeast(0L)).coerceAtLeast(0L)
                val nextIndex = nextEntryIndex(value, nowMs + nextRemainingMs)
                orderedScheduleEntries(value.entries).forEach { indexedEntry ->
                    ScheduleEntryCard(
                        entry = indexedEntry.value,
                        isNext = indexedEntry.index == nextIndex,
                        onEdit = { editing = indexedEntry },
                        onDelete = { viewModel.delete(indexedEntry.index) },
                    )
                }
                if (value.entries.isEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Text(localized(language, "Belum ada jadwal", "No schedules yet"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        Text(localized(language, "Tekan + untuk ON/OFF harian", "Tap + to add a daily ON/OFF action"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } ?: Text(if (uiState.isLoading) localized(language, "Memuat jadwal…", "Loading schedule…") else scheduleError(language, uiState.error))
            uiState.error?.takeIf { schedule != null }?.let { Text(scheduleError(language, it), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (showAdd) {
        AddScheduleDialog(
            initialHour = addDefaultTime?.hour ?: 0,
            initialMinute = addDefaultTime?.minute ?: 0,
            onDismiss = { showAdd = false },
            onAdd = { hour, minute, turnOn, event ->
                viewModel.add(hour, minute, turnOn, event, timezoneOffset) { showAdd = false }
            },
        )
    }
    editing?.let { target ->
        AddScheduleDialog(
            initialHour = target.value.hour,
            initialMinute = target.value.minute,
            initialTurnOn = target.value.turnOn,
            initialEvent = target.value.event,
            isEdit = true,
            onDismiss = { editing = null },
            onAdd = { hour, minute, turnOn, event ->
                viewModel.edit(target.index, target.value, hour, minute, turnOn, event, timezoneOffset) { editing = null }
            },
        )
    }
}

@Composable
private fun ScheduleEntryCard(entry: DailyScheduleEntry, isNext: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val language = LocalAppLanguage.current
    SmartPlugCard(
        highlighted = isNext,
        containerColor = if (isNext) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface) else null,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%02d:%02d".format(entry.hour, entry.minute),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = com.smartplug.app.ui.theme.SmartPlugMono,
            )
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = if (entry.turnOn) AccentGreen.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    if (entry.turnOn) "ON" else "OFF",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (entry.turnOn) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                if (entry.event.isNotBlank()) Text(entry.event, style = MaterialTheme.typography.bodyMedium)
                if (isNext) {
                    Text(
                        localized(language, "Berikutnya", "Up next"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, localized(language, "Edit jadwal", "Edit schedule")) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }
}

@Composable
private fun AddScheduleDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onAdd: (Int, Int, Boolean, String) -> Unit,
    initialTurnOn: Boolean = true,
    initialEvent: String = "",
    isEdit: Boolean = false,
) {
    val language = LocalAppLanguage.current
    // A schedule is evaluated by SmartPlug/ServerSmartPlug time, not by the
    // time when this composable happened to be first created.  Seed the form
    // from that authority so a new entry does not misleadingly start at 00:00.
    var hour by remember(initialHour) { mutableStateOf(initialHour.coerceIn(0, 23)) }
    var minute by remember(initialMinute) { mutableStateOf(initialMinute.coerceIn(0, 59)) }
    var turnOn by remember { mutableStateOf(initialTurnOn) }
    var event by remember { mutableStateOf(initialEvent) }
    val valid = true
    val eventValid = event.length <= 24 && event.all { character ->
        character.code in 0x20..0x7e && character !in setOf(',', ':', '"', '\\')
    }
    SheetDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) localized(language, "Edit jadwal", "Edit schedule") else localized(language, "Tambah jadwal", "Add schedule")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = turnOn, onClick = { turnOn = true }, label = { Text("ON") })
                    FilterChip(selected = !turnOn, onClick = { turnOn = false }, label = { Text("OFF") })
                }
                Text(
                    localized(language, "Setiap hari pada waktu berikut (format 24 jam)", "Every day at (24-hour time)"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.smartplug.app.ui.components.NumberWheel(range = 0..23, value = hour, onValueChange = { hour = it })
                    Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    com.smartplug.app.ui.components.NumberWheel(range = 0..59, value = minute, onValueChange = { minute = it })
                }
                OutlinedTextField(
                    value = event,
                    onValueChange = { event = it.take(24) },
                    label = { Text(localized(language, "Kegiatan", "Event")) },
                    placeholder = { Text(localized(language, "Contoh: Pompa air", "Example: Water pump")) },
                    singleLine = true,
                    isError = !eventValid,
                    supportingText = if (!eventValid) ({ Text(localized(language, "Gunakan huruf/angka biasa; tanpa koma, titik dua, kutip, atau garis miring.", "Use plain letters/numbers; commas, colons, quotes, and backslashes are not allowed.")) }) else null,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(hour, minute, turnOn, event) }, enabled = valid && eventValid) { Text(if (isEdit) localized(language, "Simpan", "Save") else localized(language, "Tambah", "Add")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
    )
}

/** Default an added schedule to the active schedule authority clock.
 *
 * `clockUtcMs` is supplied by the SmartPlug/ServerSmartPlug API. If it is not
 * available yet (for example NTP is still acquiring time), use the current
 * wall clock expressed in the same schedule timezone rather than silently
 * returning 00:00. The value is only a form default; it does not write any
 * device configuration until the user presses Add.
 */
internal fun scheduleEntryDefaultTime(
    schedule: com.smartplug.app.domain.model.DeviceSchedule,
    displayTimezoneOffsetMinutes: Int,
    fallbackUtcMs: Long,
): ScheduleEntryTime {
    val utcMs = schedule.clockUtcMs.takeIf { it > 0L } ?: fallbackUtcMs
    val offsetSeconds = displayTimezoneOffsetMinutes.coerceIn(-720, 840) * 60
    val local = Instant.ofEpochMilli(utcMs).atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds))
    return ScheduleEntryTime(hour = local.hour, minute = local.minute)
}

internal data class ScheduleEntryTime(val hour: Int, val minute: Int)

/**
 * Index (in device order) of the entry that fires at [targetUtcMs]: the one with the reported next
 * action whose daily time is within a minute of the target, seen in the schedule's own timezone.
 */
internal fun nextEntryIndex(schedule: com.smartplug.app.domain.model.DeviceSchedule, targetUtcMs: Long): Int? {
    val turnOn = schedule.nextTurnOn ?: return null
    if (!schedule.enabled) return null
    val offset = ZoneOffset.ofTotalSeconds(schedule.timezoneOffsetMinutes.coerceIn(-720, 840) * 60)
    val target = Instant.ofEpochMilli(targetUtcMs).atOffset(offset)
    val targetMinute = target.hour * 60 + target.minute
    val best = schedule.entries.withIndex()
        .filter { it.value.turnOn == turnOn }
        .map { indexed ->
            val diff = kotlin.math.abs(indexed.value.hour * 60 + indexed.value.minute - targetMinute)
            indexed.index to minOf(diff, 1440 - diff)
        }
        .minByOrNull { it.second }
    return best?.takeIf { it.second <= 1 }?.first
}

/** Ascending daily-time display while retaining the original device index for mutations. */
internal fun orderedScheduleEntries(entries: List<DailyScheduleEntry>): List<IndexedValue<DailyScheduleEntry>> =
    entries.withIndex().sortedWith(
        compareBy<IndexedValue<DailyScheduleEntry>> { it.value.hour }
            .thenBy { it.value.minute }
            .thenBy { it.index },
    )

private fun formatCountdown(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

private fun scheduleError(language: com.smartplug.app.ui.localization.AppLanguage, error: String?): String = when (error) {
    "schedule_requires_direct_firmware" -> localized(language, "Jadwal server belum tersedia pada firmware ServerSmartPlug ini.", "Server schedules are not yet available on this ServerSmartPlug firmware.")
    "schedule_full" -> localized(language, "Maksimum delapan jadwal per SmartPlug.", "A SmartPlug can have up to eight schedules.")
    "schedule_event_storage_full" -> localized(language, "Nama kegiatan terlalu panjang untuk penyimpanan jadwal SmartPlug. Pendekkan atau hapus nama kegiatan lain.", "Event labels exceed this SmartPlug's schedule storage. Shorten this label or remove another event label.")
    "invalid_schedule_entry" -> localized(language, "Data jadwal tidak valid.", "The schedule entry is invalid.")
    else -> localized(language, "Jadwal tidak dapat dimuat.", "Schedule could not be loaded.")
}
