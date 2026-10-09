package com.smartplug.app.data.repository

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.smartplug.app.domain.model.DiscoveredExistingSmartPlug
import com.smartplug.app.domain.model.DiscoveredServer
import com.smartplug.app.domain.repository.DiscoveryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Service names SmartPlug/ServerSmartPlug announce over mDNS, design.md "Standar nama discovery"
 * and "Jika IP berubah". */
private const val SERVER_SERVICE_TYPE = "_srvrplug._tcp."
private const val DEVICE_SERVICE_TYPE = "_smartplug._tcp."

@Singleton
class DiscoveryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : DiscoveryRepository {

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    override suspend fun discoverServer(timeoutMs: Long): DiscoveredServer? =
        withTimeoutOrNull(timeoutMs) {
            val serviceInfo = discoverFirstService(SERVER_SERVICE_TYPE) ?: return@withTimeoutOrNull null
            val resolved = resolveService(serviceInfo) ?: return@withTimeoutOrNull null
            val serverId = resolved.attributes["server_id"]?.let { String(it) } ?: return@withTimeoutOrNull null
            val port = resolved.attributes["mqtt_port"]?.let { String(it).toIntOrNull() } ?: resolved.port
            DiscoveredServer(serverId = serverId, host = resolved.host.hostAddress ?: return@withTimeoutOrNull null, port = port)
        }

    override suspend fun discoverExistingDevices(timeoutMs: Long): List<DiscoveredExistingSmartPlug> =
        discoverServicesForWindow(DEVICE_SERVICE_TYPE, timeoutMs)
            .mapNotNull { resolved ->
                val deviceId = resolved.attributes["device_id"]?.let(::String).orEmpty()
                val host = resolved.host?.hostAddress.orEmpty()
                if (deviceId.isBlank() || host.isBlank()) null else DiscoveredExistingSmartPlug(deviceId, host)
            }
            .distinctBy { it.deviceId }

    override suspend fun resolveDeviceLanIp(deviceId: String, timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) {
            val resolvedMatch = discoverAndResolveMatching(DEVICE_SERVICE_TYPE) { resolved ->
                resolved.attributes["device_id"]?.let { String(it) } == deviceId
            }
            resolvedMatch?.host?.hostAddress
        }

    private suspend fun discoverFirstService(serviceType: String): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(service)
                    runCatching { nsdManager.stopServiceDiscovery(this) }
                }
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            continuation.invokeOnCancellation {
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
        }

    /** Discovers services of [serviceType] one at a time, resolving each candidate (which needs
     * its own async NsdManager round trip) until [predicate] matches or discovery is cancelled. */
    private suspend fun discoverAndResolveMatching(
        serviceType: String,
        predicate: (NsdServiceInfo) -> Boolean,
    ): NsdServiceInfo? = suspendCancellableCoroutine { continuation ->
        val pendingCandidates = Channel<NsdServiceInfo>(capacity = 16)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                pendingCandidates.trySend(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)

        val scope = CoroutineScope(Dispatchers.IO)
        val consumerJob = scope.launch {
            for (candidate in pendingCandidates) {
                val resolved = resolveService(candidate)
                if (resolved != null && predicate(resolved) && continuation.isActive) {
                    continuation.resume(resolved)
                    break
                }
            }
        }

        continuation.invokeOnCancellation {
            consumerJob.cancel()
            pendingCandidates.close()
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
    }

    /**
     * Android NSD discovery has no natural "complete" callback.  For an explicit user scan we
     * keep a bounded discovery window, resolve every advertised service, then return the unique
     * resolved results.  Cancellation always stops discovery, so navigating away cannot leave a
     * listener active in the app process.
     */
    private suspend fun discoverServicesForWindow(
        serviceType: String,
        timeoutMs: Long,
    ): List<NsdServiceInfo> = suspendCancellableCoroutine { continuation ->
        val resolvedByName = linkedMapOf<String, NsdServiceInfo>()
        val mainHandler = Handler(Looper.getMainLooper())
        var stopped = false
        lateinit var listener: NsdManager.DiscoveryListener

        fun stopAndResume() {
            if (stopped) return
            stopped = true
            mainHandler.removeCallbacksAndMessages(null)
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            if (continuation.isActive) continuation.resume(resolvedByName.values.toList())
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (!stopped) resolvedByName[serviceInfo.serviceName] = serviceInfo
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                resolvedByName.remove(service.serviceName)
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stopAndResume()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        mainHandler.postDelayed({ stopAndResume() }, timeoutMs.coerceAtLeast(1L))
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        continuation.invokeOnCancellation {
            if (!stopped) {
                stopped = true
                mainHandler.removeCallbacksAndMessages(null)
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
        }
    }

    private suspend fun resolveService(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(serviceInfo)
                }
            })
        }

}
