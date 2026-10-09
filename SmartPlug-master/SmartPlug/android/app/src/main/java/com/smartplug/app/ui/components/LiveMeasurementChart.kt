package com.smartplug.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.IconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartplug.app.ui.screens.devicedetail.LiveMeasurementPoint
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import java.util.Locale
import kotlin.math.abs

enum class LiveMetric(val label: String, val unit: String, val color: Color) {
    // Energy comes first: cumulative kWh is the number this app is about, then power.
    ENERGY("Energy", "kWh", Color(0xFF477BFF)),
    WATT("Watt", "W", Color(0xFFEE6C4D)),
    VOLTAGE("Volt", "V", Color(0xFF1B998B)),
    CURRENT("Amp", "A", Color(0xFFF0A202)),
    POWER_FACTOR("PF", "%", Color(0xFF8B5CF6)),
}

/** Selectable X-axis spans (milliseconds). Must not exceed the ViewModel's live-point retention. */
private val WINDOW_OPTIONS = listOf(
    60_000L to "1 mnt",
    5 * 60_000L to "5 mnt",
    10 * 60_000L to "10 mnt",
    15 * 60_000L to "15 mnt",
    30 * 60_000L to "30 mnt",
    45 * 60_000L to "45 mnt",
    60 * 60_000L to "1 jam",
    120 * 60_000L to "2 jam",
)

/** Positive "time ago" label (no minus sign), e.g. 45s, 5m, 1h30m. */
private fun formatSpan(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> if (m == 0L && s == 0L) "${h}h" else if (s == 0L) "${h}h${m}m" else "${h}h${m}m${s}s"
        m > 0 -> if (s == 0L) "${m}m" else "${m}m${s}s"
        else -> "${s}s"
    }
}

@Composable
fun LiveMeasurementChart(
    points: List<LiveMeasurementPoint>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    energyAdjustPercent: Double = 0.0,
) {
    val language = LocalAppLanguage.current
    val tapFeedback = rememberTapFeedback()
    var metric by remember { mutableStateOf(LiveMetric.ENERGY) }
    var minimized by remember { mutableStateOf(false) }
    var windowMs by remember { mutableStateOf(5 * 60_000L) }
    var windowMenuOpen by remember { mutableStateOf(false) }
    val cutoff = System.currentTimeMillis() - windowMs
    val visible = points.filter { it.timestampMs >= cutoff }
    val values = visible.map { metricValue(it, metric, energyAdjustPercent) }

    SmartPlugCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                localized(language, "Tren live", "Live trend"),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (!minimized) {
                AssistChip(
                    onClick = onClear,
                    label = { Text(localized(language, "Clear", "Clear")) },
                )
                Spacer(Modifier.width(6.dp))
                Box {
                    AssistChip(
                        onClick = { windowMenuOpen = true },
                        label = { Text(WINDOW_OPTIONS.first { it.first == windowMs }.second) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    )
                    DropdownMenu(expanded = windowMenuOpen, onDismissRequest = { windowMenuOpen = false }) {
                        WINDOW_OPTIONS.forEach { (ms, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { tapFeedback.onTap(); windowMs = ms; windowMenuOpen = false },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { minimized = !minimized }) {
                Icon(
                    if (minimized) Icons.Filled.Add else Icons.Filled.Remove,
                    contentDescription = if (minimized) localized(language, "Tampilkan grafik", "Show chart") else localized(language, "Minimalkan grafik", "Minimize chart"),
                )
            }
        }
        if (minimized) return@SmartPlugCard
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LiveMetric.entries.forEach { item ->
                FilterChip(
                    selected = metric == item,
                    onClick = { metric = item },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(item.label, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (values.isEmpty()) "–" else if (metric == LiveMetric.ENERGY) com.smartplug.app.util.formatKwhValue(values.last(), com.smartplug.app.util.LocalKwhDecimals.current) else format(values.last()),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                " ${metric.unit}",
                style = MaterialTheme.typography.titleMedium,
                color = metric.color,
                modifier = Modifier.padding(bottom = 4.dp).weight(1f),
            )
            Text(
                metricName(metric, language),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (values.size < 2) {
            Text(
                localized(language, "Menunggu minimal dua sampel untuk membentuk grafik.", "Waiting for at least two samples to draw the chart."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LiveTrendChart(
                samples = points.map { TimedValue(it.timestampMs, metricValue(it, metric, energyAdjustPercent)) },
                windowMs = windowMs,
                labelsForSpan = { span -> (0..4).map { j -> if (j == 4) "now" else formatSpan(span * (4 - j) / 4) } },
                color = metric.color,
            )
        }
    }
}

private fun metricName(metric: LiveMetric, language: com.smartplug.app.ui.localization.AppLanguage): String = when (metric) {
    LiveMetric.ENERGY -> localized(language, "Energi kumulatif", "Cumulative energy")
    LiveMetric.WATT -> localized(language, "Daya aktif", "Active power")
    LiveMetric.VOLTAGE -> localized(language, "Tegangan", "Voltage")
    LiveMetric.CURRENT -> localized(language, "Arus", "Current")
    LiveMetric.POWER_FACTOR -> "Power factor"
}

private fun metricValue(point: LiveMeasurementPoint, metric: LiveMetric, energyAdjustPercent: Double): Double = when (metric) {
    LiveMetric.VOLTAGE -> point.measurement.voltageV
    LiveMetric.CURRENT -> point.measurement.currentA
    LiveMetric.WATT -> point.measurement.activePowerW
    LiveMetric.ENERGY -> com.smartplug.app.util.applyEnergyAdjustment(point.measurement.energyWh.coerceAtLeast(0.0) / 1000.0, energyAdjustPercent)
    LiveMetric.POWER_FACTOR -> point.measurement.powerFactor * 100.0
}

// Very small non-zero readings (e.g. a few Wh expressed in kWh) need more decimals, otherwise the
// value would show as 0.000 while the chart is clearly moving.
private fun format(value: Double): String = String.format(
    Locale.US,
    when {
        value != 0.0 && abs(value) < 0.01 -> "%.5f"
        abs(value) < 10 -> "%.3f"
        else -> "%.1f"
    },
    value,
)
