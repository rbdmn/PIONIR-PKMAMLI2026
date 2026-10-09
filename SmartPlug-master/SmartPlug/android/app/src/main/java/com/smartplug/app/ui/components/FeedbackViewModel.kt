package com.smartplug.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartplug.app.data.local.AppPreferences
import com.smartplug.app.data.local.AppSettings
import com.smartplug.app.util.SoundManager
import com.smartplug.app.util.rememberHapticController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Shared "did the user just tap something" feedback: optional sound + haptic, both toggleable
 * from Settings (spec: "suara tap opsional... pengguna dapat mematikannya" / "haptic feedback
 * ringan bila tersedia"). Every screen grabs the same Hilt-scoped instance via [hiltViewModel]. */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val soundManager: SoundManager,
    appPreferences: AppPreferences,
) : ViewModel() {
    val settings = appPreferences.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun playTap() {
        soundManager.playTap(settings.value.soundEnabled, settings.value.soundStyle)
    }
}

class TapFeedback(val onTap: () -> Unit, val onConfirm: () -> Unit)

/**
 * Plays the tap sound/haptic for every short tap anywhere inside [content], without consuming the
 * touch. Wrap each window root (the activity content and each bottom sheet), because sheets and
 * menus live in their own window and do not see the activity's touches.
 */
@Composable
fun TapFeedbackHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val feedback = rememberTapFeedback()
    val latest by rememberUpdatedState(feedback)
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                if (up != null &&
                    up.uptimeMillis - down.uptimeMillis < 400 &&
                    (up.position - down.position).getDistance() < viewConfiguration.touchSlop * 2
                ) {
                    latest.onTap()
                }
            }
        },
    ) { content() }
}

@Composable
fun rememberTapFeedback(): TapFeedback {
    val feedbackViewModel: FeedbackViewModel = hiltViewModel()
    val settings by feedbackViewModel.settings.collectAsStateWithLifecycle()
    val haptic = rememberHapticController()
    return remember(feedbackViewModel, settings.hapticEnabled) {
        TapFeedback(
            onTap = {
                feedbackViewModel.playTap()
                haptic.tick(settings.hapticEnabled)
            },
            onConfirm = {
                feedbackViewModel.playTap()
                haptic.confirm(settings.hapticEnabled)
            },
        )
    }
}
