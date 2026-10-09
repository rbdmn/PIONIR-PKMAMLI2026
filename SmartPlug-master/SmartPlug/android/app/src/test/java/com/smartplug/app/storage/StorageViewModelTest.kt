package com.smartplug.app.storage

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.local.AppPreferences
import com.smartplug.app.data.local.AppSettings
import com.smartplug.app.data.local.ServerProfileStore
import com.smartplug.app.domain.model.RegisteredServer
import kotlinx.coroutines.flow.MutableStateFlow
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.ApiFailure
import com.smartplug.app.domain.model.EnergyHistoryPoint
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.SdUsage
import com.smartplug.app.domain.model.SmartPlugDevice
import com.smartplug.app.domain.repository.DeviceRepository
import com.smartplug.app.domain.repository.HistoryRepository
import com.smartplug.app.domain.repository.StorageRepository
import com.smartplug.app.ui.screens.storage.ExportState
import com.smartplug.app.ui.screens.storage.StorageError
import com.smartplug.app.ui.screens.storage.StorageViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {
    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var devices: DeviceRepository
    private lateinit var history: HistoryRepository
    private lateinit var storage: StorageRepository
    private lateinit var prefs: AppPreferences
    private lateinit var serverProfiles: ServerProfileStore

    private val server = SmartPlugDevice("SP-1", "mac", "Lampu", null, IntegrationMode.SERVER, "srv", "srvrplug.local")

    private fun point(t: Long, wh: Double) = EnergyHistoryPoint(t, 230.0, 0.1, 12.0, 12.0, 1.0, wh)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        devices = mockk(relaxed = true)
        history = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        serverProfiles = mockk(relaxed = true)
        every { serverProfiles.profiles } returns MutableStateFlow(listOf(RegisteredServer("srv", "Server", "srvrplug.local", 1883, "u", "p")))
        every { devices.observeDevices() } returns flowOf(listOf(server))
        every { prefs.settings } returns flowOf(AppSettings())
        every { prefs.sdObservations } returns flowOf(emptyList())
        coEvery { storage.fetchSdUsage(server) } returns ApiResult.Success(SdUsage(null, null))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = StorageViewModel(devices, history, storage, prefs, serverProfiles)

    @Test fun historyUnavailableIsReportedAndDoesNotCrash() = runTest(dispatcher) {
        coEvery { history.fetchHistory(any(), any(), any(), any()) } returns
            ApiResult.Failure(ApiFailure(503, "history_unavailable"))
        val vm = vm()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertThat(state.error).isEqualTo(StorageError.HistoryUnavailable)
        assertThat(state.global).isNull()
        assertThat(state.sd).isNull() // no capacity field reported -> "– / –"
    }

    @Test fun unreachableServerKeepsLastLoadedData() = runTest(dispatcher) {
        val now = System.currentTimeMillis()
        coEvery { history.fetchHistory(any(), any(), any(), any()) } returns
            ApiResult.Success(listOf(point(now - 2_000, 1000.0), point(now - 1_000, 2000.0)))
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.uiState.value.global!!.totalKwh).isWithin(1e-9).of(1.0)

        coEvery { history.fetchHistory(any(), any(), any(), any()) } returns
            ApiResult.Failure(ApiFailure(0, "network_timeout"))
        coEvery { storage.fetchSdUsage(server) } returns ApiResult.Failure(ApiFailure(0, "network_timeout"))
        vm.refresh()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertThat(state.serverReachable).isFalse()
        assertThat(state.global).isNotNull()
    }

    @Test fun capacityIsReadWhenServerReportsIt() = runTest(dispatcher) {
        coEvery { history.fetchHistory(any(), any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { storage.fetchSdUsage(server) } returns ApiResult.Success(SdUsage(90, 100))
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.uiState.value.sd).isEqualTo(SdUsage(90, 100))
        assertThat(vm.uiState.value.nearlyFull).isTrue() // 90% > 85%
    }

    @Test fun exportOfEmptyPeriodIsEmptyNotReady() = runTest(dispatcher) {
        coEvery { history.fetchHistory(any(), any(), any(), any()) } returns ApiResult.Success(emptyList())
        val vm = vm()
        advanceUntilIdle()
        vm.requestExport(null)
        advanceUntilIdle()
        assertThat(vm.uiState.value.export).isEqualTo(ExportState.Empty)
    }

    @Test fun exportWithDataIsReady() = runTest(dispatcher) {
        val now = System.currentTimeMillis()
        coEvery { history.fetchHistory(any(), any(), any(), any()) } returns
            ApiResult.Success(listOf(point(now - 2_000, 1000.0), point(now - 1_000, 2000.0)))
        val vm = vm()
        advanceUntilIdle()
        vm.requestExport(null)
        advanceUntilIdle()
        val ready = vm.uiState.value.export as ExportState.Ready
        assertThat(ready.fileName).startsWith("smartplug_global_")
        assertThat(ready.csv).startsWith("scope,device_id")
    }

    @Test fun resetDeletesServerHistoryThenLocalCache() = runTest(dispatcher) {
        val now = System.currentTimeMillis()
        coEvery { history.fetchHistory(any(), any(), any(), any()) } returns
            ApiResult.Success(listOf(point(now - 2_000, 1000.0), point(now - 1_000, 2000.0)))
        coEvery { storage.resetServerHistory(server) } returns ApiResult.Success(Unit)
        val vm = vm()
        advanceUntilIdle()
        vm.resetStorage()
        advanceUntilIdle()
        io.mockk.coVerifyOrder {
            storage.resetServerHistory(server)
            history.clearCachedHistory("SP-1")
            prefs.clearSdObservations()
        }
        assertThat(vm.uiState.value.resetError).isNull()
    }

    @Test fun failedServerResetKeepsLocalDataAndReportsWhy() = runTest(dispatcher) {
        val now = System.currentTimeMillis()
        coEvery { history.fetchHistory(any(), any(), any(), any()) } returns
            ApiResult.Success(listOf(point(now - 2_000, 1000.0), point(now - 1_000, 2000.0)))
        coEvery { storage.resetServerHistory(server) } returns ApiResult.Failure(ApiFailure(404, "not_found"))
        val vm = vm()
        advanceUntilIdle()
        vm.resetStorage()
        advanceUntilIdle()
        assertThat(vm.uiState.value.resetError).isEqualTo("server_unsupported")
        assertThat(vm.uiState.value.global).isNotNull()
        io.mockk.coVerify(exactly = 0) { history.clearCachedHistory(any()) }
    }
}
