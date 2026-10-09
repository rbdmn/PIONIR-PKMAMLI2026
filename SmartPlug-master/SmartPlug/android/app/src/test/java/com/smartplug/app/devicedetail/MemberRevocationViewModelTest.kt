package com.smartplug.app.devicedetail

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.data.local.db.HistoryDao
import com.smartplug.app.data.local.db.LoadSignatureDao
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.ApiFailure
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.ManagedMember
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceControlRepository
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.RelayRepository
import com.smartplug.app.ui.screens.devicedetail.DeviceDetailViewModel
import io.mockk.coEvery
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

/** A failed owner-only revoke must never optimistically remove a member from the visible state. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberRevocationViewModelTest {

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
        coEvery { devices.getDevice("SP-OWNER-0001") } returns device
        coEvery { controls.listManagedMembers(device) } returns ApiResult.Success(
            listOf(ManagedMember("member-a"), ManagedMember("member-b")),
        )
        coEvery { controls.revokeManagedMember(device, "member-a") } returns
            ApiResult.Failure(ApiFailure(409, "credential_not_found", "already revoked"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `failed revocation leaves previously loaded members untouched`() = runTest {
        val viewModel = DeviceDetailViewModel(
            SavedStateHandle(mapOf("deviceId" to device.deviceId)),
            devices,
            mockk(relaxed = true),
            controls,
            mockk<HistoryDao>(relaxed = true),
            mockk<LoadSignatureDao>(relaxed = true),
            mockk<ServerProfileStore>(relaxed = true),
        mockk<com.smartplug.app.data.local.AppPreferences>(relaxed = true),
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.loadManagedMembers()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.managedMembers.map { it.credentialId })
            .containsExactly("member-a", "member-b").inOrder()

        viewModel.revokeManagedMember("member-a")
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.uiState.value.managedMembers.map { it.credentialId })
            .containsExactly("member-a", "member-b").inOrder()
        assertThat(viewModel.uiState.value.revokingMemberId).isNull()
        assertThat(viewModel.uiState.value.lastError).isEqualTo("already revoked")
    }

    private companion object {
        val device = SmartPlugDevice(
            deviceId = "SP-OWNER-0001",
            staMac = "AA:BB:CC:DD:EE:FF",
            displayName = "Owner plug",
            lanIp = "192.168.1.2",
            integrationMode = IntegrationMode.DIRECT,
        )
    }
}
