package com.smartplug.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs [onTick] immediately and then every [intervalMs] while the host screen is at least
 * STARTED, i.e. visible and the app foregrounded — [Lifecycle.repeatOnLifecycle] cancels the
 * block the moment the app backgrounds or the screen leaves composition, and restarts it when
 * it comes back. This is how every polling screen honors design.md's "Aplikasi di latar
 * belakang: Tidak melakukan polling" without each screen re-implementing lifecycle plumbing.
 *
 * [onTick] runs directly inside this composable's own `LaunchedEffect` coroutine — it does NOT go
 * through any ViewModel-level `viewModelScope`/`safeLaunch` protection a caller might have, since
 * it's invoked from the UI side, not launched by the ViewModel itself. A single unhandled
 * exception here (a `ViewModel.refresh()` throwing instead of returning a result type, an Android
 * API quirk, etc.) would otherwise crash the whole app on every poll tick forever. Catching it
 * here protects every current and future caller in one place instead of requiring each screen's
 * polled function to defend itself individually.
 */
@Composable
fun PollWhileVisible(
    intervalMs: Long,
    key: Any? = Unit,
    fixedRate: Boolean = false,
    onTick: suspend () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(key, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val startedAtMs = System.currentTimeMillis()
                try {
                    onTick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Swallow and retry next tick rather than crash; the polled screen's own
                    // state (lastError, etc.) is whatever it was left at from the failed attempt.
                }
                // Normal/direct polling deliberately waits a full interval after a response,
                // preserving the established low-traffic behavior. Server monitoring uses a
                // fixed-rate cadence instead: a 420 ms request followed by an 80 ms delay still
                // begins the next snapshot read around the requested 500 ms mark. A slow request
                // never overlaps with another one; the next attempt starts immediately instead.
                val waitMs = if (fixedRate) {
                    (intervalMs - (System.currentTimeMillis() - startedAtMs)).coerceAtLeast(0L)
                } else {
                    intervalMs
                }
                delay(waitMs)
            }
        }
    }
}
