package com.smartplug.app.relay

import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.local.SecureTokenStore
import com.smartplug.app.data.remote.ApiClientFactory
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.repository.DeviceRepositoryImpl
import com.smartplug.app.data.repository.RelayRepositoryImpl
import com.smartplug.app.domain.model.IntegrationMode
import com.smartplug.app.domain.model.RelayCommandStatus
import com.smartplug.app.domain.model.RelayState
import com.smartplug.app.domain.model.SmartPlugDevice
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test

class RelayRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: RelayRepositoryImpl
    private lateinit var tokenStore: SecureTokenStore
    private val device = SmartPlugDevice(
        deviceId = "SP-AABBCCDDEEFF",
        staMac = "AA:BB:CC:DD:EE:FF",
        displayName = "Kulkas",
        lanIp = "127.0.0.1",
        integrationMode = IntegrationMode.DIRECT,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        tokenStore = mockk()
        every { tokenStore.ownerToken(device.deviceId) } returns "owner-token"
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val factory = ApiClientFactory(OkHttpClient(), moshi)
        repository = RelayRepositoryImpl(tokenStore, factory)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun deviceOnPort(): SmartPlugDevice = device.copy(lanIp = "localhost:${server.port}")

    @Test
    fun `setRelay parses the queued command from a 202 response`() = runTest {
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"api_version":"1.0","command_id":"cmd-1","state":"on","status":"queued"}"""
            )
        )

        val result = repository.setRelay(deviceOnPort(), targetOn = true)

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val value = (result as ApiResult.Success).value
        assertThat(value.commandId).isEqualTo("cmd-1")
        assertThat(value.status).isEqualTo(RelayCommandStatus.QUEUED)
    }

    @Test
    fun `awaitSettledState for DIRECT mode polls status until relay_state matches the target`() = runTest {
        var pollCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                pollCount++
                val relayState = if (pollCount < 3) "off" else "on"
                return MockResponse().setBody(
                    """{"api_version":"1.0","device_id":"${device.deviceId}","relay_state":"$relayState","has_sample":true,"fresh":true,"sample_age_ms":100}"""
                )
            }
        }
        server.start()

        val result = repository.awaitSettledState(deviceOnPort(), "cmd-1", RelayState.ON)

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val value = (result as ApiResult.Success).value
        assertThat(value.status).isEqualTo(RelayCommandStatus.COMPLETED)
        assertThat(value.state).isEqualTo(RelayState.ON)
        assertThat(pollCount).isAtLeast(3)
    }

    @Test
    fun `awaitSettledState times out if the relay never reports the target state`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                """{"api_version":"1.0","device_id":"${device.deviceId}","relay_state":"off","has_sample":true,"fresh":true,"sample_age_ms":100}"""
            )
        }
        server.start()

        val result = repository.awaitSettledState(deviceOnPort(), "cmd-1", RelayState.ON)

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        assertThat((result as ApiResult.Success).value.status).isEqualTo(RelayCommandStatus.TIMEOUT)
    }

    @Test
    fun `parseRelayState maps wire values and defaults unknown values safely`() {
        assertThat(DeviceRepositoryImpl.parseRelayState("on")).isEqualTo(RelayState.ON)
        assertThat(DeviceRepositoryImpl.parseRelayState("off")).isEqualTo(RelayState.OFF)
        assertThat(DeviceRepositoryImpl.parseRelayState("weird")).isEqualTo(RelayState.UNKNOWN)
    }
}
