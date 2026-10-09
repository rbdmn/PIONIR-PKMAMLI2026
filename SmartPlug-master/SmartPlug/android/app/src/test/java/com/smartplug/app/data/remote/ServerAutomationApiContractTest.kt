package com.smartplug.app.data.remote

import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.remote.dto.ServerScheduleRequestDto
import com.smartplug.app.data.remote.dto.ServerTimerRequestDto
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Reproduces the Server-mode automation call without contacting a real SmartPlug or server. */
class ServerAutomationApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().build()))
            .build()
            .create(ServerApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `server timer request is bindable and serializes its duration`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val response = api.setTimer(
            bearerToken = "Bearer test",
            deviceId = "SP-TEST",
            values = ServerTimerRequestDto(action = "apply", days = 0, hours = 0, minutes = 0, seconds = 5),
        )

        assertThat(response.isSuccessful).isTrue()
        val request = checkNotNull(server.takeRequest())
        assertThat(request.path).isEqualTo("/api/v1/devices/SP-TEST/timer")
        assertThat(request.body.readUtf8()).isEqualTo("{\"action\":\"apply\",\"days\":0,\"hours\":0,\"minutes\":0,\"seconds\":5}")
    }

    @Test
    fun `server schedule request is bindable and serializes daily action`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"enabled":true,"clock":{"synchronized":true,"utc_ms":0,"timezone_offset_minutes":420},"next":{"remaining_seconds":0,"state":""},"entries":[]}
        """.trimIndent()))

        val response = api.setSchedule(
            bearerToken = "Bearer test",
            deviceId = "SP-TEST",
            values = ServerScheduleRequestDto(
                action = "add", timezoneOffsetMinutes = 420, hour = 13, minute = 35, state = "on", event = "Test",
            ),
        )

        assertThat(response.isSuccessful).isTrue()
        val request = checkNotNull(server.takeRequest())
        assertThat(request.path).isEqualTo("/api/v1/devices/SP-TEST/schedule")
        assertThat(request.body.readUtf8()).isEqualTo("{\"action\":\"add\",\"timezone_offset_minutes\":420,\"hour\":13,\"minute\":35,\"state\":\"on\",\"event\":\"Test\"}")
    }
}
