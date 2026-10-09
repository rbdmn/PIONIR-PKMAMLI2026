package com.smartplug.app.storage

import com.google.common.truth.Truth.assertThat
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.util.DayEnergy
import com.smartplug.app.util.FillEstimate
import com.smartplug.app.util.SdFillEstimator
import com.smartplug.app.util.SdObservation
import com.smartplug.app.util.StorageAnalyzer
import com.smartplug.app.util.StorageComparison
import com.smartplug.app.util.StorageCsv
import com.smartplug.app.util.StorageDays
import com.smartplug.app.util.StorageResolution
import com.smartplug.app.util.barsForDays
import com.smartplug.app.ui.screens.storage.formatBytes
import com.smartplug.app.util.shouldShowStorageTab
import org.junit.Test
import java.util.TimeZone

class StorageAnalysisTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L

    // 2026-03-10T00:00:00Z
    private val d0 = 1_773_100_800_000L

    private fun p(t: Long, wh: Double, w: Double = 10.0) =
        EnergyHistoryPoint(t, 230.0, 0.1, w, w, 1.0, wh)

    private fun device(id: String, mode: IntegrationMode) =
        SmartPlugDevice(id, "mac", id, null, mode)

    private fun serverDevice(id: String, serverId: String) =
        SmartPlugDevice(id, "mac", id, null, IntegrationMode.SERVER, serverId, "srv.local")

    @Test fun tabVisibilityFollowsSavedModeOnly() {
        val known = setOf("srv")
        assertThat(shouldShowStorageTab(emptyList(), known)).isFalse()
        assertThat(shouldShowStorageTab(listOf(device("a", IntegrationMode.DIRECT)), known)).isFalse()
        assertThat(shouldShowStorageTab(listOf(device("a", IntegrationMode.DIRECT), serverDevice("b", "srv")), known)).isTrue()
        // Server unpaired from this phone: the device is still SERVER mode but the tab must go.
        assertThat(shouldShowStorageTab(listOf(serverDevice("b", "srv")), emptySet())).isFalse()
        assertThat(shouldShowStorageTab(listOf(serverDevice("b", "srv")), setOf("other"))).isFalse()
    }

    @Test fun resolutionByDuration() {
        assertThat(StorageResolution.byDuration(2 * day)).isEqualTo(HistoryResolution.FIVE_MINUTES)
        assertThat(StorageResolution.byDuration(2 * day + 1)).isEqualTo(HistoryResolution.ONE_HOUR)
        assertThat(StorageResolution.byDuration(60 * day)).isEqualTo(HistoryResolution.ONE_HOUR)
        assertThat(StorageResolution.byDuration(61 * day)).isEqualTo(HistoryResolution.ONE_DAY)
    }

    @Test fun resolutionRaisedWhenOlderThanRetention() {
        val now = d0 + 800 * day
        // 1 day range but 2 years ago: 5m (1 year) is too fine -> 1h (5 years).
        val old = StorageResolution.choose(now - 730 * day, now - 729 * day, now)
        assertThat(old.resolution).isEqualTo(HistoryResolution.ONE_HOUR)
        assertThat(old.raised).isTrue()
        val recent = StorageResolution.choose(now - day, now, now)
        assertThat(recent.resolution).isEqualTo(HistoryResolution.FIVE_MINUTES)
        assertThat(recent.raised).isFalse()
        // 10 years ago, 1 day wide: past 1h retention too -> 1d.
        assertThat(StorageResolution.choose(now - 3650 * day, now - 3649 * day, now).resolution).isEqualTo(HistoryResolution.ONE_DAY)
    }

    @Test fun totalsAveragePeakAndPerDay() {
        val pts = listOf(
            p(d0 + 1000, 1000.0, 10.0),
            p(d0 + 2000, 1500.0, 50.0),
            p(d0 + day + 1000, 2500.0, 20.0),
        )
        val a = StorageAnalyzer.analyze("x", pts, d0, d0 + day + 5000, utc)
        assertThat(a.totalKwh).isWithin(1e-9).of(1.5)
        assertThat(a.averageW).isWithin(1e-9).of(80.0 / 3)
        assertThat(a.peak!!.watts).isEqualTo(50.0)
        assertThat(a.peak!!.atUtcMs).isEqualTo(d0 + 2000)
        assertThat(a.recordCount).isEqualTo(3)
        assertThat(a.days.map { it.kwh }).containsExactly(0.5, 1.0).inOrder()
    }

    @Test fun counterResetIsNotCountedNegative() {
        val pts = listOf(p(d0 + 1, 5000.0), p(d0 + 2, 100.0), p(d0 + 3, 600.0))
        assertThat(StorageAnalyzer.analyze("x", pts, d0, d0 + 10, utc).totalKwh).isWithin(1e-9).of(0.5)
    }

    @Test fun emptyDataDoesNotCrash() {
        val a = StorageAnalyzer.analyze("x", emptyList(), d0, d0 + day, utc)
        assertThat(a.totalKwh).isEqualTo(0.0)
        assertThat(a.peak).isNull()
        assertThat(a.days).isEmpty()
        val g = StorageAnalyzer.combine(listOf(a), listOf(emptyList()))
        assertThat(g.recordCount).isEqualTo(0)
        assertThat(g.peak).isNull()
    }

    @Test fun gapDaysAreDetected() {
        val pts = listOf(p(d0 + 100, 0.0), p(d0 + 2 * day + 100, 1000.0))
        val a = StorageAnalyzer.analyze("x", pts, d0, d0 + 2 * day + 200, utc)
        assertThat(a.days.map { it.hasData }).containsExactly(true, false, true).inOrder()
        assertThat(a.gapDays).isEqualTo(1)
        assertThat(a.daysWithData).isEqualTo(2)
    }

    @Test fun allPeriodDoesNotCountLeadingDaysAsGaps() {
        val pts = listOf(p(d0 + 100, 0.0), p(d0 + 200, 100.0))
        val a = StorageAnalyzer.analyze("x", pts, d0 - 100 * day, d0 + 300, utc, trimLeadingGaps = true)
        assertThat(a.gapDays).isEqualTo(0)
    }

    @Test fun combineAndContribution() {
        val ptsA = listOf(p(d0 + 1, 0.0, 10.0), p(d0 + 2, 1000.0, 10.0))
        val ptsB = listOf(p(d0 + 1, 0.0, 30.0), p(d0 + 2, 3000.0, 30.0))
        val a = StorageAnalyzer.analyze("a", ptsA, d0, d0 + 10, utc)
        val b = StorageAnalyzer.analyze("b", ptsB, d0, d0 + 10, utc)
        val g = StorageAnalyzer.combine(listOf(a, b), listOf(ptsA, ptsB))
        assertThat(g.totalKwh).isWithin(1e-9).of(4.0)
        assertThat(g.averageW).isWithin(1e-9).of(40.0)
        assertThat(g.peak!!.watts).isEqualTo(40.0)
        assertThat(StorageAnalyzer.contributionPercent(a.totalKwh, g.totalKwh)).isWithin(1e-9).of(25.0)
        assertThat(StorageAnalyzer.contributionPercent(1.0, 0.0)).isEqualTo(0.0)
    }

    @Test fun monthComparison() {
        val now = d0 + 5 * day // mid March 2026 (UTC)
        val thisStart = StorageDays.startOfMonth(now, 0, utc)
        val lastStart = StorageDays.startOfMonth(now, 1, utc)
        val pts = listOf(
            p(lastStart + day, 0.0), p(lastStart + 2 * day, 2000.0),
            p(thisStart + day, 3000.0), p(thisStart + 2 * day, 4000.0),
        )
        val c = StorageComparison.compare(pts, now, utc)
        // 0->2000 lands in last month (2 kWh); 2000->3000 and 3000->4000 land in this month (2 kWh).
        assertThat(c.lastMonthKwh).isWithin(1e-9).of(2.0)
        assertThat(c.thisMonthKwh).isWithin(1e-9).of(2.0)
        assertThat(c.deltaKwh).isWithin(1e-9).of(0.0)
        assertThat(c.percentChange).isWithin(1e-9).of(0.0)
        assertThat(StorageComparison.compare(emptyList(), now, utc).percentChange).isNull()
    }

    @Test fun fillEstimate() {
        val t = 1_000_000_000_000L
        assertThat(SdFillEstimator.estimate(emptyList(), 10, 100)).isEqualTo(FillEstimate.NotEnoughData)
        val sameDay = listOf(SdObservation(t, 10, 100), SdObservation(t + day / 2, 20, 100))
        assertThat(SdFillEstimator.estimate(sameDay, 20, 100)).isEqualTo(FillEstimate.NotEnoughData)
        val ok = listOf(SdObservation(t, 10, 100), SdObservation(t + 2 * day, 30, 100))
        val est = SdFillEstimator.estimate(ok, 30, 100) as FillEstimate.Days
        assertThat(est.days).isWithin(1e-9).of(7.0) // 10 units/day, 70 left
        val flat = listOf(SdObservation(t, 30, 100), SdObservation(t + 2 * day, 30, 100))
        assertThat(SdFillEstimator.estimate(flat, 30, 100)).isEqualTo(FillEstimate.NotGrowing)
        assertThat(SdFillEstimator.isNearlyFull(86, 100)).isTrue()
        assertThat(SdFillEstimator.isNearlyFull(85, 100)).isFalse()
        assertThat(SdFillEstimator.isNearlyFull(null, null)).isFalse()
    }

    @Test fun csvHeaderRowsAndEmpty() {
        val series = mapOf("SP-1" to listOf(p(d0, 0.0, 5.0), p(d0 + 60_000, 1000.0, 7.0)))
        val csv = StorageCsv.build(mapOf("SP-1" to "Lampu, \"A\""), series, utc)
        val lines = csv.trim().split("\r\n")
        assertThat(lines[0]).isEqualTo(StorageCsv.HEADER)
        assertThat(lines).hasSize(3)
        assertThat(lines[2]).startsWith("device,SP-1,\"Lampu, \"\"A\"\"\",2026-03-10T00:01:00Z,2026-03-10 00:01:00,1.0000,7.0000,7.0000")
        assertThat(StorageCsv.hasData(mapOf("a" to emptyList()))).isFalse()
        assertThat(StorageCsv.hasData(series)).isTrue()
    }

    @Test fun csvTotalRowsOnlyForMultipleDevices() {
        val two = mapOf("a" to listOf(p(d0, 0.0, 1.0)), "b" to listOf(p(d0, 0.0, 2.0)))
        assertThat(StorageCsv.build(emptyMap(), two, utc)).contains("total,,ALL,")
        assertThat(StorageCsv.build(emptyMap(), mapOf("a" to listOf(p(d0, 0.0))), utc)).doesNotContain("total,,ALL")
    }

    @Test fun csvFileName() {
        assertThat(StorageCsv.fileName(null, d0, d0 + 2 * day, utc)).isEqualTo("smartplug_global_20260310_20260312.csv")
        assertThat(StorageCsv.fileName("SP-84F3/EB", d0, d0, utc)).isEqualTo("smartplug_SP-84F3_EB_20260310_20260310.csv")
    }

    @Test fun barsGroupLongRanges() {
        val days = (0 until 30).map { DayEnergy(d0 + it * day, 1.0, true) }
        val bars = barsForDays(days, 14)
        assertThat(bars.size).isAtMost(14)
        assertThat(bars.sumOf { it.kwh }).isWithin(1e-9).of(30.0)
        assertThat(barsForDays(emptyList())).isEmpty()
    }

    @Test fun pickedUtcDayMapsToLocalMidnight() {
        val tz = TimeZone.getTimeZone("Asia/Jakarta")
        val local = StorageDays.localStartOfPickedUtcDay(d0, tz)
        assertThat(local).isEqualTo(d0 - 7 * 3_600_000L)
    }
}

class FormatBytesTest {
    @org.junit.Test fun adaptiveUnits() {
        assertThat(formatBytes(0)).isEqualTo("0")
        assertThat(formatBytes(512)).isEqualTo("512 B")
        assertThat(formatBytes(1536)).isEqualTo("1,5 KB")
        assertThat(formatBytes(5L * 1024 * 1024)).isEqualTo("5,0 MB")
        assertThat(formatBytes(125_829_120_000L)).isEqualTo("117,2 GB")
    }
}

class SharePercentTest {
    @org.junit.Test fun percentText() {
        assertThat(com.smartplug.app.ui.screens.storage.sharePercentText(0, 1000)).isEqualTo(" · 0%")
        assertThat(com.smartplug.app.ui.screens.storage.sharePercentText(250, 1000)).isEqualTo(" · 25,00% / total")
        assertThat(com.smartplug.app.ui.screens.storage.sharePercentText(1, 1_000_000)).isEqualTo(" · < 0,01% / total")
        assertThat(com.smartplug.app.ui.screens.storage.sharePercentText(5, null)).isEqualTo("")
    }
}
