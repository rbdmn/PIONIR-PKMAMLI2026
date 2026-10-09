package com.smartplug.app.util

import com.smartplug.app.domain.model.EnergyHistoryPoint

/** What the Home "today" card shows. Every field except [todayKwh] may be unknown. */
data class DailySummary(
    val todayKwh: Double,
    /** Yesterday up to the same time of day, so a half-finished day is not compared with a full one. */
    val yesterdayKwh: Double?,
    val topDeviceId: String?,
    val topDeviceKwh: Double,
    val peakWatts: Double?,
    val peakAtMs: Long?,
    /** Lowest power seen today while something drew power, only when it is small enough to be standby. */
    val standbyWatts: Double?,
) {
    /** Percent change versus yesterday (same hour); null when yesterday is unknown or zero. */
    val changePercent: Double?
        get() = yesterdayKwh?.takeIf { it > 0.0 }?.let { (todayKwh - it) / it * 100.0 }
}

object DailySummaryCalc {
    const val STANDBY_MAX_WATTS = 10.0

    /**
     * [perDevice] holds each SmartPlug's history points covering yesterday 00:00 up to [nowMs]
     * (energy already corrected by the user's per-device adjustment). Energy is the sum of positive
     * counter steps (a counter reset is skipped), each step belonging to the time of its later point.
     */
    fun compute(perDevice: Map<String, List<EnergyHistoryPoint>>, todayStartMs: Long, nowMs: Long): DailySummary? {
        if (perDevice.values.all { it.isEmpty() }) return null
        val yesterdayStart = todayStartMs - DAY_MS
        val yesterdayCutoff = yesterdayStart + (nowMs - todayStartMs).coerceAtLeast(0L)
        fun kwh(points: List<EnergyHistoryPoint>, from: Long, to: Long): Double {
            val sorted = points.sortedBy { it.timestampUtcMs }
            var sum = 0.0
            for (i in 1 until sorted.size) {
                val t = sorted[i].timestampUtcMs
                if (t < from || t >= to) continue
                val delta = (sorted[i].energyWh - sorted[i - 1].energyWh) / 1000.0
                if (delta > 0.0 && delta.isFinite()) sum += delta
            }
            return sum
        }
        val todayByDevice = perDevice.mapValues { kwh(it.value, todayStartMs, nowMs + 1) }
        val yesterdayHasData = perDevice.values.any { points -> points.any { it.timestampUtcMs < todayStartMs } }
        val yesterday = if (yesterdayHasData) perDevice.values.sumOf { kwh(it, yesterdayStart, yesterdayCutoff) } else null
        val top = todayByDevice.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }

        val combined = HashMap<Long, Double>()
        perDevice.values.forEach { points ->
            points.filter { it.timestampUtcMs >= todayStartMs }.forEach {
                combined[it.timestampUtcMs] = (combined[it.timestampUtcMs] ?: 0.0) + it.activePowerW
            }
        }
        val peak = combined.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }
        val lowest = combined.values.filter { it > 0.0 }.minOrNull()
        return DailySummary(
            todayKwh = todayByDevice.values.sum(),
            yesterdayKwh = yesterday,
            topDeviceId = top?.key,
            topDeviceKwh = top?.value ?: 0.0,
            peakWatts = peak?.value,
            peakAtMs = peak?.key,
            standbyWatts = lowest?.takeIf { it <= STANDBY_MAX_WATTS },
        )
    }

    private const val DAY_MS = 86_400_000L
}
