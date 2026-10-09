package com.smartplug.app.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Thin wrapper so screens don't need to know about [HapticFeedbackType] directly. */
class HapticController(
    private val hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    fun tick(enabled: Boolean) {
        if (enabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun confirm(enabled: Boolean) {
        if (enabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberHapticController(): HapticController {
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback) { HapticController(hapticFeedback) }
}
