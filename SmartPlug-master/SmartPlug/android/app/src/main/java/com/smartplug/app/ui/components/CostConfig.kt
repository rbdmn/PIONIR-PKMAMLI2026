package com.smartplug.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.smartplug.app.util.Rupiah

/** Cost-estimate settings as the UI sees them: [active] only when switched on with a valid tariff. */
data class CostConfig(val enabled: Boolean = false, val tariffPerKwh: Double = 0.0) {
    val active: Boolean get() = enabled && tariffPerKwh.isFinite() && tariffPerKwh > 0.0

    fun format(kwh: Double): String = Rupiah.format(Rupiah.cost(kwh.coerceAtLeast(0.0), tariffPerKwh))
}

val LocalCostConfig = staticCompositionLocalOf { CostConfig() }

/** Whether the Home "today" summary card is switched on in Settings. */
val LocalDailySummaryEnabled = staticCompositionLocalOf { true }
