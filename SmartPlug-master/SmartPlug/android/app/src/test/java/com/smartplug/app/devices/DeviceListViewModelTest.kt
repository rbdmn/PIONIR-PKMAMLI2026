package com.smartplug.app.devices

import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.DeviceSnapshot
import com.smartplug.app.domain.model.DeviceStatus
import com.smartplug.app.domain.model.ElectricalMeasurement
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.ui.screens.devices.DeviceListViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for a cold Home launch.  refreshAll must query the persisted device
 * stream directly instead of relying on uiState, whose initial StateFlow value is intentionally
 * empty before Room has emitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: DeviceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cold refresh fetches saved server device before ui state has emitted`() = runTest {
        val device = SmartPlugDevice(
            deviceId = "SP-TEST",
            staMac = "00:11:22:33:44:55",
            displayName = "Saved plug",
            lanIp = "192.168.1.50",
            integrationMode = IntegrationMode.SERVER,
            serverId = "server-1",
            serverHost = "192.168.1.20",
        )
        every { repository.observeDevices() } returns flowOf(listOf(device))
        coEvery { repository.fetchServerSnapshot(device) } returns ApiResult.Success(snapshot(device))

        // History is only used for the display-only "today" figure; a relaxed mock is enough here.
        val viewModel = DeviceListViewModel(repository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        // Do not collect uiState first: this reproduces the immediate Home polling race.
        viewModel.refreshAll()

        coVerify(exactly = 1) { repository.fetchServerSnapshot(device) }
    }

    private fun snapshot(device: SmartPlugDevice) = DeviceSnapshot(
        status = DeviceStatus(
            deviceId = device.deviceId,
            relayState = RelayState.OFF,
            relayActuationEnabled = true,
            wifiConnected = true,
            hasSample = true,
            fresh = true,
            sampleAgeMs = 50,
        ),
        measurement = ElectricalMeasurement(
            capturedAtMs = 1_000,
            hasSample = true,
            fresh = true,
            sampleAgeMs = 50,
            calibrated = true,
            voltageV = 220.0,
            currentA = 0.2,
            activePowerW = 40.0,
            apparentPowerVa = 44.0,
            powerFactor = 0.9,
            energyWh = 123.0,
        ),
    )
}
