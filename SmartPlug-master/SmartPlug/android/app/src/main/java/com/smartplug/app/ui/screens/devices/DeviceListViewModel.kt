package com.smartplug.app.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.data.local.db.HistoryDao
import com.smartplug.app.data.local.db.LoadSignatureDao
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.ElectricalMeasurement
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.RegisteredServer
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceControlRepository
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.HistoryRepository
import com.smartplug.app.domain.repository.RelayRepository
import com.smartplug.app.util.safeLaunch
import com.smartplug.app.util.sumDaySeries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

data class DeviceRow(
    val device: SmartPlugDevice,
    val status: DeviceStatus?,
    val measurement: ElectricalMeasurement?,
    val online: Boolean,
)

data class DeviceListUiState(
    val rows: List<DeviceRow> = emptyList(),
    val isRefreshing: Boolean = false,
    /** kWh used since local midnight per device id; absent while unknown (display-only). */
    val todayKwh: Map<String, Double> = emptyMap(),
    /** Summed kWh since midnight over time (epoch ms, kWh) for the Beranda chart. */
    val todaySeries: List<Pair<Long, Double>> = emptyList(),
    /** Raw (uncorrected) per-device series, so the UI can apply each device's kWh adjustment. */
    val todaySeriesByDevice: Map<String, List<Pair<Long, Double>>> = emptyMap(),
    /** ServerSmartPlug profiles saved on this phone. */
    val servers: List<RegisteredServer> = emptyList(),
    /** Devices whose relay command is in flight. */
    val relayBusy: Set<String> = emptySet(),
)

