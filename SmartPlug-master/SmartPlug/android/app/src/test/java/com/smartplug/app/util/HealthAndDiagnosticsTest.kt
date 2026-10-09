package com.smartplug.app.util

import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.remote.dto.DeviceStatusDto
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.PowerOnPolicy
import com.smartplug.app.domain.model.ProtectionStatus
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.squareup.moshi.Moshi
import org.junit.Test

class HealthAndDiagnosticsTest {
    // ---- password gate (cheap iteration count so the test is fast; the real constants are checked below)
    @Test fun hashVerificationAcceptsOnlyTheRightPassword() {
        val saltHex = "000102030405060708090a0b0c0d0e0f"
        val derived = java.util.HexFormat.of().formatHex(
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(
                javax.crypto.spec.PBEKeySpec("secret".toCharArray(), java.util.HexFormat.of().parseHex(saltHex), 1000, 256),
            ).encoded,
        )
        assertThat(DiagnosticsGate.verify("secret", saltHex, derived, 1000)).isTrue()
        assertThat(DiagnosticsGate.verify("Secret", saltHex, derived, 1000)).isFalse()
        assertThat(DiagnosticsGate.verify("", saltHex, derived, 1000)).isFalse()
    }

    @Test fun realGateRejectsWrongAndAcceptsConfiguredPassword() {
        assertThat(DiagnosticsGate.verify("wrong")).isFalse()
        assertThat(DiagnosticsGate.verify("")).isFalse()
        assertThat(DiagnosticsGate.verify("deviotsolution")).isTrue()
    }

    @Test fun fiveWrongAttemptsLockForThirtySeconds() {
        var now = 1_000L
        val limiter = AttemptLimiter { now }
        repeat(4) { limiter.recordFailure(); assertThat(limiter.canTry()).isTrue() }
        limiter.recordFailure()
        assertThat(limiter.canTry()).isFalse()
        assertThat(limiter.lockedForMs()).isEqualTo(DiagnosticsGate.LOCK_MS)
        now += DiagnosticsGate.LOCK_MS - 1
        assertThat(limiter.canTry()).isFalse()
        now += 1
        assertThat(limiter.canTry()).isTrue()
        limiter.recordFailure()          // counting starts over after the lock
        assertThat(limiter.canTry()).isTrue()
    }

    @Test fun successResetsTheFailureCount() {
        val limiter = AttemptLimiter { 0L }
        repeat(4) { limiter.recordFailure() }
        limiter.recordSuccess()
        repeat(4) { limiter.recordFailure() }
        assertThat(limiter.canTry()).isTrue()
    }

    @Test fun fiveTapsWithinThreeSecondsOpenTheGate() {
        var now = 0L
        val taps = TapSequence { now }
        repeat(4) { assertThat(taps.tap()).isFalse(); now += 500 }
        assertThat(taps.tap()).isTrue()
        // slow taps never complete
        now += 10_000
        repeat(10) { assertThat(taps.tap()).isFalse(); now += 1_500 }
    }

    // ---- uptime + overcurrent
    @Test fun uptimeFormats() {
        assertThat(formatUptime(null)).isEqualTo("–")
        assertThat(formatUptime(-1)).isEqualTo("–")
        assertThat(formatUptime(5)).isEqualTo("5s")
        assertThat(formatUptime(65)).isEqualTo("1m 05s")
        assertThat(formatUptime(2 * 3600 + 14 * 60 + 5)).isEqualTo("2h 14m 05s")
        assertThat(formatUptime(3 * 86_400L + 2 * 3600 + 14 * 60)).isEqualTo("3d 2h 14m")
    }

    @Test fun overcurrentWarningBoundaryIsExactlyTwoAmps() {
        assertThat(Overcurrent.isWarning(2.0)).isFalse()
        assertThat(Overcurrent.isWarning(2.01)).isTrue()
        assertThat(Overcurrent.isWarning(0.5)).isFalse()
        assertThat(Overcurrent.isWarning(Double.NaN)).isFalse()
        assertThat(Overcurrent.isWarning(0.5, firmwareFlag = true)).isTrue()
        assertThat(Overcurrent.isWarning(3.0, firmwareFlag = false)).isTrue() // measured value still counts
    }

    // ---- tolerant DTO
    private val adapter = Moshi.Builder().build().adapter(DeviceStatusDto::class.java)

    @Test fun oldFirmwareStatusWithoutNewFieldsStillParses() {
        val dto = adapter.fromJson("""{"api_version":"1.0","device_id":"SP-1","relay_state":"on"}""")!!
        assertThat(dto.uptimeSeconds).isNull()
        assertThat(dto.protection).isNull()
        assertThat(dto.powerOnPolicy).isNull()
        assertThat(dto.overcurrentWarning).isNull()
    }

