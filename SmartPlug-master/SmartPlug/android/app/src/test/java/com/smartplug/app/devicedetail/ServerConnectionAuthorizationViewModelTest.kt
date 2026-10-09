package com.smartplug.app.devicedetail

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.data.local.db.HistoryDao
import com.smartplug.app.data.local.db.LoadSignatureDao
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.RegisteredServer
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceControlRepository
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.RelayRepository
import com.smartplug.app.ui.screens.devicedetail.DeviceDetailViewModel
import io.mockk.coEvery
import io.mockk.coVerify
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

/** Server MQTT routing must stay inaccessible to a member-phone credential. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectionAuthorizationViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var devices: DeviceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        devices = mockk(relaxed = true)
        coEvery { devices.getDevice(device.deviceId) } returns device
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `member credential cannot initiate saved-server connection`() = runTest {
        coEvery { devices.canManageServer(device) } returns false
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.connectSavedServer(server)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { devices.connectToServer(any(), any()) }
        assertThat(viewModel.uiState.value.canManageServer).isFalse()
        assertThat(viewModel.uiState.value.lastError).contains("Hanya pemilik")
    }

    @Test
    fun `owner credential may initiate saved-server connection`() = runTest {
        coEvery { devices.canManageServer(device) } returns true
        coEvery { devices.connectToServer(device, any()) } returns ApiResult.Success(Unit)
        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.connectSavedServer(server)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            devices.connectToServer(device, match { it.serverId == server.serverId && it.brokerHost == server.host })
        }
        assertThat(viewModel.uiState.value.device?.integrationMode).isEqualTo(IntegrationMode.SERVER)
    }

    private fun viewModel() = DeviceDetailViewModel(
        SavedStateHandle(mapOf("deviceId" to device.deviceId)),
        devices,
        mockk<RelayRepository>(relaxed = true),
        mockk<DeviceControlRepository>(relaxed = true),
        mockk<HistoryDao>(relaxed = true),
        mockk<LoadSignatureDao>(relaxed = true),
        mockk<ServerProfileStore>(relaxed = true),
        mockk<com.smartplug.app.data.local.AppPreferences>(relaxed = true),
    )

    private companion object {
        val device = SmartPlugDevice(
            deviceId = "SP-MEMBER-0001",
            staMac = "AA:BB:CC:DD:EE:01",
            displayName = "Member plug",
            lanIp = "192.168.1.50",
            integrationMode = IntegrationMode.DIRECT,
        )
        val server = RegisteredServer(
            serverId = "server-1",
            displayName = "Home server",
            host = "192.168.1.9",
            mqttPort = 1883,
            mqttUsername = "smartplug",
            mqttPassword = "secret",
        )
    }
}
