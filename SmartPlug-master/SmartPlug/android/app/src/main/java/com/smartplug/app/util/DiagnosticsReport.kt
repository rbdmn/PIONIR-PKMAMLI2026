package com.smartplug.app.util

import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.PowerOnPolicy
import com.smartplug.app.domain.model.SmartPlugDevice

/** Result of one "Test connection" run. */
data class ConnectionTest(
    val reachable: Boolean,
    val latencyMs: Long,
    /** Short, plain-language reason when [reachable] is false. */
    val failureReason: String? = null,
)

/** Plain-text report that is copied to the clipboard. Contains no credentials, tokens or passwords. */
object DiagnosticsReport {
    fun build(
        device: SmartPlugDevice,
        test: ConnectionTest?,
        status: DeviceStatus?,
        phoneSignalPercent: Int?,
    ): String {
        val dash = "–"
        val mode = if (device.integrationMode == IntegrationMode.SERVER) "Server" else "Direct"
        val address = (if (device.integrationMode == IntegrationMode.SERVER) device.serverHost else device.lanIp) ?: dash
        val connection = when {
            test == null -> dash
            test.reachable -> "OK (${test.latencyMs} ms)"
            else -> "Gagal: ${test.failureReason ?: "tidak diketahui"}"
        }
        val policy = when (status?.powerOnPolicy) {
            PowerOnPolicy.OFF -> "Tetap mati"
            PowerOnPolicy.LAST -> "Seperti sebelum mati"
            PowerOnPolicy.ON -> "Selalu menyala"
            null -> dash
        }
        val protection = status?.protection?.let {
            (if (it.enabled) "aktif" else "nonaktif") + if (it.tripped) ", sempat memutus" else ""
        } ?: dash
        val age = status?.takeIf { it.hasSample }?.let { "${it.sampleAgeMs / 1000} detik lalu" } ?: dash
        return listOf(
            "Laporan SmartPlug",
            "Perangkat: ${device.displayName} (${device.deviceId})",
            "Mode: $mode",
            "Alamat: $address",
            "Koneksi: $connection",
            "Sinyal Wi-Fi HP: ${phoneSignalPercent?.let { "$it%" } ?: dash}",
            "Data terakhir: $age",
            "Firmware: ${status?.firmwareVersion ?: dash}",
            "Menyala sejak: ${if (status?.uptimeSeconds != null) formatUptime(status.uptimeSeconds) else dash}",
            "Alasan restart: ${status?.resetReason ?: dash}",
            "Jumlah boot: ${status?.bootCount?.toString() ?: dash}",
            "Proteksi beban: $protection",
            "Saat listrik kembali: $policy",
        ).joinToString("\n")
    }

    /** One short sentence for the user; the raw code is never shown. */
    fun plainReason(errorCode: String): String = when (errorCode) {
        "network_timeout" -> "SmartPlug tidak menjawab (waktu habis)"
        "no_connectivity" -> "SmartPlug tidak terjangkau di jaringan"
        "missing_lan_ip", "missing_server_host" -> "Alamat SmartPlug belum diketahui"
        "missing_owner_token", "invalid_owner_token", "missing_server_token" -> "Akses ke SmartPlug tidak valid"
        else -> "Tidak bisa membaca SmartPlug ($errorCode)"
    }
}
