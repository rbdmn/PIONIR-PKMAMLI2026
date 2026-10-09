package com.smartplug.app.util

import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.SmartPlugDevice
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Pure calculations behind the Storage tab, kept free of Android types so they are unit-testable. */

/**
 * The Storage tab exists only while at least one saved SmartPlug is stored in SERVER mode and that
 * server is still registered in this app (unpairing a server removes its token, so its data is gone).
 * Reachability is deliberately not part of the rule.
 */
fun shouldShowStorageTab(devices: List<SmartPlugDevice>, registeredServerIds: Set<String>): Boolean =
    devices.any { it.integrationMode == IntegrationMode.SERVER && it.serverId in registeredServerIds }

/** Result of [StorageResolution.choose]. [raised] = the first choice was too old for its retention. */
data class ResolutionChoice(val resolution: HistoryResolution, val raised: Boolean)

object StorageResolution {
    private const val DAY_MS = 86_400_000L

    /** design.md "Retensi riwayat dan kapasitas"; null = as long as the SD card allows. */
    fun retentionMs(resolution: HistoryResolution): Long? = when (resolution) {
        HistoryResolution.ONE_SECOND -> 30 * DAY_MS
        HistoryResolution.ONE_MINUTE -> 90 * DAY_MS
        HistoryResolution.FIVE_MINUTES -> 365 * DAY_MS
        HistoryResolution.THIRTY_MINUTES -> 365 * DAY_MS
        HistoryResolution.ONE_HOUR -> 5 * 365 * DAY_MS
        HistoryResolution.ONE_DAY -> null
    }

    /** <= 2 days -> 5m, <= 60 days -> 1h, otherwise 1d. Never the raw per-500 ms samples. */
    fun byDuration(durationMs: Long): HistoryResolution = when {
        durationMs <= 2 * DAY_MS -> HistoryResolution.FIVE_MINUTES
        durationMs <= 60 * DAY_MS -> HistoryResolution.ONE_HOUR
        else -> HistoryResolution.ONE_DAY
    }

    /** Picks by duration, then moves to a coarser resolution while [fromMs] is older than its retention. */
    fun choose(fromMs: Long, toMs: Long, nowMs: Long): ResolutionChoice {
        var resolution = byDuration((toMs - fromMs).coerceAtLeast(0L))
        var raised = false
        while (true) {
            val retention = retentionMs(resolution) ?: break
            if (fromMs >= nowMs - retention) break
            resolution = when (resolution) {
                HistoryResolution.ONE_MINUTE -> HistoryResolution.FIVE_MINUTES
                HistoryResolution.FIVE_MINUTES, HistoryResolution.THIRTY_MINUTES -> HistoryResolution.ONE_HOUR
                else -> HistoryResolution.ONE_DAY
            }
            raised = true
        }
        return ResolutionChoice(resolution, raised)
    }
}

/** Calendar-day helpers in a given zone (the phone's by default). */
object StorageDays {
    fun startOfDay(ms: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun startOfMonth(ms: Long, monthsBack: Int = 0, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = ms
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MONTH, -monthsBack)
        return cal.timeInMillis
    }

    /**
     * Material date pickers report a date as UTC midnight; this is the same calendar date at local
     * midnight, so the chosen day does not shift by the phone's UTC offset.
     */
    fun localStartOfPickedUtcDay(utcMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
        val local = Calendar.getInstance(zone)
        local.clear()
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        return local.timeInMillis
    }

