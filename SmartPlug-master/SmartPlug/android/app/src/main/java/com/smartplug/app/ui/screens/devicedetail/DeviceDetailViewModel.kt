package com.smartplug.app.ui.screens.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.local.db.HistoryDao
import com.smartplug.app.data.local.db.HistoryPointEntity
import com.smartplug.app.data.local.db.LoadSignatureDao
import com.smartplug.app.data.local.db.LoadSignatureEntity
import com.smartplug.app.data.local.AppPreferences
import com.smartplug.app.data.local.EnergyLimit
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.domain.model.ApiFailure
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.ElectricalMeasurement
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.MemberInvitation
import com.smartplug.app.domain.model.ManagedMember
import com.smartplug.app.domain.model.RelayCommandStatus
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.model.ServerConnectionProfile
import com.smartplug.app.domain.model.RegisteredServer
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.DeviceControlRepository
import com.smartplug.app.domain.repository.RelayRepository
import com.smartplug.app.util.applyEnergyAdjustment
import com.smartplug.app.util.safeLaunch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class RelayUiState { IDLE, SENDING, WAITING_SETTLE, ERROR }

data class DeviceDetailUiState(
    val device: SmartPlugDevice? = null,
    val status: DeviceStatus? = null,
    val statusReceivedAtMs: Long = 0L,
    val measurement: ElectricalMeasurement? = null,
    val lastError: String? = null,
    val relayUiState: RelayUiState = RelayUiState.IDLE,
    val isRemoved: Boolean = false,
    val liveModeRequested: Boolean = false,
    val livePoints: List<LiveMeasurementPoint> = emptyList(),
    val energyPerMinuteWh: Double? = null,
    val ratesPerMinute: MeasurementRates? = null,
    val canSaveLoadSignature: Boolean = false,
    val detectedLoadName: String? = null,
    val vampireEnergySuspected: Boolean = false,
    val registeredServers: List<RegisteredServer> = emptyList(),
    /** Only the owner may change this SmartPlug's MQTT server route. */
    val canManageServer: Boolean = false,
    val memberInvitation: MemberInvitation? = null,
    val memberInvitationExpiresAtMs: Long = 0L,
    val isCreatingMemberInvitation: Boolean = false,
    /** The normal disconnect failed; the UI offers a forced one. */
    val serverDisconnectFailed: Boolean = false,
    /** Why the last invitation attempt failed; kept apart from lastError, which polling overwrites. */
    val memberInvitationError: String? = null,
    val managedMembers: List<ManagedMember> = emptyList(),
    val isLoadingManagedMembers: Boolean = false,
    val revokingMemberId: String? = null,
    val limit: EnergyLimit = EnergyLimit(),
    /** Incremented each time an ON request is refused because the kWh limit is reached. */
    val limitBlockedNonce: Int = 0,
)

