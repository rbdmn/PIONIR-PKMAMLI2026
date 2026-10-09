package com.smartplug.app.util

/**
 * Backoff schedule for measurement polling failures, design.md "Interval pembacaan pengukuran":
 * "ulangi dengan jeda bertahap 2, 4, 8, 15, lalu 30 detik sampai koneksi kembali tersedia."
 */
object MeasurementBackoff {
    private val stepsSeconds = listOf(2L, 4L, 8L, 15L, 30L)

    fun delayForAttempt(consecutiveFailures: Int): Long {
        val index = (consecutiveFailures - 1).coerceIn(0, stepsSeconds.lastIndex)
        return stepsSeconds[index] * 1000
    }
}

/**
 * Broker/MQTT-facing reconnect backoff is firmware-owned (firmware/MQTT.md: 5, 10, 20, 40, 60s).
 * The app only mirrors it when polling ServerSmartPlug's `/latest` right after it reports a
 * device as `reconnecting`, so the UI doesn't hammer the server while SmartPlug itself is
 * backing off.
 */
object ReconnectBackoff {
    private val stepsSeconds = listOf(5L, 10L, 20L, 40L, 60L)

    fun delayForAttempt(consecutiveFailures: Int): Long {
        val index = (consecutiveFailures - 1).coerceIn(0, stepsSeconds.lastIndex)
        return stepsSeconds[index] * 1000
    }
}

enum class PollingCadence(val intervalMs: Long) {
    /** design.md: halaman monitoring biasa. */
    NORMAL(2_000),

    /** design.md: layar live/QC terbuka. */
    LIVE(1_000),

    /** ServerSmartPlug already holds a fresh MQTT snapshot every 500 ms. */
    SERVER_MONITORING(500),

    /** design.md: daftar perangkat tetap terlihat. */
    DEVICE_LIST(5_000),
}
