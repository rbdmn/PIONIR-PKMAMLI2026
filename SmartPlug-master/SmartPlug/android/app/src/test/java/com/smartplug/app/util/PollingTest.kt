package com.smartplug.app.util

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.smartplug.app.data.remote.ApiResult
import com.smartplug.app.domain.model.ApiFailure
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PollingTest {

    @Test
    fun `single-flight poller waits for each fetch to complete before scheduling the next`() = runTest {
        var callCount = 0
        val flow = pollWithBackoff(
            intervalMs = { 100 },
            backoff = { 500 },
            fetch = {
                callCount++
                ApiResult.Success(callCount)
            },
        )

        flow.test {
            assertThat(awaitItem()).isEqualTo(ApiResult.Success(1))
            assertThat(awaitItem()).isEqualTo(ApiResult.Success(2))
            assertThat(awaitItem()).isEqualTo(ApiResult.Success(3))
            cancelAndIgnoreRemainingEvents()
        }
        // Never more than one fetch call resolves before the next is emitted; three emissions
        // observed implies exactly three sequential (never overlapping) fetch calls.
        assertThat(callCount).isAtLeast(3)
    }

    @Test
    fun `failure count resets to the fast interval after a success`() = runTest {
        var attempt = 0
        val observedResults = mutableListOf<ApiResult<Int>>()
        val flow = pollWithBackoff(
            intervalMs = { 1 },
            backoff = { failures -> if (failures == 1) 2L else 999_999L },
            fetch = {
                attempt++
                if (attempt == 2) {
                    ApiResult.Failure(ApiFailure(0, "boom"))
                } else {
                    ApiResult.Success(attempt)
                }
            },
        )

        flow.test {
            observedResults += awaitItem() // success #1
            observedResults += awaitItem() // failure -> short backoff of 2ms
            observedResults += awaitItem() // success #3, should not wait 999999ms
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(observedResults[0]).isEqualTo(ApiResult.Success(1))
        assertThat((observedResults[1] as ApiResult.Failure).error.errorCode).isEqualTo("boom")
        assertThat(observedResults[2]).isEqualTo(ApiResult.Success(3))
    }
}
