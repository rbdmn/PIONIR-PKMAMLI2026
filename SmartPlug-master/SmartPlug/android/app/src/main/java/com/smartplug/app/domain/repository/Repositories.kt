package com.smartplug.app.domain.repository

import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.DeviceSnapshot
import com.smartplug.app.domain.model.DeviceSchedule
import com.smartplug.app.domain.model.DiscoveredServer
import com.smartplug.app.domain.model.DiscoveredExistingSmartPlug
import com.smartplug.app.domain.model.DiscoveredServerSetupAp
import com.smartplug.app.domain.model.DiscoveredSmartPlugAp
import com.smartplug.app.domain.model.ElectricalMeasurement
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.HistoryResolution
import com.smartplug.app.domain.model.HomeWifiNetwork
import com.smartplug.app.domain.model.MemberInvitation
import com.smartplug.app.domain.model.ManagedMember
import com.smartplug.app.domain.model.PairInfo
import com.smartplug.app.domain.model.PairingStatus
import com.smartplug.app.domain.model.RelayCommandResult
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.ServerConnectionProfile
import com.smartplug.app.domain.model.SmartPlugDevice
import kotlinx.coroutines.flow.Flow

/** Wraps Android's Wi-Fi APIs for the onboarding flow only (design.md "Alur sistem"). */
interface WifiOnboardingRepository {
    /** True if the runtime permission needed to read scan results/connect is already granted. */
    fun hasRequiredPermission(): Boolean

    /** On Android 12 and below, Wi-Fi scan results are silently empty — not an error, not a
     * permission failure, just always empty — unless the phone's system Location toggle is also
     * on, regardless of the app's own permission grant. Android 13+ with NEARBY_WIFI_DEVICES
     * doesn't have this requirement. */
    fun isLocationServiceRequiredAndDisabled(): Boolean

    /** Scans nearby Wi-Fi and returns only APs advertised as `SP-<unit_id>`. */
    suspend fun scanForSmartPlugAps(): Result<List<DiscoveredSmartPlugAp>>

    /** Scans nearby Wi-Fi for a powered-on ServerSmartPlug in setup mode. */
    suspend fun scanForServerSetupAps(): Result<List<DiscoveredServerSetupAp>>

    /**
     * Returns the phone's current nearby Wi-Fi view for ServerSmartPlug provisioning.
     *
     * The phone must be next to the server to join its setup AP, so this is a reliable
     * source for selecting the same home network without interrupting the server AP by
     * asking its radio to perform a blocking scan.
     */
    suspend fun scanNearbyHomeWifi(): Result<List<HomeWifiNetwork>>

    /** Requests a bound connection to the SmartPlug AP via `WifiNetworkSpecifier` and routes all
     * pairing HTTP calls over it until [releaseApBinding] is called. */
    suspend fun connectToAp(ssid: String, password: String): Result<Unit>

    /** Releases the AP binding so the phone falls back to its normal (home) Wi-Fi network. */
    fun releaseApBinding()

    /** Suspends until the phone is back on a non-SmartPlug Wi-Fi network, or times out. */
    suspend fun awaitHomeWifiRestored(timeoutMs: Long): Boolean
}

/** NSD/mDNS lookups, design.md "Standar nama discovery" + "Jika IP berubah". */
interface DiscoveryRepository {
    suspend fun discoverServer(timeoutMs: Long): DiscoveredServer?
    /** Finds already paired SmartPlugs on the home LAN for the explicit HP2 invitation flow. */
    suspend fun discoverExistingDevices(timeoutMs: Long): List<DiscoveredExistingSmartPlug>
    suspend fun resolveDeviceLanIp(deviceId: String, timeoutMs: Long): String?
}

/** SmartPlug AP-only pairing calls, design.md "Pairing API". */
interface PairingRepository {
    suspend fun fetchPairInfo(): ApiResult<PairInfo>
    suspend fun scanHomeWifi(pairingToken: String): ApiResult<List<HomeWifiNetwork>>
    suspend fun configureDirect(pairingToken: String, ssid: String, password: String): ApiResult<String>
    /** Emits one [PairingStatus] per second until `connected`/`failed`, per design.md. */
    fun pollStatus(pairingToken: String, configurationId: String): Flow<ApiResult<PairingStatus>>

    /** Reads the same pairing status endpoint after the phone has handed off from the setup AP
     * to the device's mDNS-resolved LAN address. */
    suspend fun fetchStatusAt(
        baseUrl: String,
        pairingToken: String,
        configurationId: String,
    ): ApiResult<PairingStatus>
}

/** Local device registry + operational status/measurement reads, routed by [SmartPlugDevice.integrationMode]. */
interface DeviceRepository {
    fun observeDevices(): Flow<List<SmartPlugDevice>>
    suspend fun getDevice(deviceId: String): SmartPlugDevice?
    suspend fun saveDevice(device: SmartPlugDevice)
    suspend fun removeDevice(deviceId: String)
    suspend fun renameDevice(deviceId: String, displayName: String)
    suspend fun updateLanIp(deviceId: String, lanIp: String)
    /** Applies the MQTT profile to the already paired unit, then persists its selected route. */
    suspend fun connectToServer(device: SmartPlugDevice, profile: ServerConnectionProfile): ApiResult<Unit>
    /** Restores direct REST as the app route and clears the device's MQTT profile. */
    /** [force] = leave Server mode even if the server cannot clean up its timer/schedule for this device. */
    suspend fun disconnectFromServer(device: SmartPlugDevice, force: Boolean = false): ApiResult<Unit>
    /**
     * True only for the owner credential.  A migrated legacy credential is
     * verified through the firmware's owner-only credential-list endpoint
     * before the UI exposes ServerSmartPlug configuration.
     */
    suspend fun canManageServer(device: SmartPlugDevice): Boolean

