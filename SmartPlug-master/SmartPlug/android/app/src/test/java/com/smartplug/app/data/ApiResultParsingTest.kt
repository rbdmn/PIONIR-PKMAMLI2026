package com.smartplug.app.data

import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.data.remote.LocalErrorCodes
import com.smartplug.app.data.remote.PairingApi
import com.smartplug.app.data.remote.safeApiCall
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Verifies [safeApiCall]/error-body parsing against both error shapes the app has to tolerate:
 * design.md's target `{"error":{"code":...}}` envelope and the flat `{"error":"<code>"}` shape
 * firmware/LOCAL-API.md documents as already shipped.
 */
class ApiResultParsingTest {

    private lateinit var server: MockWebServer
    private lateinit var pairingApi: PairingApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        pairingApi = retrofit.create(PairingApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success response maps to ApiResult Success`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"api_version":"1.0","product":"smartplug","protocol":"pairing-v1","device_id":"SP-AABBCCDDEEFF","sta_mac":"AA:BB:CC:DD:EE:FF","state":"unprovisioned","pairing_token":"tok","token_expires_in_s":300}"""
            )
        )

        val result = safeApiCall { pairingApi.getPairInfo() }

        assertThat(result).isInstanceOf(ApiResult.Success::class.java)
        val value = (result as ApiResult.Success).value
        assertThat(value.deviceId).isEqualTo("SP-AABBCCDDEEFF")
        assertThat(value.pairingToken).isEqualTo("tok")
    }

    @Test
    fun `wrapped error envelope from design_md is parsed into ApiFailure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"api_version":"1.0","error":{"code":"invalid_pairing_token","message":"expired"}}"""
            )
        )

        val result = safeApiCall { pairingApi.getPairInfo() }

        assertThat(result).isInstanceOf(ApiResult.Failure::class.java)
        val failure = (result as ApiResult.Failure).error
        assertThat(failure.httpCode).isEqualTo(401)
        assertThat(failure.errorCode).isEqualTo("invalid_pairing_token")
        assertThat(failure.message).isEqualTo("expired")
    }

    @Test
    fun `flat error shape from shipped firmware is still parsed correctly`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":"relay_actuation_disabled"}""")
        )

        val result = safeApiCall { pairingApi.getPairInfo() }

        assertThat(result).isInstanceOf(ApiResult.Failure::class.java)
        val failure = (result as ApiResult.Failure).error
        assertThat(failure.errorCode).isEqualTo("relay_actuation_disabled")
    }

    @Test
    fun `empty error body falls back to a local error code instead of crashing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = safeApiCall { pairingApi.getPairInfo() }

        assertThat(result).isInstanceOf(ApiResult.Failure::class.java)
        val failure = (result as ApiResult.Failure).error
        assertThat(failure.errorCode).isEqualTo(LocalErrorCodes.EMPTY_BODY)
    }

    @Test
    fun `malformed json body does not crash and reports unexpected_error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("not json"))

        val result = safeApiCall { pairingApi.getPairInfo() }

        assertThat(result).isInstanceOf(ApiResult.Failure::class.java)
        val failure = (result as ApiResult.Failure).error
        assertThat(failure.errorCode).isEqualTo(LocalErrorCodes.UNEXPECTED)
    }
}