    @Test fun newFirmwareFieldsAreParsed() {
        val dto = adapter.fromJson(
            """{"api_version":"1.0","device_id":"SP-1","relay_state":"off","uptime_s":7265,"reset_reason":"power_on",
               "boot_count":12,"power_on_policy":"last","restore_delay_s":30,"overcurrent_warning":true,
               "firmware":{"version":"R3.10.11-ssr"},
               "protection":{"enabled":true,"tripped":false,"warn_a":2.0,"trip_a":4.0},"something_new":1}""",
        )!!
        assertThat(dto.uptimeSeconds).isEqualTo(7265L)
        assertThat(dto.bootCount).isEqualTo(12L)
        assertThat(PowerOnPolicy.fromWire(dto.powerOnPolicy)).isEqualTo(PowerOnPolicy.LAST)
        assertThat(dto.protection!!.tripA).isEqualTo(4.0)
        assertThat(dto.firmware!!.version).isEqualTo("R3.10.11-ssr")
        assertThat(PowerOnPolicy.fromWire("bogus")).isNull()
    }

    // ---- report
    private val device = SmartPlugDevice("SP-1", "mac", "Lampu", "192.168.1.9", IntegrationMode.DIRECT)

    @Test fun reportUsesDashesForUnknownFieldsAndNeverLeaksSecrets() {
        val text = DiagnosticsReport.build(device, null, null, null)
        assertThat(text).contains("Perangkat: Lampu (SP-1)")
        assertThat(text).contains("Menyala sejak: –")
        assertThat(text.lowercase()).doesNotContain("token")
        assertThat(text.lowercase()).doesNotContain("password")
    }

    @Test fun reportShowsReportedValues() {
        val status = DeviceStatus(
            "SP-1", RelayState.ON, true, true, true, true, 2_000,
            firmwareVersion = "R3.10", uptimeSeconds = 3_700, resetReason = "power_on", bootCount = 3,
            powerOnPolicy = PowerOnPolicy.LAST, protection = ProtectionStatus(enabled = true, tripped = true),
        )
        val text = DiagnosticsReport.build(device, ConnectionTest(true, 42), status, 80)
        assertThat(text).contains("Koneksi: OK (42 ms)")
        assertThat(text).contains("Sinyal Wi-Fi HP: 80%")
        assertThat(text).contains("Menyala sejak: 1h 1m 40s")
        assertThat(text).contains("Proteksi beban: aktif, sempat memutus")
        assertThat(text).contains("Saat listrik kembali: Seperti sebelum mati")
        val failed = DiagnosticsReport.build(device, ConnectionTest(false, 0, DiagnosticsReport.plainReason("network_timeout")), null, null)
        assertThat(failed).contains("Gagal: SmartPlug tidak menjawab")
    }

    // ---- daily summary
    private fun p(t: Long, wh: Double, w: Double) = EnergyHistoryPoint(t, 230.0, 0.1, w, w, 1.0, wh)

    @Test fun dailySummaryCompareWithSameHourYesterday() {
        val day = 86_400_000L
        val todayStart = 10 * day
        val now = todayStart + 6 * 3_600_000L // 06:00
        val points = mapOf(
            "a" to listOf(
                p(todayStart - day + 3_600_000L, 0.0, 5.0),
                p(todayStart - day + 4 * 3_600_000L, 1000.0, 50.0), // yesterday 04:00 (+1.0 kWh, before the cutoff)
                p(todayStart - day + 20 * 3_600_000L, 5000.0, 80.0), // yesterday evening: must NOT count
                p(todayStart + 1 * 3_600_000L, 5400.0, 40.0),
                p(todayStart + 5 * 3_600_000L, 6400.0, 120.0), // today +1.4 kWh in total
            ),
        )
        val summary = DailySummaryCalc.compute(points, todayStart, now)!!
        assertThat(summary.todayKwh).isWithin(1e-9).of(1.4)
        assertThat(summary.yesterdayKwh).isWithin(1e-9).of(1.0)
        assertThat(summary.changePercent).isWithin(1e-9).of(40.0)
        assertThat(summary.topDeviceId).isEqualTo("a")
        assertThat(summary.peakWatts).isEqualTo(120.0)
        assertThat(summary.peakAtMs).isEqualTo(todayStart + 5 * 3_600_000L)
        assertThat(summary.standbyWatts).isNull() // lowest power today (40 W) is not standby-sized
    }

    @Test fun dailySummaryStandbyAndEmptyData() {
        val todayStart = 1_000_000_000L
        val withStandby = mapOf("a" to listOf(p(todayStart + 10, 0.0, 3.0), p(todayStart + 20, 5.0, 60.0)))
        assertThat(DailySummaryCalc.compute(withStandby, todayStart, todayStart + 100)!!.standbyWatts).isEqualTo(3.0)
        assertThat(DailySummaryCalc.compute(emptyMap(), todayStart, todayStart + 100)).isNull()
        assertThat(DailySummaryCalc.compute(mapOf("a" to emptyList()), todayStart, todayStart + 100)).isNull()
        // no data from yesterday -> no comparison
        assertThat(DailySummaryCalc.compute(withStandby, todayStart, todayStart + 100)!!.changePercent).isNull()
    }

    @Test fun counterResetDoesNotGoNegative() {
        val todayStart = 5_000_000_000L
        val points = mapOf("a" to listOf(p(todayStart + 1, 9000.0, 10.0), p(todayStart + 2, 100.0, 10.0), p(todayStart + 3, 600.0, 10.0)))
        assertThat(DailySummaryCalc.compute(points, todayStart, todayStart + 10)!!.todayKwh).isWithin(1e-9).of(0.5)
    }
}
