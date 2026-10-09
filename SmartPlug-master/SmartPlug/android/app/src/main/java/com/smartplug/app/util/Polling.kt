package com.smartplug.app.util

import com.smartplug.app.data.remote.ApiResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Sequential single-flight polling: the next [fetch] is only scheduled after the previous one
 * returns, so there is never more than one request in flight per call site (design.md: "Setiap
 * SmartPlug hanya boleh memiliki satu request pengukuran yang sedang berjalan").
 *
 * Callers collect this from a Compose `LaunchedEffect`/`repeatOnLifecycle(STARTED)` scope so
 * backgrounding the screen or the app cancels the flow and polling simply stops, per design.md
 * "Aplikasi di latar belakang: Tidak melakukan polling pengukuran".
 *
 * On success the backoff counter resets and the next delay is [intervalMs]. On failure, the delay
 * follows [backoff] instead, and the failure count grows until a success resets it.
 */
fun <T> pollWithBackoff(
    intervalMs: () -> Long,
    backoff: (consecutiveFailures: Int) -> Long,
    fetch: suspend () -> ApiResult<T>,
): Flow<ApiResult<T>> = flow {
    var consecutiveFailures = 0
    while (true) {
        val result = fetch()
        emit(result)
        val delayMs = when (result) {
            is ApiResult.Success -> {
                consecutiveFailures = 0
                intervalMs()
            }
            is ApiResult.Failure -> {
                consecutiveFailures += 1
                backoff(consecutiveFailures)
            }
        }
        delay(delayMs)
    }
}
