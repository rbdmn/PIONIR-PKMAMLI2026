package com.smartplug.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.ui.components.AnimatedDecimal
import com.smartplug.app.ui.components.EmptyState
import com.smartplug.app.ui.components.PollWhileVisible
import com.smartplug.app.ui.components.PulsingDot
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.ui.components.TickChart
import com.smartplug.app.ui.components.entrance
import com.smartplug.app.ui.screens.devices.DeviceListViewModel
import com.smartplug.app.ui.theme.AccentGreen
import com.smartplug.app.ui.theme.AccentRed
import com.smartplug.app.util.PollingCadence
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import com.smartplug.app.util.formatKwhValue
import java.util.Locale

/** Beranda: a quick "how is my home doing" summary, not a duplicate of the full device list. */
@Composable
fun HomeScreen(
    onOpenDevice: (String) -> Unit,
    onAddDevice: () -> Unit,
    viewModel: DeviceListViewModel = hiltViewModel(),
    summaryViewModel: DailySummaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current

    val settingsForSummary = com.smartplug.app.ui.components.LocalDailySummaryEnabled.current
    val summaryState by summaryViewModel.uiState.collectAsStateWithLifecycle()
    PollWhileVisible(intervalMs = PollingCadence.DEVICE_LIST.intervalMs) {
        viewModel.refreshAll()
        if (settingsForSummary) summaryViewModel.refresh()
    }

    Scaffold(topBar = { TopAppBar(title = { Text(localized(language, "Beranda", "Home")) }) }) { padding ->
        if (uiState.rows.isEmpty()) {
            EmptyState(
                title = localized(language, "Selamat datang di SmartPlug", "Welcome to SmartPlug"),
                description = localized(language, "Pantau konsumsi energi dan kendalikan relay dari satu aplikasi.", "Monitor energy use and control relays from one app."),
                modifier = Modifier.padding(padding),
                action = { Button(onClick = onAddDevice) { Text(localized(language, "Tambah Perangkat", "Add Device")) } },
            )
            return@Scaffold
        }

        val kwhDecimals = com.smartplug.app.util.LocalKwhDecimals.current
        val onlineCount = uiState.rows.count { it.online }
        val readableRows = uiState.rows.filter { it.measurement != null }
        val totalPower = readableRows.sumOf { it.measurement?.activePowerW?.coerceAtLeast(0.0) ?: 0.0 }
        // Per-device kWh corrections (display-only); raw values stay untouched elsewhere.
        val adjustments = com.smartplug.app.util.LocalEnergyAdjustments.current
        fun adjustFor(deviceId: String, kwh: Double) =
            com.smartplug.app.util.applyEnergyAdjustment(kwh, adjustments[deviceId] ?: 0.0)
        val totalKwh = readableRows.sumOf { adjustFor(it.device.deviceId, (it.measurement?.energyWh ?: 0.0).coerceAtLeast(0.0) / 1000.0) }
        val todayTotal = readableRows.mapNotNull { row -> uiState.todayKwh[row.device.deviceId]?.let { adjustFor(row.device.deviceId, it) } }
            .takeIf { it.isNotEmpty() }?.sum()
        val todaySeries = if (uiState.todaySeriesByDevice.isEmpty()) uiState.todaySeries else
            com.smartplug.app.util.sumDaySeries(uiState.todaySeriesByDevice.map { (id, series) -> series.map { it.first to adjustFor(id, it.second) } })
        val heroColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface)

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SmartPlugCard(modifier = Modifier.entrance(0), containerColor = heroColor) {
                    Text(
                        localized(language, "Total energi · $onlineCount dari ${uiState.rows.size} online", "Total energy · $onlineCount of ${uiState.rows.size} online"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                        AnimatedDecimal(
                            value = totalKwh,
                            decimals = kwhDecimals,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            " kWh",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    val cost = com.smartplug.app.ui.components.LocalCostConfig.current
                    if (cost.active) {
                        Text(
                            "≈ ${cost.format(totalKwh)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    todayTotal?.let { today ->
                        Text(
                            localized(language, "▲ +${formatKwhValue(today, kwhDecimals)} kWh hari ini", "▲ +${formatKwhValue(today, kwhDecimals)} kWh today"),
                            style = MaterialTheme.typography.labelLarge,
                            color = AccentGreen,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (todaySeries.size >= 2) {
                        val series = todaySeries
                        val clock = java.text.SimpleDateFormat("HH:mm", Locale.US)
                        // Few minutes of data: label only start and end so ticks never overlap.
                        val spanMs = series.last().first - series.first().first
                        val labels = if (spanMs < 30 * 60_000L) {
                            listOf(clock.format(java.util.Date(series.first().first)), localized(language, "sekarang", "now"))
                        } else {
                            (0..4).map { j ->
                                if (j == 4) localized(language, "sekarang", "now")
                                else clock.format(java.util.Date(series[(series.size - 1) * j / 4].first))
                            }
                        }
                        TickChart(
                            values = series.map { it.second },
                            xLabels = labels,
                            color = MaterialTheme.colorScheme.primary,
                            height = 160.dp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            summaryState.summary?.takeIf { settingsForSummary }?.let { summary ->
                item { DailySummaryCard(summary, summaryState.deviceNames, kwhDecimals, language) }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().entrance(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmartPlugCard(modifier = Modifier.weight(1f)) {
                        Text(localized(language, "Hari ini", "Today"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(todayTotal?.let { formatKwhValue(it, kwhDecimals) } ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, fontFamily = com.smartplug.app.ui.theme.SmartPlugMono)
                        Text("kWh", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val todayCost = com.smartplug.app.ui.components.LocalCostConfig.current
                        if (todayCost.active && todayTotal != null) {
                            Text("≈ ${todayCost.format(todayTotal)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    SmartPlugCard(modifier = Modifier.weight(1f)) {
                        Text(localized(language, "Daya sekarang", "Power now"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AnimatedDecimal(value = totalPower, decimals = 1, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("W", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text(
                    localized(language, "Perangkat", "Devices"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.entrance(2),
                )
            }
            itemsIndexed(uiState.rows, key = { _, row -> row.device.deviceId }) { index, row ->
                SmartPlugCard(modifier = Modifier.fillMaxWidth().entrance(index + 3).clickable { onOpenDevice(row.device.deviceId) }) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.device.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        PulsingDot(color = if (row.online) AccentGreen else AccentRed, active = row.online)
                    }
                    val measurement = row.measurement
                    if (measurement == null) {
                        Text(
                            localized(language, "Offline", "Offline"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val power = measurement.activePowerW.coerceAtLeast(0.0)
                        val kwh = adjustFor(row.device.deviceId, measurement.energyWh.coerceAtLeast(0.0) / 1000.0)
                        val kwhPercent = if (totalKwh > 0.0) kwh / totalKwh * 100.0 else 0.0
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                            Text("${formatKwhValue(kwh, kwhDecimals)} kWh", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, fontFamily = com.smartplug.app.ui.theme.SmartPlugMono, modifier = Modifier.weight(1f))
                            Text("${format1(power)} W", style = MaterialTheme.typography.bodyMedium, fontFamily = com.smartplug.app.ui.theme.SmartPlugMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (kwhPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            localized(language, "${format1(kwhPercent)}% dari total energi", "${format1(kwhPercent)}% of total energy"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)

@Composable
private fun DailySummaryCard(
    summary: com.smartplug.app.util.DailySummary,
    names: Map<String, String>,
    kwhDecimals: Int,
    language: com.smartplug.app.ui.localization.AppLanguage,
) {
    val cost = com.smartplug.app.ui.components.LocalCostConfig.current
    val clock = java.text.SimpleDateFormat("HH:mm", Locale.US)
    SmartPlugCard {
        Text(localized(language, "Ringkasan hari ini", "Today's summary"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val used = formatKwhValue(summary.todayKwh, kwhDecimals) + " kWh" + if (cost.active) " (≈ ${cost.format(summary.todayKwh)})" else ""
        SummaryLine(localized(language, "Pemakaian", "Used"), used)
        summary.topDeviceId?.let { id ->
            SummaryLine(
                localized(language, "Paling banyak", "Most used"),
                "${names[id] ?: id} (${formatKwhValue(summary.topDeviceKwh, kwhDecimals)} kWh)",
            )
        }
        if (summary.peakWatts != null && summary.peakAtMs != null) {
            SummaryLine(
                localized(language, "Daya tertinggi", "Highest power"),
                localized(
                    language,
                    "${format1(summary.peakWatts)} W pukul ${clock.format(java.util.Date(summary.peakAtMs))}",
                    "${format1(summary.peakWatts)} W at ${clock.format(java.util.Date(summary.peakAtMs))}",
                ),
            )
        }
        summary.standbyWatts?.let {
            SummaryLine(
                localized(language, "Saat standby", "On standby"),
                localized(language, "sekitar ${format1(it)} W", "about ${format1(it)} W"),
            )
        }
        summary.changePercent?.let { change ->
            val up = change >= 0.0
            SummaryLine(
                localized(language, "Dibanding kemarin", "Compared to yesterday"),
                localized(
                    language,
                    (if (up) "naik " else "turun ") + String.format(Locale.US, "%.0f", kotlin.math.abs(change)) + "%",
                    (if (up) "up " else "down ") + String.format(Locale.US, "%.0f", kotlin.math.abs(change)) + "%",
                ),
                if (up) AccentRed else AccentGreen,
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}
