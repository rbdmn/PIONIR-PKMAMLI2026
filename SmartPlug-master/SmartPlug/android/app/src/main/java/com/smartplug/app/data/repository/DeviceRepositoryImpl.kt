package com.smartplug.app.data.repository

import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.local.DeviceAccessRole
import com.smartplug.app.data.local.db.DeviceDao
import com.smartplug.app.data.local.db.DeviceEntity
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.dto.ServerLatestDto
import com.smartplug.app.data.remote.dto.MqttAuthDeviceRequestDto
import com.smartplug.app.data.remote.map
import com.smartplug.app.data.remote.safeApiCall
import com.smartplug.app.domain.model.ApiFailure
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.DeviceSnapshot
import com.smartplug.app.domain.model.ElectricalMeasurement
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val deviceDao: DeviceDao,
    private val tokenStore: SecureTokenStore,
    private val apiClientFactory: ApiClientFactory,
) : DeviceRepository {

    override fun observeDevices(): Flow<List<SmartPlugDevice>> =
        deviceDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getDevice(deviceId: String): SmartPlugDevice? =
        deviceDao.getById(deviceId)?.toDomain()

    override suspend fun saveDevice(device: SmartPlugDevice) {
        deviceDao.upsert(device.toEntity())
    }

    override suspend fun removeDevice(deviceId: String) {
        deviceDao.deleteById(deviceId)
        tokenStore.clearOwnerToken(deviceId)
    }

    override suspend fun renameDevice(deviceId: String, displayName: String) {
        deviceDao.renameDevice(deviceId, displayName)
    }

    override suspend fun updateLanIp(deviceId: String, lanIp: String) {
        deviceDao.updateLanIp(deviceId, lanIp)
    }

    override suspend fun connectToServer(device: SmartPlugDevice, profile: com.smartplug.app.domain.model.ServerConnectionProfile): ApiResult<Unit> {
        val lanIp = device.lanIp ?: return missingLanIpFailure()
        val deviceBearer = requireOwnerToken(device.deviceId) ?: return missingTokenFailure()
        val serverBearer = tokenStore.serverApiToken(profile.serverId)?.let { "Bearer $it" }
            ?: return missingServerTokenFailure()
        val serverApi = apiClientFactory.serverApi(ApiClientFactory.hostBaseUrl(profile.brokerHost))

        // The secret must exist only during this suspend call: it is neither
        // stored in Room/EncryptedSharedPreferences nor surfaced to ViewModel/UI.
        val signingSecret = generateSigningSecret()
        val registered = safeApiCall {
            serverApi.registerMqttAuthDevice(
                serverBearer,
                device.deviceId,
                MqttAuthDeviceRequestDto(signingSecret),
            )
        }
        if (registered is ApiResult.Failure) return registered

        // Server automation is authoritative only after the SmartPlug has
        // entered Server mode.  Clear any retained automation for this device
        // before committing that handover so an old server attachment cannot
        // later control the newly attached plug.  If this fails, keep Direct
        // mode and revoke the freshly registered MQTT credential.
        val automationReset = safeApiCall {
            serverApi.resetAutomation(serverBearer, device.deviceId)
        }
        if (automationReset is ApiResult.Failure) {
            safeApiCall { serverApi.deleteMqttAuthDevice(serverBearer, device.deviceId) }
            return automationReset
        }

        val deviceApi = apiClientFactory.deviceApi(ApiClientFactory.lanBaseUrl(lanIp))
        val result = safeApiCall {
            deviceApi.setMqttSettings(deviceBearer, mapOf(
                "mode" to "mqtt",
                "host" to profile.brokerHost,
                "port" to profile.brokerPort.toString(),
                "username" to profile.mqttUsername,
                "password" to profile.mqttPassword,
                "topic" to profile.baseTopic,
                "protocol" to "2",
                "signing_secret" to signingSecret,
            ))
        }
        if (result is ApiResult.Success) {
            saveDevice(device.copy(
                integrationMode = IntegrationMode.SERVER,
                serverId = profile.serverId,
                serverHost = profile.brokerHost,
                serverPort = 80,
            ))
        } else {
            // Do not leave a usable server-side key when the SmartPlug failed
            // to atomically persist/configure the same secret.  Cleanup is
            // best effort; the local profile remains Direct either way.
            safeApiCall { serverApi.deleteMqttAuthDevice(serverBearer, device.deviceId) }
        }
        return result
    }

    override suspend fun disconnectFromServer(device: SmartPlugDevice, force: Boolean): ApiResult<Unit> {
        val lanIp = device.lanIp ?: return missingLanIpFailure()
        val bearer = requireOwnerToken(device.deviceId) ?: return missingTokenFailure()
        // If this phone no longer holds the server (Unpair Server removed its token), there is no
        // server cleanup left to do and the SmartPlug must still be able to leave Server mode.
        val serverToken = device.serverId?.let { tokenStore.serverApiToken(it) }
        val serverHost = device.serverHost
        val serverApi = if (serverToken != null && serverHost != null) {
            apiClientFactory.serverApi(ApiClientFactory.hostBaseUrl(serverHost, device.serverPort))
        } else null

        // A Direct profile must not leave an active server timer or schedule
        // behind.  Detach server automation first; if the server is not
        // reachable or refuses the reset, preserve Server mode so the user can
        // retry instead of creating an ambiguous split authority.
        if (serverApi != null) {
            val automationReset = safeApiCall {
                serverApi.resetAutomation("Bearer $serverToken", device.deviceId)
            }
            // A forced disconnect (confirmed by the user because the server is unreachable or rejects
            // us) continues; the server may keep its timer/schedule for this device until it is reset.
            if (automationReset is ApiResult.Failure && !force) return automationReset
        }

        val api = apiClientFactory.deviceApi(ApiClientFactory.lanBaseUrl(lanIp))
        val result = safeApiCall {
            api.setMqttSettings(bearer, mapOf(
                "mode" to "rest", "host" to "", "port" to "0", "username" to "", "password" to "", "topic" to "",
            ))
        }
        if (result is ApiResult.Success) {
            saveDevice(device.copy(integrationMode = IntegrationMode.DIRECT, serverId = null, serverHost = null, serverPort = 80))
            // The SmartPlug has already atomically returned to Direct mode.
            // Revoke the now-unused server key when this phone owns the
            // matching server profile, but never turn a successful Direct
            // disconnect into a failure merely because the server is offline.
            if (serverApi != null) {
                safeApiCall {
                    serverApi.deleteMqttAuthDevice("Bearer $serverToken", device.deviceId)
                }
            }
        }
        return result
    }

    override suspend fun canManageServer(device: SmartPlugDevice): Boolean {
        when (tokenStore.accessRole(device.deviceId)) {
            DeviceAccessRole.OWNER -> return true
            DeviceAccessRole.MEMBER -> return false
            DeviceAccessRole.UNKNOWN -> Unit
        }

        // Pre-role-marker installations are deliberately not assumed to be
        // owners.  Probe an already owner-only endpoint once, then persist the
        // result so the menu is correctly gated on later launches.  This does
        // not alter relay/read access, which continues using either credential.
        val lanIp = device.lanIp ?: return false
        val credential = tokenStore.accessCredential(device.deviceId) ?: return false
        val api = apiClientFactory.deviceApi(ApiClientFactory.lanBaseUrl(lanIp))
        return when (safeApiCall { api.getAccessCredentials("Bearer $credential") }) {
            is ApiResult.Success -> {
                tokenStore.setOwnerToken(device.deviceId, credential)
                true
            }
            is ApiResult.Failure -> false
        }
    }

    override suspend fun fetchStatus(device: SmartPlugDevice): ApiResult<DeviceStatus> =
        when (device.integrationMode) {
            IntegrationMode.DIRECT -> fetchDirectStatus(device)
            IntegrationMode.SERVER -> fetchServerStatus(device)
        }

    override suspend fun fetchMeasurement(device: SmartPlugDevice): ApiResult<ElectricalMeasurement> =
        when (device.integrationMode) {
            IntegrationMode.DIRECT -> fetchDirectMeasurement(device)
            IntegrationMode.SERVER -> fetchServerMeasurement(device)
        }

    override suspend fun fetchServerSnapshot(device: SmartPlugDevice): ApiResult<DeviceSnapshot> {
        if (device.integrationMode != IntegrationMode.SERVER) {
            return ApiResult.Failure(ApiFailure(0, "server_snapshot_unavailable"))
        }
        val host = device.serverHost ?: return fetchDirectSnapshot(device)
        val bearer = device.serverId?.let(tokenStore::serverApiToken)?.let { "Bearer $it" }
            ?: return fetchDirectSnapshot(device)
        val api = apiClientFactory.serverMonitoringApi(ApiClientFactory.hostBaseUrl(host, device.serverPort))
        return safeApiCall { api.getLatest(bearer, device.deviceId) }.map { dto ->
            DeviceSnapshot(serverStatus(dto), serverMeasurement(dto))
        }
    }

    override suspend fun verifyDeviceIdentity(device: SmartPlugDevice): ApiResult<Boolean> =
        fetchDirectStatus(device).map { it.deviceId == device.deviceId }

    /** Server routing changes need the local owner credential, never a member credential. */
    private fun requireOwnerToken(deviceId: String): String? =
        tokenStore.accessRole(deviceId).takeIf { it == DeviceAccessRole.OWNER }
            ?.let { tokenStore.accessCredential(deviceId) }
            ?.let { "Bearer $it" }

    /** Daily status/measurement reads intentionally remain available to owner and member phones. */
    private fun requireOperationalCredential(deviceId: String): String? =
        tokenStore.accessCredential(deviceId)?.let { "Bearer $it" }

    private fun missingTokenFailure() = ApiResult.Failure(ApiFailure(0, "missing_owner_token"))
    private fun missingLanIpFailure() = ApiResult.Failure(ApiFailure(0, "missing_lan_ip"))
    private fun missingServerFailure() = ApiResult.Failure(ApiFailure(0, "missing_server_host"))
    private fun missingServerTokenFailure() = ApiResult.Failure(ApiFailure(0, "missing_server_api_token"))

    private fun generateSigningSecret(): String {
        val bytes = ByteArray(SPMQTT2_SECRET_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private suspend fun fetchDirectStatus(device: SmartPlugDevice): ApiResult<DeviceStatus> {
        val lanIp = device.lanIp ?: return missingLanIpFailure()
        val bearer = requireOperationalCredential(device.deviceId) ?: return missingTokenFailure()
        val api = apiClientFactory.deviceApi(ApiClientFactory.lanBaseUrl(lanIp))
        return safeApiCall { api.getStatus(bearer) }.map { dto ->
            DeviceStatus(
                deviceId = dto.deviceId,
                relayState = parseRelayState(dto.relayState),
                relayActuationEnabled = dto.relayActuationEnabled,
                wifiConnected = dto.wifiConnected,
                hasSample = dto.hasSample,
                fresh = dto.fresh,
                sampleAgeMs = dto.sampleAgeMs,
                timerRemainingMs = dto.timer?.remainingMs ?: 0,
                timerArmed = dto.timer?.let { it.active && !it.running } == true,
                scheduleRemainingMs = dto.schedule?.next?.remainingSeconds?.times(1000L) ?: 0,
                scheduleTurnOn = dto.schedule?.next?.state?.let { if (it == "on") true else if (it == "off") false else null },
                energySavedWh = dto.energyPersistence?.takeIf { it.ready && it.savedAvailable }?.savedWh,
                energyNextSaveMs = dto.energyPersistence?.takeIf { it.ready }?.nextSaveSeconds?.times(1000L),
                firmwareVersion = dto.firmware?.version,
                uptimeSeconds = dto.uptimeSeconds,
                resetReason = dto.resetReason,
                bootCount = dto.bootCount,
                powerOnPolicy = com.smartplug.app.domain.model.PowerOnPolicy.fromWire(dto.powerOnPolicy),
                restoreDelaySeconds = dto.restoreDelaySeconds,
                protection = dto.protection?.let {
                    com.smartplug.app.domain.model.ProtectionStatus(it.enabled, it.tripped, it.warnA, it.tripA)
                },
                overcurrentWarning = dto.overcurrentWarning,
            )
        }
    }

    private suspend fun fetchDirectMeasurement(device: SmartPlugDevice): ApiResult<ElectricalMeasurement> {
        val lanIp = device.lanIp ?: return missingLanIpFailure()
        val bearer = requireOperationalCredential(device.deviceId) ?: return missingTokenFailure()
        val api = apiClientFactory.deviceApi(ApiClientFactory.lanBaseUrl(lanIp))
        return safeApiCall { api.getAllParameters(bearer) }.map { dto ->
            ElectricalMeasurement(
                capturedAtMs = dto.capturedAtMs,
                hasSample = dto.hasSample,
                fresh = dto.fresh,
                sampleAgeMs = dto.sampleAgeMs,
                calibrated = dto.calibration == "calibrated",
                voltageV = dto.electrical.voltageV,
                currentA = dto.electrical.currentA,
                activePowerW = dto.electrical.activePowerW,
                apparentPowerVa = dto.electrical.apparentPowerVa,
                powerFactor = dto.electrical.powerFactor,
                energyWh = dto.electrical.energyWh,
            )
        }
    }

    private suspend fun fetchServerStatus(device: SmartPlugDevice): ApiResult<DeviceStatus> {
        val host = device.serverHost ?: return fetchDirectStatus(device)
        // A phone that enrolled as a member can truthfully learn that the SmartPlug is in
        // Server mode without receiving another phone's Server API token.  Until this phone
        // separately registers that server, keep local monitoring available through the
        // member-authorized SmartPlug REST API instead of treating the device as offline.
        val bearer = device.serverId?.let(tokenStore::serverApiToken)?.let { "Bearer $it" }
            ?: return fetchDirectStatus(device)
        val api = apiClientFactory.serverMonitoringApi(ApiClientFactory.hostBaseUrl(host, device.serverPort))
        return safeApiCall { api.getLatest(bearer, device.deviceId) }.map { dto ->
            serverStatus(dto)
        }
    }

    private suspend fun fetchServerMeasurement(device: SmartPlugDevice): ApiResult<ElectricalMeasurement> {
        val host = device.serverHost ?: return fetchDirectMeasurement(device)
        val bearer = device.serverId?.let(tokenStore::serverApiToken)?.let { "Bearer $it" }
            ?: return fetchDirectMeasurement(device)
        val api = apiClientFactory.serverMonitoringApi(ApiClientFactory.hostBaseUrl(host, device.serverPort))
        return safeApiCall { api.getLatest(bearer, device.deviceId) }.map { dto ->
            serverMeasurement(dto)
        }
    }

    private suspend fun fetchDirectSnapshot(device: SmartPlugDevice): ApiResult<DeviceSnapshot> {
        val status = fetchDirectStatus(device)
        if (status is ApiResult.Failure) return status
        val measurement = fetchDirectMeasurement(device)
        if (measurement is ApiResult.Failure) return measurement
        return ApiResult.Success(DeviceSnapshot(
            status = (status as ApiResult.Success).value,
            measurement = (measurement as ApiResult.Success).value,
        ))
    }

    private fun serverStatus(dto: ServerLatestDto): DeviceStatus {
        val ageMs = (System.currentTimeMillis() - dto.capturedAtMs).coerceAtLeast(0)
        return DeviceStatus(
            deviceId = dto.deviceId,
            relayState = parseRelayState(dto.relayState),
            relayActuationEnabled = dto.status != "offline",
            wifiConnected = dto.status != "offline",
            hasSample = true,
            fresh = dto.status == "online",
            sampleAgeMs = ageMs,
            timerRemainingMs = when {
                dto.timerDeadlineUtc > 0 -> (dto.timerDeadlineUtc * 1000L - System.currentTimeMillis()).coerceAtLeast(0)
                else -> dto.timerDurationSeconds * 1000L
            },
            timerArmed = dto.timerDeadlineUtc == 0L && dto.timerDurationSeconds > 0,
            scheduleRemainingMs = dto.schedule?.next?.remainingSeconds?.times(1000L) ?: 0,
            scheduleTurnOn = dto.schedule?.next?.state?.let { if (it == "on") true else if (it == "off") false else null },
        )
    }

    private fun serverMeasurement(dto: ServerLatestDto): ElectricalMeasurement {
        val ageMs = (System.currentTimeMillis() - dto.capturedAtMs).coerceAtLeast(0)
        return ElectricalMeasurement(
            capturedAtMs = dto.capturedAtMs,
            hasSample = true,
            fresh = dto.status == "online",
            sampleAgeMs = ageMs,
            calibrated = dto.calibrated,
            voltageV = dto.voltageV,
            currentA = dto.currentA,
            activePowerW = dto.activePowerW,
            apparentPowerVa = dto.apparentPowerVa,
            powerFactor = dto.powerFactor,
            energyWh = dto.energyWh,
        )
    }

    companion object {
        private const val SPMQTT2_SECRET_BYTES = 32
        private val secureRandom = SecureRandom()
        fun parseRelayState(wireValue: String): RelayState = when (wireValue) {
            "on" -> RelayState.ON
            "off" -> RelayState.OFF
            else -> RelayState.UNKNOWN
        }
    }
}

private fun DeviceEntity.toDomain() = SmartPlugDevice(
    deviceId = deviceId,
    staMac = staMac,
    displayName = displayName,
    lanIp = lanIp,
    integrationMode = if (integrationMode == "server") IntegrationMode.SERVER else IntegrationMode.DIRECT,
    serverId = serverId,
    serverHost = serverHost,
    serverPort = serverPort,
    apUnitId = apUnitId,
)

private fun SmartPlugDevice.toEntity() = DeviceEntity(
    deviceId = deviceId,
    staMac = staMac,
    displayName = displayName,
    lanIp = lanIp,
    integrationMode = if (integrationMode == IntegrationMode.SERVER) "server" else "direct",
    serverId = serverId,
    serverHost = serverHost,
    serverPort = serverPort,
    apUnitId = apUnitId,
)
