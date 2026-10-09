package com.smartplug.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RupiahTest {
    @Test fun formatsThousandsWithDots() {
        assertThat(Rupiah.format(1250.0)).isEqualTo("Rp 1.250")
        assertThat(Rupiah.format(1_234_567.0)).isEqualTo("Rp 1.234.567")
        assertThat(Rupiah.format(999.0)).isEqualTo("Rp 999")
        assertThat(Rupiah.format(1000.0)).isEqualTo("Rp 1.000")
    }

    @Test fun roundsHalfUpToWholeRupiah() {
        assertThat(Rupiah.format(1249.5)).isEqualTo("Rp 1.250")
        assertThat(Rupiah.format(1249.49)).isEqualTo("Rp 1.249")
        assertThat(Rupiah.format(0.5)).isEqualTo("Rp 1")
    }

    @Test fun zeroAndInvalidShowRpZero() {
        assertThat(Rupiah.format(0.0)).isEqualTo("Rp 0")
        assertThat(Rupiah.format(-5.0)).isEqualTo("Rp 0")
        assertThat(Rupiah.format(Double.NaN)).isEqualTo("Rp 0")
        assertThat(Rupiah.format(Double.POSITIVE_INFINITY)).isEqualTo("Rp 0")
    }

    @Test fun verySmallPositiveShowsLessThanOne() {
        assertThat(Rupiah.format(0.0001)).isEqualTo("< Rp 1")
        assertThat(Rupiah.format(0.49)).isEqualTo("< Rp 1")
    }

    @Test fun costIsKwhTimesTariff() {
        assertThat(Rupiah.format(Rupiah.cost(0.087, 1444.7))).isEqualTo("Rp 126")
        assertThat(Rupiah.format(Rupiah.cost(2.0, 625.0))).isEqualTo("Rp 1.250")
    }
}
