package com.smartplug.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smartplug.app.domain.model.ElectricalMeasurement
import com.smartplug.app.ui.screens.devicedetail.MeasurementRates
import com.smartplug.app.ui.localization.LocalAppLanguage
import com.smartplug.app.ui.localization.localized
import java.util.Locale

data class MeasurementTile(
    val label: String,
    val value: String,
    val unit: String,
    val secondary: String? = null,
    val rate: String? = null,
    val accent: Color? = null,
)

fun ElectricalMeasurement.toTiles(
    rates: MeasurementRates? = null,
    languageEnglish: Boolean = false,
    energySavedWh: Double? = null,
    energyNextSaveMs: Long? = null,
): List<MeasurementTile> = listOf(
    MeasurementTile(if (languageEnglish) "Voltage" else "Tegangan", format1(voltageV), "V", rate = rates?.voltageVariationPercent?.variationPercent()),
    MeasurementTile(if (languageEnglish) "Current" else "Arus", format2(currentA), "A"),
    MeasurementTile(if (languageEnglish) "Active power" else "Daya aktif", format1(activePowerW), "W", if (languageEnglish) "Apparent power ${format1(apparentPowerVa)} VA" else "Daya semu ${format1(apparentPowerVa)} VA", rates?.activePowerW?.perMinute("W", languageEnglish)),
    MeasurementTile("Power factor", formatPercent(powerFactor), "%", rate = rates?.powerFactorVariationPercent?.variationPercent(), accent = powerFactorColor(powerFactor)),
    MeasurementTile(
        if (languageEnglish) "Energy" else "Energi",
        formatKwh(energyWh.coerceAtLeast(0.0) / 1000.0),
        "kWh",
        secondary = energyPersistenceText(energySavedWh, energyNextSaveMs, languageEnglish),
        rate = rates?.energyKwhPerMinute?.kwhPerMinute(languageEnglish),
    ),
)

private fun format1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun format2(v: Double) = String.format(Locale.US, "%.2f", v)
private fun formatKwh(v: Double) = String.format(Locale.US, "%.5f", v)
private fun formatPercent(value: Double) = String.format(Locale.US, "%.0f", value * 100.0)
private fun energyPersistenceText(savedWh: Double?, remainingMs: Long?, english: Boolean): String? {
    if (savedWh == null) return null
    val saved = formatKwh(savedWh.coerceAtLeast(0.0) / 1000.0)
    val remaining = remainingMs?.let { "%02d:%02d".format((it.coerceAtLeast(0) / 1000) / 60, (it.coerceAtLeast(0) / 1000) % 60) } ?: "--:--"
    return if (english) "Saved $saved kWh · next save $remaining" else "Tersimpan $saved kWh · simpan lagi $remaining"
}
private fun Double.perMinute(unit: String, english: Boolean): String = String.format(Locale.US, if (english) "%+.2f %s/min" else "%+.2f %s/menit", this, unit)
private fun Double.variationPercent(): String = String.format(Locale.US, "± %.2f%%", this.coerceAtLeast(0.0))
private fun Double.kwhPerMinute(english: Boolean): String = String.format(
    Locale.US,
    if (english) "%.5f kWh/min" else "%.5f kWh/menit",
    this.coerceAtLeast(0.0),
)

/** Smoothly maps bad PF to red and healthy PF to green, with no abrupt state jump. */
private fun powerFactorColor(value: Double): Color {
    val t = ((value.coerceIn(0.5, 1.0) - 0.5) / 0.5).toFloat()
    return Color(
        red = 0.85f - (0.60f * t),
        green = 0.15f + (0.55f * t),
        blue = 0.18f - (0.05f * t),
    )
}

/**
 * Fixed 2-column grid for the always-6-tile measurement set. Deliberately plain Row/Column, not
 * LazyVerticalGrid: this is nested inside a `Column(Modifier.verticalScroll())` in
 * DeviceDetailScreen, and a lazy layout there gets measured with an infinite height constraint
 * and crashes (there's no page-size worth of items to justify laziness anyway).
 */
@Composable
fun MeasurementGrid(
    tiles: List<MeasurementTile>,
    modifier: Modifier = Modifier,
    onCurrentClick: (() -> Unit)? = null,
) {
    val language = LocalAppLanguage.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { rowTiles ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowTiles.forEach { tile ->
                    MeasurementTileCard(
                        tile,
                        modifier = Modifier.weight(1f),
                        onClick = if (tile.label == localized(language, "Arus", "Current")) onCurrentClick else null,
                    )
                }
                if (rowTiles.size < 2) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MeasurementTileCard(tile: MeasurementTile, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    SmartPlugCard(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        // PF severity is a continuous red-to-green tint across the entire card,
        // while retaining enough contrast to read the actual number.
        containerColor = tile.accent?.copy(alpha = 0.18f),
    ) {
        Row {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .background(tile.accent ?: MaterialTheme.colorScheme.primary)
                    .height(34.dp)
                    .padding(horizontal = 2.dp),
            )
            Text(tile.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(tile.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (tile.unit.isNotEmpty()) {
                Text(
                    " ${tile.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        tile.secondary?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        tile.rate?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
        }
    }
}