    suspend fun fetchStatus(device: SmartPlugDevice): ApiResult<DeviceStatus>
    suspend fun fetchMeasurement(device: SmartPlugDevice): ApiResult<ElectricalMeasurement>
    /** Reads one ServerSmartPlug snapshot so status and measurement cannot come from different polls. */
    suspend fun fetchServerSnapshot(device: SmartPlugDevice): ApiResult<DeviceSnapshot>

    /** Verifies the LAN `device_id` matches after onboarding hands control back to home Wi-Fi. */
    suspend fun verifyDeviceIdentity(device: SmartPlugDevice): ApiResult<Boolean>
}

/** Relay control, design.md "Status relay": command acceptance != physical settle. */
interface RelayRepository {
    suspend fun setRelay(device: SmartPlugDevice, targetOn: Boolean): ApiResult<RelayCommandResult>

    /** Polls until the device/server reports the settled [RelayState], or times out. */
    suspend fun awaitSettledState(
        device: SmartPlugDevice,
        commandId: String,
        targetState: RelayState,
    ): ApiResult<RelayCommandResult>
}

/** Safety-sensitive owner actions and countdown configuration. */
interface DeviceControlRepository {
    /** Owner-only label persisted by the device and exposed to later member enrolments. */
    suspend fun setSharedDisplayName(device: SmartPlugDevice, displayName: String): ApiResult<Unit>
    /** Uses the local SmartPlug owner credential; member credentials are rejected by firmware. */
    suspend fun createMemberInvitation(device: SmartPlugDevice): ApiResult<MemberInvitation>
    /** Owner-only member list and revocation, served by the local SmartPlug even in Server mode. */
    suspend fun listManagedMembers(device: SmartPlugDevice): ApiResult<List<ManagedMember>>
    suspend fun revokeManagedMember(device: SmartPlugDevice, credentialId: String): ApiResult<Unit>
    suspend fun resetEnergy(device: SmartPlugDevice): ApiResult<Unit>
    /** PROPOSED: what the relay does when power returns; owner only, served by the local SmartPlug. */
    suspend fun setPowerPolicy(device: SmartPlugDevice, policy: com.smartplug.app.domain.model.PowerOnPolicy, restoreDelaySeconds: Int): ApiResult<Unit>
    /** PROPOSED: overcurrent protection on/off; owner only, served by the local SmartPlug. */
    suspend fun setProtection(device: SmartPlugDevice, enabled: Boolean): ApiResult<Unit>
    suspend fun factoryReset(device: SmartPlugDevice): ApiResult<Unit>
    /**
     * Global reset has to know that the physical SmartPlug accepted its reset
     * before the app removes its profile and resets the MQTT server.  A
     * server-mode device still exposes its local owner-token API, so prefer it
     * when the device is on the same LAN; MQTT remains the remote fallback.
     */
    suspend fun factoryResetForGlobalReset(device: SmartPlugDevice): ApiResult<Unit>
    suspend fun applyTimer(device: SmartPlugDevice, days: Int, hours: Int, minutes: Int, seconds: Int): ApiResult<Unit>
    suspend fun resetTimer(device: SmartPlugDevice): ApiResult<Unit>
    suspend fun getSchedule(device: SmartPlugDevice): ApiResult<DeviceSchedule>
    suspend fun setScheduleEnabled(device: SmartPlugDevice, enabled: Boolean, timezoneOffsetMinutes: Int): ApiResult<DeviceSchedule>
    suspend fun addSchedule(device: SmartPlugDevice, hour: Int, minute: Int, turnOn: Boolean, event: String, timezoneOffsetMinutes: Int): ApiResult<DeviceSchedule>
    suspend fun deleteSchedule(device: SmartPlugDevice, index: Int): ApiResult<DeviceSchedule>
    suspend fun moveSchedule(device: SmartPlugDevice, from: Int, to: Int): ApiResult<DeviceSchedule>
}

/** Server-mode energy history, design.md "REST API aplikasi ke server" + "Retensi riwayat". */
interface HistoryRepository {
    suspend fun fetchHistory(
        device: SmartPlugDevice,
        fromUtcMs: Long,
        toUtcMs: Long,
        resolution: HistoryResolution,
    ): ApiResult<List<EnergyHistoryPoint>>

    /** Drops the on-phone cache of fetched history for a device. Never touches the server. */
    suspend fun clearCachedHistory(deviceId: String)
}

/** Read-only server storage status for the Storage tab. */
interface StorageRepository {
    /** Reads GET /api/v1/status of the server this device is attached to. Never sends commands. */
    suspend fun fetchSdUsage(device: com.smartplug.app.domain.model.SmartPlugDevice): ApiResult<com.smartplug.app.domain.model.SdUsage>

    /** PROPOSED: asks the server to delete its measurement history (frees SD space). */
    suspend fun resetServerHistory(device: com.smartplug.app.domain.model.SmartPlugDevice): ApiResult<Unit>
}
