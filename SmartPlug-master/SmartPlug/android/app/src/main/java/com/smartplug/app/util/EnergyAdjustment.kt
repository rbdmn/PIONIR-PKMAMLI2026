package com.smartplug.app.util

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.abs
import kotlin.math.roundToLong

/** Per-device display correction of kWh, in percent. Display-only; raw data is never modified. */
object EnergyAdjustment {
    const val MIN_PERCENT = -99.99
    const val MAX_PERCENT = 300.0

    /** Clamps to the allowed range and rounds to 0.01%; non-finite input becomes 0%. */
    fun normalize(percent: Double): Double {
        if (!percent.isFinite()) return 0.0
        val clamped = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        return (clamped * 100.0).roundToLong() / 100.0
    }

    fun factor(percent: Double): Double = 1.0 + normalize(percent) / 100.0

    fun isAdjusted(percent: Double): Boolean = abs(normalize(percent)) >= 0.005

    /**
     * Parses typed input. Returns null for empty or invalid text ("", "-", ".", "abc", NaN) so the
     * caller can ignore it; values outside the range are clamped, never rejected into a crash.
     */
    fun parseInput(text: String): Double? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        return normalize(value)
    }

    /**
     * Slider position (0..1) to percent with 0% exactly in the middle: the left half scales
     * -99.99..0 linearly, the right half 0..+300 linearly.
     */
    fun sliderToPercent(position: Float): Double {
        val p = position.toDouble().coerceIn(0.0, 1.0)
        val raw = if (p < 0.5) MIN_PERCENT * (0.5 - p) / 0.5 else MAX_PERCENT * (p - 0.5) / 0.5
        return normalize(raw)
    }

    fun percentToSlider(percent: Double): Float {
        val v = normalize(percent)
        val position = if (v < 0.0) 0.5 - 0.5 * (v / MIN_PERCENT) else 0.5 + 0.5 * (v / MAX_PERCENT)
        return position.toFloat().coerceIn(0f, 1f)
    }

    fun formatPercent(percent: Double): String = String.format(java.util.Locale.US, "%+.2f%%", normalize(percent))
}

/** The one place a raw kWh becomes the corrected, displayed kWh. */
fun applyEnergyAdjustment(kwh: Double, percent: Double): Double = kwh * EnergyAdjustment.factor(percent)

/** deviceId -> adjustment percent for every device that has one saved. */
val LocalEnergyAdjustments = staticCompositionLocalOf<Map<String, Double>> { emptyMap() }