@HiltViewModel
class DeviceListViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val historyRepository: HistoryRepository,
    private val relayRepository: RelayRepository,
    private val deviceControlRepository: DeviceControlRepository,
    private val historyDao: HistoryDao,
    private val loadSignatureDao: LoadSignatureDao,
    private val serverProfileStore: ServerProfileStore,
) : ViewModel() {

    private val statuses = MutableStateFlow<Map<String, DeviceStatus>>(emptyMap())
    private val measurements = MutableStateFlow<Map<String, ElectricalMeasurement>>(emptyMap())
    private val refreshing = MutableStateFlow(false)
    private val todayKwh = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val todaySeries = MutableStateFlow<List<Pair<Long, Double>>>(emptyList())
    private val todaySeriesByDevice = MutableStateFlow<Map<String, List<Pair<Long, Double>>>>(emptyMap())
    private val servers = MutableStateFlow(serverProfileStore.all())
    private val relayBusy = MutableStateFlow<Set<String>>(emptySet())
    private var lastTodayFetchMs = 0L
    private var todayFetchRunning = false

    private data class Base(
        val devices: List<SmartPlugDevice>,
        val statusMap: Map<String, DeviceStatus>,
        val measurementMap: Map<String, ElectricalMeasurement>,
        val isRefreshing: Boolean,
    )

    private data class Extras(
        val today: Map<String, Double>,
        val series: List<Pair<Long, Double>>,
        val seriesByDevice: Map<String, List<Pair<Long, Double>>>,
        val servers: List<RegisteredServer>,
        val busy: Set<String>,
    )

    val uiState: StateFlow<DeviceListUiState> = combine(
        combine(deviceRepository.observeDevices(), statuses, measurements, refreshing) { devices, statusMap, measurementMap, isRefreshing ->
            Base(devices, statusMap, measurementMap, isRefreshing)
        },
        combine(
            combine(todayKwh, todaySeries, todaySeriesByDevice) { today, series, byDevice -> Triple(today, series, byDevice) },
            combine(servers, relayBusy) { serverList, busy -> serverList to busy },
        ) { (today, series, byDevice), (serverList, busy) ->
            Extras(today, series, byDevice, serverList, busy)
        },
    ) { base, extras ->
        DeviceListUiState(
            rows = base.devices.map { device ->
                val status = base.statusMap[device.deviceId]
                DeviceRow(device = device, status = status, measurement = base.measurementMap[device.deviceId], online = status?.fresh == true)
            },
            isRefreshing = base.isRefreshing,
            todayKwh = extras.today,
            todaySeries = extras.series,
            todaySeriesByDevice = extras.seriesByDevice,
            servers = extras.servers,
            relayBusy = extras.busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceListUiState())

    /**
     * Intentionally suspend until every device result has been applied.  PollWhileVisible waits
     * for this call before scheduling its next tick; launching into viewModelScope here used to
     * overlap list refreshes and let a late failure overwrite a newer successful server poll.
     */
    suspend fun refreshAll() {
        refreshing.value = true
        try {
            // Do not use uiState as the source of truth here.  On a cold Home launch the
            // StateFlow starts from DeviceListUiState() and Room may not have delivered its
            // first list to the combined UI flow yet.  The first poll would therefore fetch
            // zero devices, leaving every saved device rendered Offline until a later screen
            // happened to trigger another refresh.  Read Room's current list directly so the
            // immediate, lifecycle-visible poll always covers persisted devices.
            val devices = deviceRepository.observeDevices().first()
            val results = coroutineScope {
                devices.map { device ->
                    async {
                        if (device.integrationMode == IntegrationMode.SERVER) {
                            when (val snapshot = deviceRepository.fetchServerSnapshot(device)) {
                                is ApiResult.Success -> Triple(
                                    device.deviceId,
                                    ApiResult.Success(snapshot.value.status),
                                    ApiResult.Success(snapshot.value.measurement),
                                )
                                is ApiResult.Failure -> Triple(device.deviceId, snapshot, snapshot)
                            }
                        } else {
                            // Keep the established Direct route isolated: it still talks to the
                            // SmartPlug's two local REST endpoints rather than ServerSmartPlug.
                            Triple(device.deviceId, deviceRepository.fetchStatus(device), deviceRepository.fetchMeasurement(device))
                        }
                    }
                }.awaitAll()
            }
            val updated = statuses.value.toMutableMap()
            val updatedMeasurements = measurements.value.toMutableMap()
            results.forEach { (deviceId, statusResult, measurementResult) ->
                if (statusResult is ApiResult.Success) {
                    updated[deviceId] = statusResult.value
                } else {
                    // Drop stale success data immediately so the overview is truthful after
                    // a SmartPlug loses power or leaves the LAN.
                    updated.remove(deviceId)
                }
                if (measurementResult is ApiResult.Success) updatedMeasurements[deviceId] = measurementResult.value
                else updatedMeasurements.remove(deviceId)
            }
            statuses.value = updated
            measurements.value = updatedMeasurements
            servers.value = serverProfileStore.all()
            refreshTodayKwh(devices, updatedMeasurements)
        } finally {
            // Never leave the pull-to-refresh spinner stuck on if any single device's
            // status fetch throws instead of returning ApiResult.Failure.
            refreshing.value = false
        }
    }

    fun removeDevice(deviceId: String) {
        safeLaunch { deviceRepository.removeDevice(deviceId) }
    }

    /**
     * Same command path as the detail screen: send, wait for the settled state, then re-read the
     * source of truth. The list never assumes the relay changed just because it asked.
     */
    fun setRelay(device: SmartPlugDevice, targetOn: Boolean) {
        if (device.deviceId in relayBusy.value) return
        relayBusy.value = relayBusy.value + device.deviceId
        safeLaunch(onError = { relayBusy.value = relayBusy.value - device.deviceId }) {
            try {
                when (val result = relayRepository.setRelay(device, targetOn)) {
                    is ApiResult.Success -> relayRepository.awaitSettledState(
                        device, result.value.commandId, if (targetOn) RelayState.ON else RelayState.OFF,
                    )
                    is ApiResult.Failure -> Unit
                }
                refreshAll()
            } finally {
                relayBusy.value = relayBusy.value - device.deviceId
            }
        }
    }

    /** Local unpair: removes this phone's profile and history only; the physical unit is not touched. */
    fun unpair(device: SmartPlugDevice) {
        safeLaunch {
            historyDao.clearForDevice(device.deviceId)
            loadSignatureDao.clearForDevice(device.deviceId)
            deviceRepository.removeDevice(device.deviceId)
        }
    }

    /** Same factory-reset command as the detail menu; the profile is only removed once it is accepted. */
    fun factoryReset(device: SmartPlugDevice) {
        safeLaunch {
            if (deviceControlRepository.factoryReset(device) is ApiResult.Success) {
                historyDao.clearForDevice(device.deviceId)
                loadSignatureDao.clearForDevice(device.deviceId)
                deviceRepository.removeDevice(device.deviceId)
            }
        }
    }

    /** Local unpair of a ServerSmartPlug profile; the physical server and its plugs are untouched. */
    fun unpairServer(server: RegisteredServer) {
        serverProfileStore.remove(server.serverId)
        servers.value = serverProfileStore.all()
    }

    /**
     * Energy used since 00:00 local time: latest counter minus the first recorded point of today,
     * plus the summed series for the Beranda chart. Read-only use of the existing history
     * repository, refreshed at most once a minute and never allowed to delay or fail the live poll.
     */
    private fun refreshTodayKwh(devices: List<SmartPlugDevice>, latest: Map<String, ElectricalMeasurement>) {
        val now = System.currentTimeMillis()
        if (todayFetchRunning || now - lastTodayFetchMs < 60_000L) return
        todayFetchRunning = true
        lastTodayFetchMs = now
        safeLaunch {
            try {
                val midnight = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val result = todayKwh.value.toMutableMap()
                val perDevice = mutableListOf<List<Pair<Long, Double>>>()
                val byDevice = mutableMapOf<String, List<Pair<Long, Double>>>()
                devices.forEach { device ->
                    val current = latest[device.deviceId] ?: return@forEach
                    val points = (historyRepository.fetchHistory(device, midnight, now, HistoryResolution.FIVE_MINUTES) as? ApiResult.Success)
                        ?.value.orEmpty().filter { it.timestampUtcMs >= midnight }.sortedBy { it.timestampUtcMs }
                    val first = points.firstOrNull() ?: return@forEach
                    result[device.deviceId] = ((current.energyWh - first.energyWh) / 1000.0).coerceAtLeast(0.0)
                    val series = points.map { it.timestampUtcMs to ((it.energyWh - first.energyWh) / 1000.0).coerceAtLeast(0.0) } +
                        (now to result.getValue(device.deviceId))
                    perDevice += series
                    byDevice[device.deviceId] = series
                }
                todayKwh.value = result
                todaySeries.value = sumDaySeries(perDevice)
                todaySeriesByDevice.value = byDevice
            } finally {
                todayFetchRunning = false
            }
        }
    }

}
