package com.smartplug.app.data.local.db

import androidx.room.Entity

/**
 * Cached history rows so [com.smartplug.app.ui.screens.history.EnergyHistoryScreen] can render
 * instantly on re-open and work briefly offline. ServerSmartPlug's SD-card store remains the
 * source of truth; this is a read cache only, keyed by (deviceId, resolution, timestamp).
 */
@Entity(tableName = "history_points", primaryKeys = ["deviceId", "resolution", "timestampUtcMs"])
data class HistoryPointEntity(
    val deviceId: String,
    val resolution: String,
    val timestampUtcMs: Long,
    val voltageV: Double,
    val currentA: Double,
    val activePowerW: Double,
    val apparentPowerVa: Double,
    val powerFactor: Double,
    val energyWh: Double,
)
