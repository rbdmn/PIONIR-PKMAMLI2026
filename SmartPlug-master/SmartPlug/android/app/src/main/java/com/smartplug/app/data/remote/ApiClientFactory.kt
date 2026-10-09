package com.smartplug.app.data.remote

import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SmartPlug's own IP/hostname changes per device and ServerSmartPlug's per install, so Retrofit
 * clients can't be bound to one fixed base URL at DI-graph build time. This factory builds one
 * Retrofit instance per base URL and reuses it (LAN calls are frequent — polling every 1-2s —
 * so avoiding client re-creation on every request matters).
 *
 * Normal calls use an 8-second timeout; Server live-monitor reads use the separately documented
 * two-second deadline. Callers own their retry/backoff (see
 * [com.smartplug.app.util.RetryPolicy]) rather than OkHttp retrying silently underneath them.
 */
@Singleton
open class ApiClientFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
) {
    private val retrofitCache = ConcurrentHashMap<String, Retrofit>()

    // open: PairingRepositoryImplTest overrides this to redirect to a MockWebServer standing in
    // for the SmartPlug AP, keeping Android request/state mapping independent from device tests.
    open fun pairingApi(baseUrl: String = PAIRING_BASE_URL): PairingApi =
        retrofitFor(baseUrl).create(PairingApi::class.java)

    /**
     * A Wi-Fi scan runs in the ESP8266 radio driver and can take longer than normal local REST
     * calls when every 2.4 GHz channel must be visited.  Keep its longer deadline scoped to this
     * setup operation so status/relay calls still fail fast under a broken LAN connection.
     */
    open fun pairingScanApi(baseUrl: String = PAIRING_BASE_URL): PairingApi =
        retrofitFor(baseUrl, PAIRING_SCAN_CALL_TIMEOUT_SECONDS).create(PairingApi::class.java)

    // Open for JVM contract tests of flows that bind a device-specific local REST endpoint.
    // Production instances keep the same Retrofit cache and transport behavior.
    open fun deviceApi(baseUrl: String): DeviceApi =
        retrofitFor(baseUrl).create(DeviceApi::class.java)

    fun serverApi(baseUrl: String): ServerApi =
        retrofitFor(baseUrl).create(ServerApi::class.java)

    /**
     * The detail/list monitor is intentionally fail-fast. ServerSmartPlug normally serves its
     * in-memory latest snapshot within a few hundred milliseconds; allowing an eight-second
     * transport stall would freeze a 500 ms monitoring cadence and delay truthful offline state.
     * Configuration, relay, history and setup operations keep using [serverApi]'s regular limit.
     */
    fun serverMonitoringApi(baseUrl: String): ServerApi =
        retrofitFor(baseUrl, SERVER_MONITORING_CALL_TIMEOUT_SECONDS).create(ServerApi::class.java)

    fun serverSetupApi(baseUrl: String = PAIRING_BASE_URL): ServerSetupApi =
        retrofitFor(baseUrl).create(ServerSetupApi::class.java)

    private fun retrofitFor(
        baseUrl: String,
        callTimeoutSeconds: Long = CALL_TIMEOUT_SECONDS,
    ): Retrofit {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        // Timeout is part of a client's transport contract.  Do not let a cache hit for the
        // regular eight-second pairing client silently shorten the Wi-Fi scanning client.
        val cacheKey = "$normalized#timeout=$callTimeoutSeconds"
        return retrofitCache.getOrPut(cacheKey) {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient.newBuilder()
                    .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                    .build())
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
        }
    }

    /** Drops cached clients for a base URL, e.g. after a device's `lan_ip` changes. */
    fun invalidate(baseUrl: String) {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        retrofitCache.keys.removeIf { it.startsWith("$normalized#timeout=") }
    }

    companion object {
        const val PAIRING_BASE_URL = "http://192.168.4.1/"
        private const val CALL_TIMEOUT_SECONDS = 8L
        private const val SERVER_MONITORING_CALL_TIMEOUT_SECONDS = 2L
        private const val PAIRING_SCAN_CALL_TIMEOUT_SECONDS = 20L

        fun lanBaseUrl(ip: String): String = "http://$ip/"
        fun hostBaseUrl(host: String, port: Int = 80): String =
            if (port == 80) "http://$host/" else "http://$host:$port/"
    }
}