    /** Start-of-day instants for every calendar day touched by [fromMs]..[toMs] (inclusive). */
    fun daysBetween(fromMs: Long, toMs: Long, zone: TimeZone = TimeZone.getDefault()): List<Long> {
        if (toMs < fromMs) return emptyList()
        val days = ArrayList<Long>()
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = startOfDay(fromMs, zone)
        val last = startOfDay(toMs, zone)
        while (cal.timeInMillis <= last && days.size < 4000) {
            days.add(cal.timeInMillis)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return days
    }
}

data class PeakInfo(val watts: Double, val atUtcMs: Long)

data class DayEnergy(val dayStartMs: Long, val kwh: Double, val hasData: Boolean)

/** Analysis of one device, or of the sum of all devices ([deviceId] == null). */
data class StorageAnalysis(
    val deviceId: String?,
    val totalKwh: Double,
    val averageW: Double,
    val peak: PeakInfo?,
    val days: List<DayEnergy>,
    val recordCount: Int,
) {
    val daysWithData: Int get() = days.count { it.hasData }
    val gapDays: Int get() = days.count { !it.hasData }
}

object StorageAnalyzer {
    /**
     * Energy is the sum of positive deltas of the cumulative counter (a drop means the counter was
     * reset, so that step is skipped rather than counted negative); each delta belongs to the
     * calendar day of the later point. Points are sorted first.
     */
    fun analyze(
        deviceId: String?,
        points: List<EnergyHistoryPoint>,
        fromMs: Long,
        toMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
        trimLeadingGaps: Boolean = false,
    ): StorageAnalysis {
        val sorted = points.sortedBy { it.timestampUtcMs }
        val dayKwh = HashMap<Long, Double>()
        val dayHasData = HashSet<Long>()
        var total = 0.0
        sorted.forEachIndexed { i, point ->
            val day = StorageDays.startOfDay(point.timestampUtcMs, zone)
            dayHasData.add(day)
            if (i > 0) {
                val delta = (point.energyWh - sorted[i - 1].energyWh) / 1000.0
                if (delta > 0.0 && delta.isFinite()) {
                    total += delta
                    dayKwh[day] = (dayKwh[day] ?: 0.0) + delta
                }
            }
        }
        val rangeStart = if (sorted.isNotEmpty()) minOf(fromMs, sorted.first().timestampUtcMs) else fromMs
        val rangeEnd = if (sorted.isNotEmpty()) maxOf(toMs.coerceAtLeast(fromMs), sorted.last().timestampUtcMs) else toMs
        // For "All" the range starts long before the first record; those leading days are not gaps.
        val firstDay = if (trimLeadingGaps && sorted.isNotEmpty()) StorageDays.startOfDay(sorted.first().timestampUtcMs, zone) else null
        val days = StorageDays.daysBetween(rangeStart, rangeEnd, zone)
            .filter { firstDay == null || it >= firstDay }
            .let { if (sorted.isEmpty()) emptyList() else it }
            .map { DayEnergy(it, dayKwh[it] ?: 0.0, it in dayHasData) }
        val peak = sorted.maxByOrNull { it.activePowerW }?.takeIf { it.activePowerW > 0.0 }
            ?.let { PeakInfo(it.activePowerW, it.timestampUtcMs) }
        return StorageAnalysis(
            deviceId = deviceId,
            totalKwh = total,
            averageW = if (sorted.isEmpty()) 0.0 else sorted.map { it.activePowerW }.average(),
            peak = peak,
            days = days,
            recordCount = sorted.size,
        )
    }

    /**
     * Sum of all devices: energy and per-day kWh add up; power is summed per timestamp (devices run
     * concurrently), so the average is the average of that combined series and the peak is its maximum.
     */
    fun combine(
        perDevice: List<StorageAnalysis>,
        perDevicePoints: List<List<EnergyHistoryPoint>>,
    ): StorageAnalysis {
        val dayKwh = HashMap<Long, Double>()
        val dayHasData = HashMap<Long, Boolean>()
        perDevice.forEach { a ->
            a.days.forEach { d ->
                dayKwh[d.dayStartMs] = (dayKwh[d.dayStartMs] ?: 0.0) + d.kwh
                if (d.hasData) dayHasData[d.dayStartMs] = true else dayHasData.putIfAbsent(d.dayStartMs, false)
            }
        }
        val combinedPower = HashMap<Long, Double>()
        perDevicePoints.forEach { pts -> pts.forEach { combinedPower[it.timestampUtcMs] = (combinedPower[it.timestampUtcMs] ?: 0.0) + it.activePowerW } }
        val peakEntry = combinedPower.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }
        return StorageAnalysis(
            deviceId = null,
            totalKwh = perDevice.sumOf { it.totalKwh },
            averageW = if (combinedPower.isEmpty()) 0.0 else combinedPower.values.average(),
            peak = peakEntry?.let { PeakInfo(it.value, it.key) },
            days = dayKwh.keys.sorted().map { DayEnergy(it, dayKwh.getValue(it), dayHasData[it] == true) },
            recordCount = perDevice.sumOf { it.recordCount },
        )
    }

    /** Share of [part] in [whole] as 0..100; 0 when there is nothing to share. */
    fun contributionPercent(part: Double, whole: Double): Double =
        if (whole <= 0.0 || !whole.isFinite()) 0.0 else (part / whole * 100.0).coerceIn(0.0, 100.0)
}

/** This month vs last month for one series (global or one device). */
data class MonthComparison(val thisMonthKwh: Double, val lastMonthKwh: Double) {
    val deltaKwh: Double get() = thisMonthKwh - lastMonthKwh

    /** Null when last month is zero (a percentage would be meaningless). */
    val percentChange: Double? get() = if (lastMonthKwh > 0.0) deltaKwh / lastMonthKwh * 100.0 else null
}

object StorageComparison {
    /** [points] must cover at least the start of last month up to now. */
    fun compare(
        points: List<EnergyHistoryPoint>,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): MonthComparison {
        val thisStart = StorageDays.startOfMonth(nowMs, 0, zone)
        val lastStart = StorageDays.startOfMonth(nowMs, 1, zone)
        var thisKwh = 0.0
        var lastKwh = 0.0
        val sorted = points.sortedBy { it.timestampUtcMs }
        for (i in 1 until sorted.size) {
            val delta = (sorted[i].energyWh - sorted[i - 1].energyWh) / 1000.0
            if (delta <= 0.0 || !delta.isFinite()) continue
            val t = sorted[i].timestampUtcMs
            when {
                t >= thisStart -> thisKwh += delta
                t >= lastStart -> lastKwh += delta
            }
        }
        return MonthComparison(thisKwh, lastKwh)
    }
}

