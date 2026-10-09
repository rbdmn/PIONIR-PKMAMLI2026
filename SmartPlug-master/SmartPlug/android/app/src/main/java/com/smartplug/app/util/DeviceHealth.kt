package com.smartplug.app.util

/** Load warning shown to the user when a SmartPlug carries more than its relay is rated for. */
object Overcurrent {
    /** Rating of the relay (Omron G3MB-202P): 2 A. Warning only; the app never switches anything off. */
    const val WARN_AMPERES = 2.0

    fun isWarning(currentA: Double, firmwareFlag: Boolean? = null): Boolean =
        firmwareFlag == true || (currentA.isFinite() && currentA > WARN_AMPERES)
}

/** "2h 14m 05s"; shorter forms for short uptimes; "3d 2h 14m" once a day has passed. */
fun formatUptime(seconds: Long?): String {
    if (seconds == null || seconds < 0L) return "–"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    val secs = seconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h ${minutes.toString().padStart(2, '0')}m"
        hours > 0 -> "${hours}h ${minutes}m ${secs.toString().padStart(2, '0')}s"
        minutes > 0 -> "${minutes}m ${secs.toString().padStart(2, '0')}s"
        else -> "${secs}s"
    }
}