data class LiveMeasurementPoint(val timestampMs: Long, val measurement: ElectricalMeasurement)
data class MeasurementRates(
    val voltageVariationPercent: Double,
    val activePowerW: Double,
    val powerFactorVariationPercent: Double,
    /** Instantaneous conversion from active power, so this responds before the next CF pulse. */
    val energyKwhPerMinute: Double,
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val relayRepository: RelayRepository,
    private val deviceControlRepository: DeviceControlRepository,
    private val historyDao: HistoryDao,
    private val loadSignatureDao: LoadSignatureDao,
    private val serverProfileStore: ServerProfileStore,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val deviceId: String = checkNotNull(savedStateHandle["deviceId"])
    private var lastStoredMinute: Long = Long.MIN_VALUE
    private var lastLimitOffAtMs: Long = 0L

    private val _uiState = MutableStateFlow(DeviceDetailUiState())
    val uiState: StateFlow<DeviceDetailUiState> = _uiState.asStateFlow()

    init {
        safeLaunch {
            val device = deviceRepository.getDevice(deviceId)
            val canManageServer = device?.let { deviceRepository.canManageServer(it) } ?: false
            _uiState.value = _uiState.value.copy(
                device = device,
                registeredServers = serverProfileStore.all(),
                canManageServer = canManageServer,
            )
        }
    }

    init {
        safeLaunch {
            appPreferences.energyLimit(deviceId).collect { limit ->
                _uiState.value = _uiState.value.copy(limit = limit)
            }
        }
    }

    fun setEnergyLimit(enabled: Boolean, kwh: Double) {
        safeLaunch { appPreferences.setEnergyLimit(deviceId, enabled, kwh) }
    }

    // Limits compare against the same corrected kWh the user sees on screen.
    private var kwhAdjustPercent = 0.0

    init {
        safeLaunch { appPreferences.kwhAdjustPercent(deviceId).collect { kwhAdjustPercent = it } }
    }

    private fun currentKwh(): Double? = _uiState.value.measurement?.energyWh?.coerceAtLeast(0.0)?.div(1000.0)
        ?.let { applyEnergyAdjustment(it, kwhAdjustPercent) }

    /**
     * App-side limit: while the app is open and polling, a reached limit sends OFF (rate-limited so
     * a slow relay is not spammed). It cannot act while the app is closed, because the app never
     * polls in the background (design.md).
     */
    private fun enforceLimit(measurementKwh: Double, relayOn: Boolean) {
        val limit = _uiState.value.limit
        if (!limit.isReached(measurementKwh) || !relayOn) return
        val now = System.currentTimeMillis()
        if (now - lastLimitOffAtMs < LIMIT_OFF_RETRY_MS) return
        lastLimitOffAtMs = now
        setRelay(false)
    }

    fun setLiveMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(liveModeRequested = enabled)
    }

    fun clearLiveGraph() {
        _uiState.value = _uiState.value.copy(livePoints = emptyList())
    }

    /** Called directly from [com.smartplug.app.ui.components.PollWhileVisible]'s own coroutine on
     * every poll tick, not via [safeLaunch] — that shared component already guards every caller
     * against an uncaught exception here, so this only needs to convert failures into UI state. */
    suspend fun refresh() {
        val device = _uiState.value.device ?: deviceRepository.getDevice(deviceId) ?: return
        // Server mode is deliberately a single atomic `/latest` read. This avoids
        // duplicate traffic and prevents state/measurement values being composed
        // from two different server snapshots. Direct mode reads its two
        // endpoints sequentially: ESP8266WebServer is single-threaded, so two
        // simultaneous HTTP calls can make an otherwise healthy device close
        // one socket while it serves the other.
        val (statusResult, measurementResult) = if (device.integrationMode == IntegrationMode.SERVER) {
            // `/latest` is an idempotent cache read. The ESP32 may close an idle
            // HTTP socket between one-second polls, so retry one clean request
            // before presenting a transient transport failure as offline.
            when (val snapshot = retryReadOnce { deviceRepository.fetchServerSnapshot(device) }) {
                is ApiResult.Success -> ApiResult.Success(snapshot.value.status) to ApiResult.Success(snapshot.value.measurement)
                is ApiResult.Failure -> snapshot to snapshot
            }
        } else {
            retryReadOnce { deviceRepository.fetchStatus(device) } to
                retryReadOnce { deviceRepository.fetchMeasurement(device) }
        }

        // A Server -> Direct (or Direct -> Server) change is allowed while the
        // visible poll is in flight.  Never let that older response write its
        // route back into UI state: it would make a successfully disconnected
        // Direct device appear offline until a later recreation.  The next
        // one-second poll uses the persisted, newly selected route instead.
        val currentDevice = _uiState.value.device ?: deviceRepository.getDevice(deviceId) ?: return
        if (currentDevice.integrationMode != device.integrationMode ||
            currentDevice.serverId != device.serverId ||
            currentDevice.serverHost != device.serverHost ||
            currentDevice.serverPort != device.serverPort ||
            currentDevice.lanIp != device.lanIp) {
            return
        }

        val successfulMeasurement = (measurementResult as? ApiResult.Success)?.value
        if (successfulMeasurement != null) recordMeasurement(device.deviceId, successfulMeasurement)
        val updatedLivePoints = successfulMeasurement?.let { appendLivePoint(_uiState.value.livePoints, it) }
            ?: _uiState.value.livePoints
        val rates = measurementPresentation(updatedLivePoints)
        val isStableForMinute = isStableForMinute(updatedLivePoints, statusResult, successfulMeasurement)
        val vampireSuspected = isVampireEnergySuspected(updatedLivePoints, statusResult)
        val detectedLoad = successfulMeasurement?.let { measurement ->
            findMatchingLoad(device.deviceId, measurement)
        }
        _uiState.value = _uiState.value.copy(
            device = device,
            // A failed poll means this device is unreachable now. Do not retain an old successful
            // status: that made a powered-off SmartPlug appear online indefinitely.
            status = (statusResult as? ApiResult.Success)?.value,
            statusReceivedAtMs = if (statusResult is ApiResult.Success) System.currentTimeMillis() else 0L,
            measurement = successfulMeasurement ?: _uiState.value.measurement,
            livePoints = updatedLivePoints,
            energyPerMinuteWh = rates?.energyKwhPerMinute?.times(1000.0),
            ratesPerMinute = rates,
            canSaveLoadSignature = isStableForMinute,
            detectedLoadName = detectedLoad,
            vampireEnergySuspected = vampireSuspected,
            lastError = ((statusResult as? ApiResult.Failure)?.error ?: (measurementResult as? ApiResult.Failure)?.error)
                ?.let(::describeError),
        )
        if (successfulMeasurement != null) {
            enforceLimit(
                measurementKwh = applyEnergyAdjustment(successfulMeasurement.energyWh.coerceAtLeast(0.0) / 1000.0, kwhAdjustPercent),
                relayOn = (statusResult as? ApiResult.Success)?.value?.relayState == RelayState.ON,
            )
        }
    }

    /**
     * The ESP8266 local server may close a just-idle TCP socket while Wi-Fi
     * changes state.  Direct reads are idempotent, so retry one failed read on
     * a clean, still-serial request. A second failure remains visible to the
     * user; this is not an offline-state suppression mechanism.
     */
    private suspend fun <T> retryReadOnce(read: suspend () -> ApiResult<T>): ApiResult<T> {
        val first = read()
        if (first is ApiResult.Success) return first
        delay(DIRECT_READ_RETRY_DELAY_MS)
        return read()
    }

    /** A standby pattern, not a statement that the load is faulty: 0.5–10 W,
     * stable and continuously present for fifteen minutes while relay is on. */
    private fun isVampireEnergySuspected(
        points: List<LiveMeasurementPoint>,
        status: ApiResult<DeviceStatus>,
    ): Boolean {
        if ((status as? ApiResult.Success)?.value?.relayState != RelayState.ON) return false
        val newest = points.lastOrNull() ?: return false
        val window = points.filter { newest.timestampMs - it.timestampMs <= VAMPIRE_WINDOW_MS }
        if (window.size < 2 || newest.timestampMs - window.first().timestampMs < VAMPIRE_WINDOW_MS) return false
        val watts = window.map { it.measurement.activePowerW }
        val average = watts.average()
        return average in 0.5..10.0 && watts.max() - watts.min() <= maxOf(1.0, average * 0.20)
    }

    /**
     * The voltage/PF secondary labels are a +/- variation, not a before/after change:
     * half the observed range divided by the window average.  For 219, 220, 221 V this is
     * +/- (1 / 220 * 100) = +/- 0.45%.
     */
    private fun measurementPresentation(points: List<LiveMeasurementPoint>): MeasurementRates? {
        val newest = points.lastOrNull() ?: return null
        // kWh/min is intentionally immediate.  The cumulative Wh counter advances in CF pulses,
        // so calculating it from the current active power avoids a visually frozen rate at light loads.
        val immediateKwhPerMinute = (newest.measurement.activePowerW / 60_000.0).coerceAtLeast(0.0)
        val minuteWindow = points.filter { newest.timestampMs - it.timestampMs <= TimeUnit.MINUTES.toMillis(1) }
        if (minuteWindow.size < 2 || newest.timestampMs - minuteWindow.first().timestampMs < TimeUnit.MINUTES.toMillis(1)) {
            return MeasurementRates(0.0, 0.0, 0.0, immediateKwhPerMinute)
        }
        val elapsedMinutes = (newest.timestampMs - minuteWindow.first().timestampMs).toDouble() / TimeUnit.MINUTES.toMillis(1)
        return MeasurementRates(
            voltageVariationPercent = variationPercent(minuteWindow.map { it.measurement.voltageV }),
            activePowerW = (newest.measurement.activePowerW - minuteWindow.first().measurement.activePowerW) / elapsedMinutes,
            powerFactorVariationPercent = variationPercent(minuteWindow.map { it.measurement.powerFactor }),
            energyKwhPerMinute = immediateKwhPerMinute,
        )
    }

    private fun variationPercent(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val average = values.average()
        if (average == 0.0) return 0.0
        return ((values.max() - values.min()) / 2.0 / kotlin.math.abs(average) * 100.0).coerceAtLeast(0.0)
    }

    private fun isStableForMinute(
        points: List<LiveMeasurementPoint>,
        status: ApiResult<DeviceStatus>,
        measurement: ElectricalMeasurement?,
    ): Boolean {
        if ((status as? ApiResult.Success)?.value?.relayState != RelayState.ON || measurement == null || measurement.currentA <= 0.0) return false
        val newest = points.lastOrNull() ?: return false
        val window = points.filter { newest.timestampMs - it.timestampMs <= TimeUnit.MINUTES.toMillis(1) }
        if (window.size < 2 || newest.timestampMs - window.first().timestampMs < TimeUnit.MINUTES.toMillis(1)) return false
        val values = window.map { it.measurement.currentA }
        val average = values.average()
        return average > 0.0 && values.max() - values.min() <= maxOf(0.02, average * 0.08)
    }

    private suspend fun findMatchingLoad(deviceId: String, measurement: ElectricalMeasurement): String? {
        if (measurement.currentA <= 0.0) return null
        return loadSignatureDao.forDevice(deviceId)
            .filter { signature ->
                kotlin.math.abs(signature.currentA - measurement.currentA) <= maxOf(0.03, signature.currentA * 0.10) &&
                    kotlin.math.abs(signature.powerFactor - measurement.powerFactor) <= 0.08
            }
            .minByOrNull { kotlin.math.abs(it.currentA - measurement.currentA) }
            ?.name
    }

    private suspend fun recordMeasurement(deviceId: String, measurement: ElectricalMeasurement) {
        // Keep a durable, lightweight one-minute local history. The dense one-second series is
        // intentionally in-memory only and is used solely for the 1–60 minute live chart.
        val minute = System.currentTimeMillis() / TimeUnit.MINUTES.toMillis(1)
        if (minute == lastStoredMinute) return
        lastStoredMinute = minute
        historyDao.upsertAll(
            listOf(
                HistoryPointEntity(
                    deviceId = deviceId,
                    resolution = LOCAL_HISTORY_RESOLUTION,
                    timestampUtcMs = minute * TimeUnit.MINUTES.toMillis(1),
                    voltageV = measurement.voltageV,
                    currentA = measurement.currentA,
                    activePowerW = measurement.activePowerW,
                    apparentPowerVa = measurement.apparentPowerVa,
                    powerFactor = measurement.powerFactor,
                    energyWh = measurement.energyWh,
                ),
            ),
        )
        historyDao.deleteOlderThan(LOCAL_HISTORY_RESOLUTION, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
    }

    private fun appendLivePoint(
        existing: List<LiveMeasurementPoint>,
        measurement: ElectricalMeasurement,
    ): List<LiveMeasurementPoint> {
        val now = System.currentTimeMillis()
        return (existing + LiveMeasurementPoint(now, measurement)).filter { it.timestampMs >= now - TimeUnit.MINUTES.toMillis(120) }
    }

    fun toggleRelay() {
        val currentState = _uiState.value.status?.relayState ?: RelayState.UNKNOWN
        setRelay(currentState != RelayState.ON)
    }

    fun setRelay(targetOn: Boolean) {
        val device = _uiState.value.device ?: return
        if (targetOn) {
            val kwh = currentKwh()
            if (kwh != null && _uiState.value.limit.isReached(kwh)) {
                _uiState.value = _uiState.value.copy(limitBlockedNonce = _uiState.value.limitBlockedNonce + 1)
                return
            }
        }

        safeLaunch(onError = {
            _uiState.value = _uiState.value.copy(
                relayUiState = RelayUiState.ERROR,
                lastError = "Terjadi kesalahan tak terduga saat mengontrol relay.",
            )
        }) {
            _uiState.value = _uiState.value.copy(relayUiState = RelayUiState.SENDING, lastError = null)
            when (val result = relayRepository.setRelay(device, targetOn)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(relayUiState = RelayUiState.WAITING_SETTLE)
                    val targetState = if (targetOn) RelayState.ON else RelayState.OFF
                    when (val settled = relayRepository.awaitSettledState(device, result.value.commandId, targetState)) {
                        is ApiResult.Success -> {
                            val finalStatus = _uiState.value.status?.copy(relayState = settled.value.state)
                            _uiState.value = _uiState.value.copy(
                                status = finalStatus,
                                relayUiState = if (settled.value.status == RelayCommandStatus.COMPLETED) RelayUiState.IDLE else RelayUiState.ERROR,
                                lastError = if (settled.value.status != RelayCommandStatus.COMPLETED) {
                                    "Perintah relay tidak selesai (${settled.value.status})"
                                } else null,
                            )
                        }
                        is ApiResult.Failure -> _uiState.value = _uiState.value.copy(
                            relayUiState = RelayUiState.ERROR,
                            lastError = describeError(settled.error),
                        )
                    }
                }
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(
                    relayUiState = RelayUiState.ERROR,
                    lastError = describeError(result.error),
                )
            }
            // Always re-sync from the source of truth rather than trusting our own optimistic state.
            // Preserve a terminal command error: refresh() only describes status-read failures,
            // so it must not make a rejected/timeout relay command look successful.
            val commandError = _uiState.value.lastError
            refresh()
            if (commandError != null) {
                _uiState.value = _uiState.value.copy(lastError = commandError)
            }
        }
    }

    fun renameDevice(newName: String) {
        val device = _uiState.value.device ?: return
        safeLaunch {
            when (val result = deviceControlRepository.setSharedDisplayName(device, newName)) {
                is ApiResult.Success -> {
                    val normalized = newName.trim()
                    deviceRepository.renameDevice(deviceId, normalized)
                    _uiState.value = _uiState.value.copy(device = device.copy(displayName = normalized))
                }
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(lastError = describeError(result.error))
            }
        }
    }

    fun connectSavedServer(server: RegisteredServer) {
        val device = _uiState.value.device ?: return
        if (!_uiState.value.canManageServer) {
            _uiState.value = _uiState.value.copy(lastError = "Hanya pemilik SmartPlug yang dapat mengubah koneksi server.")
            return
        }
        // Server profiles are created only by the separate ServerSmartPlug onboarding flow.
        // Never mutate that profile here; update this one SmartPlug only after its local REST
        // endpoint accepts the selected MQTT settings.
        safeLaunch {
            when (val result = deviceRepository.connectToServer(
                device,
                ServerConnectionProfile(
                    serverId = server.serverId,
                    brokerHost = server.host,
                    brokerPort = server.mqttPort,
                    mqttUsername = server.mqttUsername,
                    mqttPassword = server.mqttPassword,
                    baseTopic = "smartplug/${device.deviceId}",
                ),
            )) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    device = device.copy(
                        integrationMode = com.smartplug.app.domain.model.IntegrationMode.SERVER,
                        serverId = server.serverId,
                        serverHost = server.host,
                    ),
                    lastError = null,
                )
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(lastError = describeError(result.error))
            }
        }
    }

    fun dismissForceDisconnect() {
        _uiState.value = _uiState.value.copy(serverDisconnectFailed = false)
    }

    fun disconnectFromServer(force: Boolean = false) {
        val device = _uiState.value.device ?: return
        if (!_uiState.value.canManageServer) {
            _uiState.value = _uiState.value.copy(lastError = "Hanya pemilik SmartPlug yang dapat mengubah koneksi server.")
            return
        }
        safeLaunch {
            when (val result = deviceRepository.disconnectFromServer(device, force)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    device = device.copy(integrationMode = com.smartplug.app.domain.model.IntegrationMode.DIRECT, serverId = null, serverHost = null),
                    serverDisconnectFailed = false,
                    lastError = null,
                )
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(
                    serverDisconnectFailed = !force,
                    lastError = describeError(result.error),
                )
            }
        }
    }

    fun saveLoadSignature(name: String) {
        val measurement = _uiState.value.measurement ?: return
        if (!_uiState.value.canSaveLoadSignature || name.isBlank()) return
        safeLaunch {
            loadSignatureDao.upsert(
                LoadSignatureEntity(deviceId = deviceId, name = name.trim(), currentA = measurement.currentA, powerFactor = measurement.powerFactor),
            )
            _uiState.value = _uiState.value.copy(detectedLoadName = name.trim())
        }
    }

    fun removeDevice() {
        safeLaunch {
            deviceRepository.removeDevice(deviceId)
            _uiState.value = _uiState.value.copy(isRemoved = true)
        }
    }

    /** PROPOSED firmware API; hidden in the UI unless the device reports the field. */
    fun setProtection(enabled: Boolean, onSuccess: () -> Unit = {}) =
        runControl("Pengaturan proteksi gagal disimpan.", onSuccess) { device ->
            deviceControlRepository.setProtection(device, enabled)
        }

    fun setPowerPolicy(policy: com.smartplug.app.domain.model.PowerOnPolicy, delaySeconds: Int, onSuccess: () -> Unit = {}) =
        runControl("Pengaturan gagal disimpan.", onSuccess) { device ->
            deviceControlRepository.setPowerPolicy(device, policy, delaySeconds)
        }

    fun resetEnergy() = runControl("Reset energi ditolak") { device -> deviceControlRepository.resetEnergy(device) }

    fun factoryReset() = runControl("Factory reset ditolak") { device -> deviceControlRepository.factoryReset(device) }

    fun applyTimer(hours: Int, minutes: Int, seconds: Int, onSuccess: () -> Unit) {
        // The composable disables Apply at zero, but keep the command boundary
        // defensive so an accessibility action or future UI cannot send an
        // invalid all-zero timer request to the SmartPlug/server.
        if (hours !in 0..99 || minutes !in 0..59 || seconds !in 0..59 ||
            (hours == 0 && minutes == 0 && seconds == 0)
        ) {
            _uiState.value = _uiState.value.copy(lastError = "Durasi timer tidak valid.")
            return
        }
        runControl("Timer tidak dapat diterapkan", onSuccess) { device ->
            // The product UI intentionally has no day selector. Preserve the existing
            // firmware/server contract by converting a Clock-style total-hour wheel
            // into its days + hours wire representation internally.
            deviceControlRepository.applyTimer(device, hours / 24, hours % 24, minutes, seconds)
        }
    }

    fun resetTimer(onSuccess: () -> Unit) =
        runControl("Timer tidak dapat direset", onSuccess) { device -> deviceControlRepository.resetTimer(device) }

    fun createMemberInvitation() {
        val device = _uiState.value.device ?: return
        safeLaunch(onError = {
            _uiState.value = _uiState.value.copy(
                isCreatingMemberInvitation = false,
                memberInvitationError = "Kode undangan tidak dapat dibuat.",
                lastError = "Kode undangan tidak dapat dibuat.",
            )
        }) {
            _uiState.value = _uiState.value.copy(
                isCreatingMemberInvitation = true,
                memberInvitation = null,
                memberInvitationExpiresAtMs = 0L,
                memberInvitationError = null,
                lastError = null,
            )
            when (val result = deviceControlRepository.createMemberInvitation(device)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    isCreatingMemberInvitation = false,
                    memberInvitation = result.value,
                    memberInvitationExpiresAtMs = System.currentTimeMillis() + result.value.expiresInSeconds * 1000L,
                )
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(
                    isCreatingMemberInvitation = false,
                    memberInvitationError = describeError(result.error),
                    lastError = describeError(result.error),
                )
            }
        }
    }

    fun loadManagedMembers() {
        val device = _uiState.value.device ?: return
        safeLaunch(onError = {
            _uiState.value = _uiState.value.copy(isLoadingManagedMembers = false, lastError = "Daftar HP tidak dapat dimuat.")
        }) {
            _uiState.value = _uiState.value.copy(isLoadingManagedMembers = true, lastError = null)
            when (val result = deviceControlRepository.listManagedMembers(device)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    isLoadingManagedMembers = false, managedMembers = result.value, lastError = null,
                )
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(
                    isLoadingManagedMembers = false, lastError = describeError(result.error),
                )
            }
        }
    }

    fun revokeManagedMember(credentialId: String) {
        val device = _uiState.value.device ?: return
        safeLaunch(onError = {
            _uiState.value = _uiState.value.copy(revokingMemberId = null, lastError = "Akses HP tidak dapat dicabut.")
        }) {
            _uiState.value = _uiState.value.copy(revokingMemberId = credentialId, lastError = null)
            when (val result = deviceControlRepository.revokeManagedMember(device, credentialId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    revokingMemberId = null,
                    managedMembers = _uiState.value.managedMembers.filterNot { it.credentialId == credentialId },
                    lastError = null,
                )
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(
                    revokingMemberId = null, lastError = describeError(result.error),
                )
            }
        }
    }

    private fun runControl(
        fallback: String,
        onSuccess: () -> Unit = {},
        action: suspend (SmartPlugDevice) -> ApiResult<Unit>,
    ) {
        val device = _uiState.value.device ?: return
        safeLaunch(onError = {
            _uiState.value = _uiState.value.copy(lastError = fallback)
        }) {
            when (val result = action(device)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(lastError = null)
                    // A control response is the authoritative acceptance of the
                    // action.  TimerDialog must close on that response, not be
                    // held open by a best-effort follow-up status refresh that
                    // may fail independently after the command has succeeded.
                    onSuccess()
                    refresh()
                }
                is ApiResult.Failure -> _uiState.value = _uiState.value.copy(lastError = describeError(result.error))
            }
        }
    }

    private fun describeError(failure: ApiFailure): String = when (failure.errorCode) {
        "invalid_owner_token" -> "Sesi tidak valid. Ulangi pemasangan SmartPlug ini."
        "measurement_unavailable" -> "Pengukuran belum tersedia dari perangkat."
        "network_timeout" -> "Waktu tunggu jaringan habis. Periksa koneksi Wi-Fi."
        "no_connectivity" -> "Tidak dapat menjangkau perangkat di jaringan."
        "missing_lan_ip", "missing_server_host" -> "Alamat perangkat belum diketahui."
        "relay_busy" -> "Perintah relay sebelumnya masih diproses."
        "relay_actuation_disabled" -> "Kontrol relay dinonaktifkan pada firmware ini."
        "triple_confirmation_required" -> "Konfirmasi tiga tahap belum lengkap."
        "invalid_timer_duration" -> "Durasi timer tidak valid."
        "invalid_power_policy", "invalid_protection" -> "Pengaturan tidak valid."
        "not_found" -> "Firmware SmartPlug ini belum mendukung pengaturan tersebut."
        "invite_already_active" -> "Masih ada kode undangan aktif di SmartPlug (berlaku maksimal 5 menit). Tunggu sampai kedaluwarsa, lalu buat kode baru."
        "access_invite_temporarily_locked" -> "Pembuatan kode dikunci sementara karena terlalu banyak percobaan salah. Coba lagi sebentar."
        else -> failure.message ?: "Terjadi kesalahan (${failure.errorCode})"
    }

    private companion object {
        const val LOCAL_HISTORY_RESOLUTION = "local_1m"
        const val DIRECT_READ_RETRY_DELAY_MS = 150L
        const val LIMIT_OFF_RETRY_MS = 15_000L
        val VAMPIRE_WINDOW_MS = TimeUnit.MINUTES.toMillis(15)
    }
}
