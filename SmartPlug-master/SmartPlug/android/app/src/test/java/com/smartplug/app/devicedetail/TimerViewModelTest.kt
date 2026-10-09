package com.smartplug.app.devicedetail

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.data.local.db.HistoryDao
import com.smartplug.app.data.local.db.LoadSignatureDao
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.ApiFailure
import com.smartplug.app.domain.model.DeviceSnapshot
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.ElectricalMeasurement
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceControlRepository
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.RelayRepository
import com.smartplug.app.ui.screens.devicedetail.DeviceDetailViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Guards the Timer dialog command boundary independently of Compose gestures. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var devices: DeviceRepository
    private lateinit var controls: DeviceControlRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        devices = mockk(relaxed = true)
        controls = mockk(relaxed = true)
        coEvery { devices.getDevice(device.deviceId) } returns device
        coEvery { devices.fetchStatus(device) } returns ApiResult.Success(status)
        coEvery { devices.fetchMeasurement(device) } returns ApiResult.Success(measurement)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `apply converts total Clock hours and invokes success only after control succeeds`() = runTest {
        coEvery { controls.applyTimer(device, 2, 3, 4, 5) } returns ApiResult.Success(Unit)
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        var successful = false

        viewModel.applyTimer(hours = 51, minutes = 4, seconds = 5) { successful = true }
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { controls.applyTimer(device, 2, 3, 4, 5) }
        assertThat(successful).isTrue()
        assertThat(viewModel.uiState.value.lastError).isNull()
    }

    @Test
    fun `failed apply keeps dialog callback uninvoked`() = runTest {
        coEvery { controls.applyTimer(device, 0, 0, 0, 1) } returns ApiResult.Failure(
            com.smartplug.app.domain.model.ApiFailure(503, "network_timeout", "offline"),
        )
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        var successful = false

        viewModel.applyTimer(hours = 0, minutes = 0, seconds = 1) { successful = true }
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { controls.applyTimer(device, 0, 0, 0, 1) }
        assertThat(successful).isFalse()
        assertThat(viewModel.uiState.value.lastError).isEqualTo("Waktu tunggu jaringan habis. Periksa koneksi Wi-Fi.")
    }

    @Test
    fun `zero duration never invokes repository or success callback`() = runTest {
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        var successful = false

        viewModel.applyTimer(hours = 0, minutes = 0, seconds = 0) { successful = true }
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { controls.applyTimer(any(), any(), any(), any(), any()) }
        assertThat(successful).isFalse()
        assertThat(viewModel.uiState.value.lastError).isEqualTo("Durasi timer tidak valid.")
    }

    @Test
    fun `direct refresh reads status before measurement to avoid concurrent ESP8266 sockets`() = runTest {
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.refresh()

        coVerifyOrder {
            devices.fetchStatus(device)
            devices.fetchMeasurement(device)
        }
    }

    @Test
    fun `direct refresh retries one transient measurement failure without marking device offline`() = runTest {
        coEvery { devices.fetchMeasurement(device) } returns
            ApiResult.Failure(ApiFailure(0, "no_connectivity", "socket_closed")) andThen
            ApiResult.Success(measurement)
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { devices.fetchMeasurement(device) }
        assertThat(viewModel.uiState.value.status).isEqualTo(status)
        assertThat(viewModel.uiState.value.measurement).isEqualTo(measurement)
        assertThat(viewModel.uiState.value.lastError).isNull()
    }

    @Test
    fun `server refresh retries one transient latest failure without marking device offline`() = runTest {
        val serverDevice = device.copy(
            integrationMode = IntegrationMode.SERVER,
            serverId = "server-1",
            serverHost = "192.168.1.9",
        )
        val snapshot = DeviceSnapshot(status = status.copy(deviceId = serverDevice.deviceId), measurement = measurement)
        coEvery { devices.getDevice(serverDevice.deviceId) } returns serverDevice
        coEvery { devices.fetchServerSnapshot(serverDevice) } returns
            ApiResult.Failure(ApiFailure(0, "no_connectivity", "socket_closed")) andThen
            ApiResult.Success(snapshot)
        val viewModel = DeviceDetailViewModel(
            SavedStateHandle(mapOf("deviceId" to serverDevice.deviceId)),
            devices,
            mockk<RelayRepository>(relaxed = true),
            controls,
            mockk<HistoryDao>(relaxed = true),
            mockk<LoadSignatureDao>(relaxed = true),
            mockk<ServerProfileStore>(relaxed = true),
        mockk<com.smartplug.app.data.local.AppPreferences>(relaxed = true),
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) { devices.fetchServerSnapshot(serverDevice) }
        assertThat(viewModel.uiState.value.status).isEqualTo(snapshot.status)
        assertThat(viewModel.uiState.value.measurement).isEqualTo(snapshot.measurement)
        assertThat(viewModel.uiState.value.lastError).isNull()
    }

    @Test
    fun `reset invokes repository and success callback only after successful reset`() = runTest {
        coEvery { controls.resetTimer(device) } returns ApiResult.Success(Unit)
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        var successful = false

        viewModel.resetTimer { successful = true }
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { controls.resetTimer(device) }
        assertThat(successful).isTrue()
    }

    private fun viewModel() = DeviceDetailViewModel(
        SavedStateHandle(mapOf("deviceId" to device.deviceId)),
        devices,
        mockk<RelayRepository>(relaxed = true),
        controls,
        mockk<HistoryDao>(relaxed = true),
        mockk<LoadSignatureDao>(relaxed = true),
        mockk<ServerProfileStore>(relaxed = true),
        mockk<com.smartplug.app.data.local.AppPreferences>(relaxed = true),
    )

    private companion object {
        val device = SmartPlugDevice(
            deviceId = "SP-TIMER-0001",
            staMac = "AA:BB:CC:DD:EE:01",
            displayName = "Timer plug",
            lanIp = "192.168.1.50",
            integrationMode = IntegrationMode.DIRECT,
        )
        val status = DeviceStatus(
            deviceId = device.deviceId,
            relayState = RelayState.ON,
            relayActuationEnabled = true,
            wifiConnected = true,
            hasSample = true,
            fresh = true,
            sampleAgeMs = 0,
        )
        val measurement = ElectricalMeasurement(
            capturedAtMs = 1L,
            hasSample = true,
            fresh = true,
            sampleAgeMs = 0,
            calibrated = true,
            voltageV = 220.0,
            currentA = 0.1,
            activePowerW = 10.0,
            apparentPowerVa = 11.0,
            powerFactor = 0.9,
            energyWh = 1.0,
        )
    }
}
