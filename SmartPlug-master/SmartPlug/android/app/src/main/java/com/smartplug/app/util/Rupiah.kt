package com.smartplug.app.util

import kotlin.math.floor

/** Indonesian Rupiah formatting for the energy cost estimate (no decimals, "." thousands). */
object Rupiah {
    fun cost(kwh: Double, tariffPerKwh: Double): Double = kwh * tariffPerKwh

    /** "Rp 1.250"; a positive amount that rounds to zero shows "< Rp 1", never blank. */
    fun format(amount: Double): String {
        if (!amount.isFinite() || amount <= 0.0) return "Rp 0"
        val rounded = floor(amount + 0.5).toLong()
        if (rounded == 0L) return "< Rp 1"
        return "Rp " + rounded.toString().reversed().chunked(3).joinToString(".").reversed()
    }
}
