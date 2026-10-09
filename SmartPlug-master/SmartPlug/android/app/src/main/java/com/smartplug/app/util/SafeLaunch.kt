package com.smartplug.app.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Every screen in this app drives real Wi-Fi/HTTP/NSD/DataStore calls whose failure modes aren't
 * fully enumerable (radio errors, malformed responses, OS quirks that vary by device and Android
 * version). A bare `viewModelScope.launch { ... }` lets any unexpected exception inside it escape
 * uncaught, which crashes the whole app. Routing coroutine work through this instead guarantees a
 * caught exception is reported to [onError] (e.g. "show a failed/error state") rather than taking
 * the process down — [CancellationException] is deliberately rethrown so structured concurrency
 * (screen navigation, ViewModel clearing) still works normally.
 */
fun ViewModel.safeLaunch(onError: (Throwable) -> Unit = {}, block: suspend () -> Unit) {
    viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }
}