/** One locally remembered SD usage observation. */
data class SdObservation(val timestampMs: Long, val usedBytes: Long, val totalBytes: Long)

sealed interface FillEstimate {
    data object NotEnoughData : FillEstimate
    data object NotGrowing : FillEstimate
    data class Days(val days: Double) : FillEstimate
}

object SdFillEstimator {
    private const val MIN_SPAN_MS = 86_400_000L
    const val WARN_FRACTION = 0.85

    fun isNearlyFull(usedBytes: Long?, totalBytes: Long?): Boolean =
        usedBytes != null && totalBytes != null && totalBytes > 0 && usedBytes.toDouble() / totalBytes > WARN_FRACTION

    /** Linear rate between the first and last observation; needs two points >= 1 day apart. */
    fun estimate(observations: List<SdObservation>, usedNow: Long?, totalNow: Long?): FillEstimate {
        val sorted = observations.sortedBy { it.timestampMs }
        if (sorted.size < 2 || usedNow == null || totalNow == null || totalNow <= 0) return FillEstimate.NotEnoughData
        val first = sorted.first()
        val last = sorted.last()
        val span = last.timestampMs - first.timestampMs
        if (span < MIN_SPAN_MS) return FillEstimate.NotEnoughData
        val perMs = (last.usedBytes - first.usedBytes).toDouble() / span
        if (perMs <= 0.0) return FillEstimate.NotGrowing
        val remaining = (totalNow - usedNow).coerceAtLeast(0L)
        return FillEstimate.Days(remaining / perMs / TimeUnit.DAYS.toMillis(1))
    }
}

/** CSV for the share sheet: one row per history point per device plus a TOTAL row set per timestamp. */
object StorageCsv {
    const val HEADER =
        "scope,device_id,device_name,timestamp_utc,timestamp_local,energy_kwh,avg_power_w,peak_power_w"

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun fmt(v: Double) = String.format(Locale.US, "%.4f", v)

    /**
     * Rows are per recorded point: kWh is that point's counter delta, W is the point's active power
     * (the server aggregates per bucket, so avg and peak are the bucket values the server returned).
     * [seriesByDevice] values are already display-adjusted by the caller.
     */
    fun build(
        names: Map<String, String>,
        seriesByDevice: Map<String, List<EnergyHistoryPoint>>,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
        val sb = StringBuilder(HEADER).append("\r\n")
        val perTimestamp = java.util.TreeMap<Long, DoubleArray>() // [kwh, w]
        seriesByDevice.forEach { (id, pts) ->
            val sorted = pts.sortedBy { it.timestampUtcMs }
            sorted.forEachIndexed { i, p ->
                val kwh = if (i == 0) 0.0 else ((p.energyWh - sorted[i - 1].energyWh) / 1000.0).coerceAtLeast(0.0)
                val date = Date(p.timestampUtcMs)
                sb.append("device,").append(escape(id)).append(',').append(escape(names[id] ?: id)).append(',')
                    .append(utc.format(date)).append(',').append(local.format(date)).append(',')
                    .append(fmt(kwh)).append(',').append(fmt(p.activePowerW)).append(',').append(fmt(p.activePowerW)).append("\r\n")
                val acc = perTimestamp.getOrPut(p.timestampUtcMs) { DoubleArray(2) }
                acc[0] += kwh; acc[1] += p.activePowerW
            }
        }
        if (seriesByDevice.size > 1) {
            perTimestamp.forEach { (ts, acc) ->
                val date = Date(ts)
                sb.append("total,,ALL,").append(utc.format(date)).append(',').append(local.format(date)).append(',')
                    .append(fmt(acc[0])).append(',').append(fmt(acc[1])).append(',').append(fmt(acc[1])).append("\r\n")
            }
        }
        return sb.toString()
    }

    /** True when there is at least one data row to export. */
    fun hasData(seriesByDevice: Map<String, List<EnergyHistoryPoint>>): Boolean = seriesByDevice.values.any { it.isNotEmpty() }

    /** `smartplug_<global|deviceId>_<from>_<to>.csv` with a filesystem-safe id and local dates. */
    fun fileName(scope: String?, fromMs: Long, toMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }
        val safe = (scope ?: "global").replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "smartplug_${safe}_${day.format(Date(fromMs))}_${day.format(Date(toMs))}.csv"
    }
}

/** One bar of the kWh-per-day chart; a bar can span several days when the range is long. */
data class DayBar(val startMs: Long, val days: Int, val kwh: Double)

/** Groups consecutive days into at most [maxBars] bars so a long range stays readable. */
fun barsForDays(days: List<DayEnergy>, maxBars: Int = 14): List<DayBar> {
    if (days.isEmpty() || maxBars <= 0) return emptyList()
    val per = (days.size + maxBars - 1) / maxBars
    return days.chunked(per).map { chunk -> DayBar(chunk.first().dayStartMs, chunk.size, chunk.sumOf { it.kwh }) }
}
