package com.smartplug.app.ui.components

/**
 * A daily schedule remains active regardless of this presentation limit.  The
 * countdown is only useful when its action is near enough to need attention.
 */
internal const val SCHEDULE_COUNTDOWN_DISPLAY_LIMIT_MS = 3L * 60L * 60L * 1_000L

internal fun shouldShowScheduleCountdown(remainingMs: Long): Boolean =
    remainingMs in 1L..SCHEDULE_COUNTDOWN_DISPLAY_LIMIT_MS
