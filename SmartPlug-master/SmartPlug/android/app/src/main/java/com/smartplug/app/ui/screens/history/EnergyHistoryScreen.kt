package com.smartplug.app.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartplug.app.ui.components.EmptyState
import com.smartplug.app.ui.components.EnergyLineChart
import com.smartplug.app.ui.components.FullScreenError
import com.smartplug.app.ui.components.FullScreenLoading
import com.smartplug.app.ui.components.SmartPlugCard
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import java.util.Locale

@Composable
fun EnergyHistoryScreen(
    deviceId: String,
    onBack: () -> Unit,
    viewModel: EnergyHistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localized(language, "Riwayat pengukuran", "Measurement history")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = localized(language, "Kembali", "Back")) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryRange.entries.forEach { range ->
                    FilterChip(
                        selected = uiState.range == range,
                        onClick = { viewModel.loadRange(range) },
                        label = { Text(range.label(language)) },
                    )
                }
            }

            Column(modifier = Modifier.padding(top = 16.dp).verticalScroll(rememberScrollState())) {
                when {
                    uiState.isLoading -> FullScreenLoading()
                    uiState.error != null && uiState.points.isEmpty() -> FullScreenError(
                        message = uiState.error ?: localized(language, "Riwayat tidak tersedia", "History is unavailable"),
                        onRetry = { viewModel.loadRange(HistoryRange.LAST_DAY) },
                    )
                    uiState.points.isEmpty() -> EmptyState(
                        title = localized(language, "Belum ada riwayat", "No history yet"),
                        description = localized(language, "Mode langsung menyimpan satu titik tiap menit saat halaman perangkat terbuka. Mode server memuat riwayat dari SD card ServerSmartPlug.", "Direct mode saves one point per minute while the device page is open. Server mode loads history from the ServerSmartPlug SD card."),
                    )
                    else -> {
                        // Display-only correction: the stored/fetched points stay raw.
                        val adjustPercent = com.smartplug.app.util.LocalEnergyAdjustments.current[deviceId] ?: 0.0
                        val shownPoints = remember(uiState.points, adjustPercent) {
                            uiState.points.map { it.copy(energyWh = com.smartplug.app.util.applyEnergyAdjustment(it.energyWh, adjustPercent)) }
                        }
                        HistorySummary(points = shownPoints, languageEnglish = language.name == "ENGLISH")
                        // One chart at a time, chosen with chips: energy first, then power and the rest.
                        val metrics = listOf<HistoryMetric>(
                            HistoryMetric(localized(language, "Energi", "Energy"), localized(language, "Energi kumulatif (kWh)", "Cumulative energy (kWh)"), "kWh") { it.energyWh.coerceAtLeast(0.0) / 1000.0 },
                            HistoryMetric(localized(language, "Daya", "Power"), localized(language, "Daya aktif (W)", "Active power (W)"), "W") { it.activePowerW },
                            HistoryMetric(localized(language, "Tegangan", "Voltage"), localized(language, "Tegangan (V)", "Voltage (V)"), "V") { it.voltageV },
                            HistoryMetric(localized(language, "Arus", "Current"), localized(language, "Arus (A)", "Current (A)"), "A") { it.currentA },
                            HistoryMetric(localized(language, "Daya semu", "Apparent"), localized(language, "Daya semu (VA)", "Apparent power (VA)"), "VA") { it.apparentPowerVa },
                            HistoryMetric("PF", localized(language, "Power factor (%)", "Power factor (%)"), "%") { it.powerFactor * 100.0 },
                        )
                        var selectedMetric by rememberSaveable { mutableIntStateOf(0) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            metrics.forEachIndexed { index, metric ->
                                FilterChip(selected = selectedMetric == index, onClick = { selectedMetric = index }, label = { Text(metric.chip) })
                            }
                        }
                        val chosen = metrics[selectedMetric.coerceIn(0, metrics.lastIndex)]
                        HistoryMetricCard(chosen.title, shownPoints, chosen.unit, chosen.value)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMetricCard(
    title: String,
    points: List<EnergyHistoryPoint>,
    unit: String,
    value: (EnergyHistoryPoint) -> Double,
) {
    SmartPlugCard(modifier = Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        EnergyLineChart(points = points, valueSelector = value, unit = unit)
    }
}

@Composable
private fun HistorySummary(points: List<EnergyHistoryPoint>, languageEnglish: Boolean) {
    val energyUsedKwh = ((points.last().energyWh - points.first().energyWh) / 1000.0).coerceAtLeast(0.0)
    SmartPlugCard {
        Text(
            if (languageEnglish) "Used in this range" else "Terpakai pada rentang ini",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            com.smartplug.app.ui.components.AnimatedDecimal(
                value = energyUsedKwh,
                decimals = com.smartplug.app.util.LocalKwhDecimals.current,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Text(" kWh", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            if (languageEnglish) "${points.size} recorded point(s)" else "${points.size} titik tercatat",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val (barValues, barLabels) = remember(points) { energyBuckets(points) }
        if (barValues.any { it > 0.0 }) {
            com.smartplug.app.ui.components.GrowingBars(
                values = barValues,
                labels = barLabels,
                color = MaterialTheme.colorScheme.primary,
                highlightIndex = barValues.indexOf(barValues.max()),
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("", modifier = Modifier.weight(1.5f))
            listOf("Min", if (languageEnglish) "Avg" else "Rata", if (languageEnglish) "Max" else "Maks").forEach {
                Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        HistoryRangeRow(if (languageEnglish) "Voltage" else "Tegangan", points.map { it.voltageV }, "V")
        HistoryRangeRow(if (languageEnglish) "Current" else "Arus", points.map { it.currentA }, "A")
        HistoryRangeRow(if (languageEnglish) "Active power" else "Daya aktif", points.map { it.activePowerW }, "W")
        HistoryRangeRow("Power factor", points.map { it.powerFactor * 100.0 }, "%")
    }
}

@Composable
private fun HistoryRangeRow(label: String, values: List<Double>, unit: String) {
    if (values.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("$label ($unit)", modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.labelMedium)
        listOf(values.min(), values.average(), values.max()).forEach {
            Text(
                formatHistory(it),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = com.smartplug.app.ui.theme.SmartPlugMono,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** Energy used in each of seven equal time slots (cumulative counter deltas) with short labels. */
private fun energyBuckets(points: List<EnergyHistoryPoint>, slots: Int = 7): Pair<List<Double>, List<String>> {
    val sorted = points.sortedBy { it.timestampUtcMs }
    if (sorted.size < 2) return emptyList<Double>() to emptyList()
    val start = sorted.first().timestampUtcMs
    val span = (sorted.last().timestampUtcMs - start).coerceAtLeast(1L)
    val longRange = span > 36L * 3_600_000L
    val label = java.text.SimpleDateFormat(if (longRange) "d/M" else "HH:mm", Locale.US)
    fun energyAt(t: Long) = sorted.lastOrNull { it.timestampUtcMs <= t }?.energyWh ?: sorted.first().energyWh
    val values = (0 until slots).map { i ->
        val from = start + span * i / slots
        val to = start + span * (i + 1) / slots
        ((energyAt(to) - energyAt(from)) / 1000.0).coerceAtLeast(0.0)
    }
    val labels = (0 until slots).map { i -> label.format(java.util.Date(start + span * i / slots)) }
    return values to labels
}

private class HistoryMetric(
    val chip: String,
    val title: String,
    val unit: String,
    val value: (EnergyHistoryPoint) -> Double,
)

private fun formatHistory(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun HistoryRange.label(language: com.smartplug.app.ui.localization.AppLanguage): String = when (this) {
    HistoryRange.LAST_5_MIN -> localized(language, "5 menit", "5 min")
    HistoryRange.LAST_15_MIN -> localized(language, "15 menit", "15 min")
    HistoryRange.LAST_HOUR -> localized(language, "1 jam", "1 hour")
    HistoryRange.LAST_DAY -> localized(language, "24 jam", "24 hours")
    HistoryRange.LAST_WEEK -> localized(language, "7 hari", "7 days")
    HistoryRange.LAST_MONTH -> localized(language, "30 hari", "30 days")
}
