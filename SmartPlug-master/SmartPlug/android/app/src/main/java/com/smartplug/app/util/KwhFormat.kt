package com.smartplug.app.util

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** How many decimals every kWh value in the app shows (0..5); chosen in Settings. */
val LocalKwhDecimals = staticCompositionLocalOf { KwhFormat.DEFAULT_DECIMALS }

object KwhFormat {
    const val DEFAULT_DECIMALS = 3
    const val MAX_DECIMALS = 5

    fun clamp(decimals: Int) = decimals.coerceIn(0, MAX_DECIMALS)

    fun format(kwh: Double, decimals: Int): String = String.format(Locale.US, "%.${clamp(decimals)}f", kwh)

    /** "x", "x.x" ... "x.xxxxx" - what the setting chips show. */
    fun pattern(decimals: Int): String = if (clamp(decimals) == 0) "x" else "x." + "x".repeat(clamp(decimals))
}

fun formatKwhValue(kwh: Double, decimals: Int): String = KwhFormat.format(kwh, decimals)
