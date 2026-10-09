package com.smartplug.app.addexisting

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.DeviceApi
import com.smartplug.app.data.remote.dto.AccessEnrollRequestDto
import com.smartplug.app.data.remote.dto.AccessEnrollResponseDto
import com.smartplug.app.data.remote.dto.DeviceAccessProfileDto
import com.smartplug.app.domain.model.DiscoveredExistingSmartPlug
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.DiscoveryRepository
import com.smartplug.app.ui.screens.addexisting.AddExistingSmartPlugViewModel
import com.smartplug.app.ui.screens.addexisting.ExistingSmartPlugStep
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression coverage for the second-phone enrollment path. The shared Direct AP onboarding
 * must remain independent: an invitation only creates a member credential on the joining phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddExistingSmartPlugViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var discovery: DiscoveryRepository
    private lateinit var devices: DeviceRepository
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var factory: ApiClientFactory
    private lateinit var api: DeviceApi

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        discovery = mockk()
        devices = mockk(relaxed = true)
        tokenStore = mockk(relaxed = true)
        factory = mockk()
        api = mockk()
        every { factory.deviceApi(any()) } returns api
        coEvery { discovery.discoverExistingDevices(any()) } returns listOf(discoveredDevice())
        coEvery { devices.getDevice(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `member enrollment stores only member credential and safe profile`() = runTest {
        val safeProfile = profile()
        coEvery { api.enrollAccess(AccessEnrollRequestDto("SP-HP2-0001", "123456")) } returns
            retrofit2.Response.success(
                AccessEnrollResponseDto(
                    apiVersion = "1.0",
                    credential = "member-credential-only",
                    credentialId = "member-1",
                    role = "member",
                    profile = safeProfile,
                ),
            )
        coEvery { api.getAccessProfile("Bearer member-credential-only") } returns retrofit2.Response.success(safeProfile)
        val viewModel = AddExistingSmartPlugViewModel(discovery, devices, factory, tokenStore)
        dispatcher.scheduler.advanceUntilIdle()

        selectAndEnroll(viewModel)

        assertThat(viewModel.uiState.value.step).isEqualTo(ExistingSmartPlugStep.SUCCESS)
        assertThat(viewModel.uiState.value.resultDeviceId).isEqualTo("SP-HP2-0001")
        verify(exactly = 1) { tokenStore.setMemberCredential("SP-HP2-0001", "member-credential-only") }
        verify(exactly = 0) { tokenStore.setOwnerToken(any(), any()) }
        verify(exactly = 0) { tokenStore.setServerApiToken(any(), any()) }
        coVerify(exactly = 1) {
            devices.saveDevice(match { saved ->
                saved.deviceId == "SP-HP2-0001" &&
                    saved.displayName == "Dapur Utama" &&
                    saved.integrationMode.name == "SERVER" &&
                    saved.serverHost == "server.local" &&
                    saved.serverId == null &&
                    saved.staMac == "not-disclosed"
            })
        }

        coVerify(exactly = 1) { api.enrollAccess(AccessEnrollRequestDto("SP-HP2-0001", "123456")) }
        coVerify(exactly = 1) { api.getAccessProfile("Bearer member-credential-only") }
    }

    @Test
    fun `expired invitation does not store credential or replace local device state`() = runTest {
        coEvery { api.enrollAccess(any()) } returns retrofit2.Response.error(
            410,
            """{"error":{"code":"invite_expired","message":"expired"}}""".toResponseBody(
                "application/json".toMediaType(),
            ),
        )
        val viewModel = AddExistingSmartPlugViewModel(discovery, devices, factory, tokenStore)
        dispatcher.scheduler.advanceUntilIdle()

        selectAndEnroll(viewModel)

        assertThat(viewModel.uiState.value.step).isEqualTo(ExistingSmartPlugStep.FAILED)
        assertThat(viewModel.uiState.value.invitationCode).isEmpty()
        assertThat(viewModel.uiState.value.errorMessage).contains("invite_expired")
        verify(exactly = 0) { tokenStore.setMemberCredential(any(), any()) }
        verify(exactly = 0) { tokenStore.clearAccessCredential(any()) }
        coVerify(exactly = 0) { devices.saveDevice(any()) }
    }

    private fun selectAndEnroll(viewModel: AddExistingSmartPlugViewModel) {
        viewModel.selectDevice("SP-HP2-0001")
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.uiState.value.step).isEqualTo(ExistingSmartPlugStep.ENTER_INVITATION)
        viewModel.setInvitationCode("12a345678")
        assertThat(viewModel.uiState.value.invitationCode).isEqualTo("123456")
        viewModel.enroll()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun discoveredDevice() = DiscoveredExistingSmartPlug(
        deviceId = "SP-HP2-0001",
        host = "192.168.1.50",
    )

    private fun profile() = DeviceAccessProfileDto(
        apiVersion = "1.0",
        deviceId = "SP-HP2-0001",
        integrationMode = "server",
        serverHost = "server.local",
        serverPort = 80,
        displayName = "Dapur Utama",
        relayState = "off",
    )
}
