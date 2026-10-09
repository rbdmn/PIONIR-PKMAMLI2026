package com.smartplug.app.ui.screens.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.ui.components.CostConfig
import com.smartplug.app.ui.components.GrowingBars
import com.smartplug.app.ui.components.LocalCostConfig
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.localization.AppLanguage
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.util.FillEstimate
import com.smartplug.app.util.MonthComparison
import com.smartplug.app.util.StorageAnalysis
import com.smartplug.app.util.StorageAnalyzer
import com.smartplug.app.util.StorageDays
import com.smartplug.app.util.barsForDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StorageScreen(viewModel: StorageViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var emptyExportNotice by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    LaunchedEffect(state.export) {
        when (val export = state.export) {
            is ExportState.Ready -> {
                val intent = withContext(Dispatchers.IO) { writeShareIntent(context, export.fileName, export.csv, language) }
                viewModel.consumeExport()
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    emptyExportNotice = false
                }
            }
            ExportState.Empty -> emptyExportNotice = true
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                actions = {
                    IconButton(onClick = { showReset = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = localized(language, "Reset storage", "Reset storage"))
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = localized(language, "Muat ulang", "Reload"))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.serverReachable) {
                item {
                    Notice(
                        localized(
                            language,
                            "Server tidak terjangkau." + if (state.hasLoadedOnce && state.global != null) " Menampilkan data terakhir yang berhasil dimuat." else "",
                            "Server unreachable." + if (state.hasLoadedOnce && state.global != null) " Showing the last data that loaded." else "",
                        ),
                    )
                }
            }
            when (val error = state.error) {
                StorageError.HistoryUnavailable -> item {
                    Notice(localized(language, "Riwayat tidak tersedia (SD belum siap).", "History unavailable (SD card not ready)."), onRetry = viewModel::refresh)
                }
                is StorageError.Other -> if (state.serverReachable) item {
                    Notice(
                        localized(language, "Gagal memuat riwayat (${error.code}).", "Could not load history (${error.code})."),
                        onRetry = viewModel::refresh,
                    )
                }
                null -> Unit
            }
            state.resetError?.let { code ->
                item {
                    Notice(
                        if (code == "server_unsupported")
                            localized(language, "Firmware server belum mendukung hapus riwayat. Tidak ada yang dihapus.", "Server firmware does not support deleting history yet. Nothing was deleted.")
                        else localized(language, "Reset gagal ($code). Tidak ada yang dihapus.", "Reset failed ($code). Nothing was deleted."),
                        onRetry = viewModel::dismissResetError,
                    )
                }
            }
            if (state.nearlyFull) {
                item { Notice(localized(language, "SD hampir penuh (di atas 85%).", "SD card almost full (above 85%).")) }
            }
            item { SdCard(state, language) }
            item { PeriodCard(state, language, onAll = viewModel::selectAll, onPick = { showPicker = true }) }
            if (state.isLoading) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            }
            val global = state.global
            if (global != null) {
                item {
                    AnalysisCard(
                        title = localized(language, "Semua SmartPlug", "All SmartPlugs"),
                        analysis = global,
                        sharePercent = null,
                        comparison = state.globalComparison,
                        language = language,
                        exporting = state.export == ExportState.Building,
                        onExport = { viewModel.requestExport(null) },
                    )
                }
                items(state.perDevice, key = { it.deviceId ?: "" }) { analysis ->
                    AnalysisCard(
                        title = state.deviceNames[analysis.deviceId] ?: analysis.deviceId.orEmpty(),
                        analysis = analysis,
                        sharePercent = StorageAnalyzer.contributionPercent(analysis.totalKwh, global.totalKwh),
                        comparison = state.deviceComparison[analysis.deviceId],
                        sdBytes = state.deviceSdBytes[analysis.deviceId],
                        sdTotalBytes = state.sd?.totalBytes,
                        sdBytesComplete = state.deviceSdBytesComplete,
                        language = language,
                        exporting = state.export == ExportState.Building,
                        onExport = { viewModel.requestExport(analysis.deviceId) },
                    )
                }
            } else if (!state.isLoading && state.error == null && state.hasLoadedOnce) {
                item {
                    Text(
                        localized(language, "Belum ada data riwayat pada periode ini.", "No history data in this period."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDateRangePickerState()
        // Day/month/year for everyone: the picker formats from the configuration locale.
        val dmyLocale = if (language == AppLanguage.INDONESIAN) Locale("id", "ID") else Locale.UK
        val baseConfig = LocalConfiguration.current
        val pickerConfig = remember(baseConfig, dmyLocale) {
            android.content.res.Configuration(baseConfig).apply { setLocale(dmyLocale) }
        }
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        val end = pickerState.selectedEndDateMillis ?: start
                        if (start != null && end != null) {
                            viewModel.selectRange(
                                StorageDays.localStartOfPickedUtcDay(start),
                                StorageDays.localStartOfPickedUtcDay(end),
                            )
                        }
                        showPicker = false
                    },
                ) { Text(localized(language, "Simpan", "Save")) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(localized(language, "Batal", "Cancel")) } },
        ) {
            CompositionLocalProvider(LocalConfiguration provides pickerConfig) {
                Column {
                    // A fixed height keeps the Save/Cancel buttons on screen.
                    DateRangePicker(
                        state = pickerState,
                        modifier = Modifier.height(460.dp),
                        title = {
                            Text(
                                localized(language, "Pilih rentang tanggal", "Select date range"),
                                modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                    Text(
                        retentionInfo(language),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showReset) {
        ResetStorageDialog(language, onDismiss = { showReset = false }, onConfirmed = { showReset = false; viewModel.resetStorage() })
    }

    if (emptyExportNotice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { emptyExportNotice = false; viewModel.consumeExport() },
            confirmButton = {
                TextButton(onClick = { emptyExportNotice = false; viewModel.consumeExport() }) { Text("OK") }
            },
            text = { Text(localized(language, "Tidak ada data untuk diekspor.", "No data to export.")) },
        )
    }
}

@Composable
private fun Notice(text: String, onRetry: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) {
                val language = LocalAppLanguage.current
                TextButton(onClick = onRetry) { Text(localized(language, "Coba lagi", "Retry")) }
            }
        }
    }
}

@Composable
private fun SdCard(state: StorageUiState, language: AppLanguage) {
    SmartPlugCard {
        Text(localized(language, "Kapasitas SD card", "SD card capacity"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Only the memory taken by the SmartPlugs' own data, not the server's other files on the card.
        val total = state.sd?.totalBytes
        val used = if (total != null && state.deviceSdBytes.isNotEmpty()) state.deviceSdBytes.values.sum() else null
        Text(
            if (used != null && total != null) "${formatBytes(used)} / ${formatBytes(total)}" else "– / –",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (used != null && !state.deviceSdBytesComplete) {
            Text(
                localized(language, "Masih dihitung oleh server…", "Server is still counting…"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The full-card warning and the estimate keep using the real card usage reported by the server.
        if (state.sd?.usedBytes != null && total != null) {
            val fill = state.fill
            Text(
                when (fill) {
                    FillEstimate.NotEnoughData -> localized(language, "Estimasi penuh: belum cukup data", "Estimated full: not enough data yet")
                    FillEstimate.NotGrowing -> localized(language, "Estimasi penuh: pemakaian tidak bertambah", "Estimated full: usage is not growing")
                    is FillEstimate.Days -> localized(language, "Perkiraan penuh dalam ${formatDuration(fill.days, true)}", "Estimated full in ${formatDuration(fill.days, false)}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeriodCard(state: StorageUiState, language: AppLanguage, onAll: () -> Unit, onPick: () -> Unit) {
    SmartPlugCard {
        Text(localized(language, "Periode", "Period"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(selected = state.period?.isAll != false, onClick = onAll, label = { Text(localized(language, "Semua", "All")) })
            FilterChip(
                selected = state.period?.isAll == false,
                onClick = onPick,
                label = {
                    val p = state.period
                    Text(if (p != null && !p.isAll) "${formatDate(p.fromMs)} – ${formatDate(p.toMs)}" else localized(language, "Pilih tanggal", "Pick dates"))
                },
            )
        }
        state.resolution?.let { res ->
            Text(
                localized(language, "Resolusi data: ${resolutionLabel(res, true)}", "Data resolution: ${resolutionLabel(res, false)}"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (state.resolutionRaised) {
                Text(
                    localized(language, "Tanggal terlalu lama untuk resolusi halus, dipakai resolusi lebih kasar.", "Dates are older than the fine-resolution retention; a coarser resolution is used."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(retentionInfo(language), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AnalysisCard(
    title: String,
    analysis: StorageAnalysis,
    sharePercent: Double?,
    comparison: MonthComparison?,
    language: AppLanguage,
    exporting: Boolean,
    sdBytes: Long? = null,
    sdTotalBytes: Long? = null,
    sdBytesComplete: Boolean = true,
    onExport: () -> Unit,
) {
    val cost = LocalCostConfig.current
    val kwhDecimals = com.smartplug.app.util.LocalKwhDecimals.current
    SmartPlugCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (sharePercent != null) {
                Text(String.format(Locale.US, "%.1f%%", sharePercent), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (sharePercent != null) {
            LinearProgressIndicator(progress = { (sharePercent / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        StatRow(localized(language, "Total", "Total"), kwhWithCost(analysis.totalKwh, cost, kwhDecimals))
        StatRow(localized(language, "Daya rata-rata", "Average power"), String.format(Locale.US, "%.1f W", analysis.averageW))
        StatRow(
            localized(language, "Daya puncak", "Peak power"),
            analysis.peak?.let { String.format(Locale.US, "%.1f W · %s", it.watts, formatDateTime(it.atUtcMs)) } ?: "–",
        )
        StatRow(localized(language, "Record dimuat", "Records loaded"), analysis.recordCount.toString())
        if (sdBytes != null) {
            StatRow(
                localized(language, "Memori di SD card", "SD card memory"),
                formatBytes(sdBytes) + sharePercentText(sdBytes, sdTotalBytes) +
                    if (sdBytesComplete) "" else localized(language, " (masih dihitung)", " (still counting)"),
            )
        }

        val bars = barsForDays(analysis.days)
        if (bars.isNotEmpty() && bars.any { it.kwh > 0.0 }) {
            Text(
                localized(language, "kWh per hari", "kWh per day") + (bars.firstOrNull()?.days?.takeIf { it > 1 }?.let { " (1 bar = $it ${localized(language, "hari", "days")})" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            val labelFormat = remember { SimpleDateFormat("d/M", Locale.US) }
            GrowingBars(
                values = bars.map { it.kwh },
                labels = bars.map { labelFormat.format(Date(it.startMs)) },
                color = MaterialTheme.colorScheme.primary,
                highlightIndex = bars.indexOf(bars.maxByOrNull { it.kwh }),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        if (analysis.days.isNotEmpty()) {
            Text(
                localized(
                    language,
                    "Cakupan: ${analysis.daysWithData} hari ada data, ${analysis.gapDays} hari bolong",
                    "Coverage: ${analysis.daysWithData} days with data, ${analysis.gapDays} gap days",
                ),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            val gaps = analysis.days.filter { !it.hasData }
            if (gaps.isNotEmpty()) {
                val shown = gaps.take(6).joinToString(", ") { formatDate(it.dayStartMs) }
                Text(
                    localized(language, "Bolong: $shown${if (gaps.size > 6) ", …" else ""}", "Gaps: $shown${if (gaps.size > 6) ", …" else ""}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    localized(
                        language,
                        "Server hanya mencatat saat waktu tersinkron dan SD siap, jadi total bisa lebih kecil dari pemakaian sebenarnya.",
                        "The server only records while time is synchronised and the SD card is ready, so the total may be lower than real usage.",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (comparison != null) {
            Text(localized(language, "Bulan ini vs bulan lalu", "This month vs last month"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            StatRow(localized(language, "Bulan ini", "This month"), kwhWithCost(comparison.thisMonthKwh, cost, kwhDecimals))
            StatRow(localized(language, "Bulan lalu", "Last month"), kwhWithCost(comparison.lastMonthKwh, cost, kwhDecimals))
            val pct = comparison.percentChange
            StatRow(
                localized(language, "Selisih", "Difference"),
                String.format(Locale.US, "%+.${kwhDecimals}f kWh", comparison.deltaKwh) + (pct?.let { String.format(Locale.US, " (%+.1f%%)", it) } ?: ""),
            )
        }

        OutlinedButton(onClick = onExport, enabled = !exporting, modifier = Modifier.padding(top = 8.dp)) {
            if (exporting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Export CSV", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun kwhWithCost(kwh: Double, cost: CostConfig, decimals: Int): String =
    com.smartplug.app.util.formatKwhValue(kwh, decimals) + " kWh" + if (cost.active) " · ≈ ${cost.format(kwh)}" else ""

/** 0 -> "0"; otherwise the unit that fits (B, KB, MB, GB, TB) so small amounts stay visible. */
/** " · 0,03% dari total" for the SD capacity; "< 0,01%" for tiny non-zero amounts. */
internal fun sharePercentText(bytes: Long, totalBytes: Long?): String {
    if (totalBytes == null || totalBytes <= 0L) return ""
    if (bytes <= 0L) return " · 0%"
    val percent = bytes.toDouble() / totalBytes * 100.0
    val text = if (percent < 0.01) "< 0,01" else String.format(Locale.US, "%.2f", percent).replace('.', ',')
    return " · $text% / total"
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
    val text = if (unit == 0) value.toLong().toString() else String.format(Locale.US, "%.1f", value).replace('.', ',')
    return "$text ${units[unit]}"
}

private fun formatDuration(days: Double, indonesian: Boolean): String = when {
    days < 1.0 -> if (indonesian) "< 1 hari" else "< 1 day"
    days < 60.0 -> String.format(Locale.US, if (indonesian) "%.0f hari" else "%.0f days", days)
    days < 730.0 -> String.format(Locale.US, if (indonesian) "%.1f bulan" else "%.1f months", days / 30.4)
    else -> String.format(Locale.US, if (indonesian) "%.1f tahun" else "%.1f years", days / 365.0)
}

private fun resolutionLabel(r: HistoryResolution, indonesian: Boolean) = when (r) {
    HistoryResolution.ONE_SECOND -> if (indonesian) "1 detik" else "1 sec"
    HistoryResolution.ONE_MINUTE -> if (indonesian) "1 menit" else "1 min"
    HistoryResolution.FIVE_MINUTES -> if (indonesian) "5 menit" else "5 min"
    HistoryResolution.THIRTY_MINUTES -> if (indonesian) "30 menit" else "30 min"
    HistoryResolution.ONE_HOUR -> if (indonesian) "1 jam" else "1 hour"
    HistoryResolution.ONE_DAY -> if (indonesian) "1 hari" else "1 day"
}

private fun retentionInfo(language: AppLanguage) = localized(
    language,
    "Retensi server: 1 menit 90 hari, 5 menit 1 tahun, 1 jam 5 tahun, 1 hari selama SD tersedia.",
    "Server retention: 1 min 90 days, 5 min 1 year, 1 hour 5 years, 1 day as long as the SD card allows.",
)

private fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(ms))
private fun formatDateTime(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date(ms))

/** Writes the CSV into cache/exports (dropping files older than a day) and returns a share-sheet intent. */
private fun writeShareIntent(context: Context, fileName: String, csv: String, language: AppLanguage): Intent {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 86_400_000L
    dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") && f.lastModified() < cutoff }?.forEach { it.delete() }
    val file = File(dir, fileName)
    file.writeText(csv, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, localized(language, "Riwayat energi SmartPlug", "SmartPlug energy history"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "Export CSV").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Three confirmation steps; the last one requires typing the phrase exactly. */
@Composable
private fun ResetStorageDialog(language: AppLanguage, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    var typed by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized(language, "Reset storage ($step/3)", "Reset storage ($step/3)")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (step) {
                    1 -> Text(localized(language,
                        "Ini MENGHAPUS PERMANEN seluruh riwayat pengukuran di SD card server (untuk mengosongkan ruang) dan cache di HP ini. Total energi tiap SmartPlug, timer, dan jadwal tidak dihapus.",
                        "This PERMANENTLY DELETES all measurement history on the server's SD card (to free space) and the cache on this phone. Each SmartPlug's energy total, timers and schedules are kept."))
                    2 -> Text(localized(language,
                        "Yakin? Riwayat yang dihapus TIDAK bisa dikembalikan, dan grafik/analisis Storage mulai dari kosong.",
                        "Are you sure? Deleted history CANNOT be recovered, and Storage charts/analysis start empty."))
                    else -> {
                        Text(localized(language, "Ketik Reset storage untuk melanjutkan.", "Type Reset storage to continue."))
                        androidx.compose.material3.OutlinedTextField(
                            value = typed, onValueChange = { typed = it }, singleLine = true,
                            placeholder = { Text("Reset storage") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = step < 3 || typed == "Reset storage",
                onClick = { if (step < 3) step++ else onConfirmed() },
            ) { Text(if (step < 3) localized(language, "Lanjut", "Continue") else "Reset") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(localized(language, "Batal", "Cancel")) } },
    )
}
