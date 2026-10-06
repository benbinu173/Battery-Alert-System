package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltageTrendTest {

    private companion object {
        const val CELLS = 12

        /** A 12S pack falling at a steady [voltsPerCellPerSecond]. */
        fun steadyDecline(
            voltsPerCellPerSecond: Double,
            samples: Int,
            intervalMillis: Long = 1_000L,
        ): Pair<List<Double>, List<Long>> {
            val volts = (0 until samples).map { index ->
                48.0 - voltsPerCellPerSecond * CELLS * (index * intervalMillis / 1_000.0)
            }
            val times = (0 until samples).map { it * intervalMillis }
            return volts to times
        }
    }

    @Test
    fun `recovers the rate from a steady decline`() {
        val (volts, times) = steadyDecline(voltsPerCellPerSecond = 0.01, samples = 10)
        val rate = VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS)!!
        assertEquals(0.01, rate, 1e-9)
    }

    @Test
    fun `a falling pack reports a positive rate`() {
        val (volts, times) = steadyDecline(voltsPerCellPerSecond = 0.02, samples = 8)
        assertTrue(VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS)!! > 0.0)
    }

    @Test
    fun `a recovering pack reports a negative rate`() {
        // Voltage climbing back as load comes off — the opposite failure, and it must not
        // be reported as sag.
        val (volts, times) = steadyDecline(voltsPerCellPerSecond = -0.02, samples = 8)
        assertTrue(VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS)!! < 0.0)
    }

    @Test
    fun `a flat pack reports no decline`() {
        val volts = List(10) { 48.0 }
        val times = List(10) { it * 1_000L }
        assertEquals(0.0, VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS)!!, 1e-9)
    }

    @Test
    fun `uses the timestamps rather than the sample index`() {
        // Unevenly spaced samples: the index would say "linear", the clock says otherwise.
        val times = listOf(0L, 1_000L, 3_000L, 6_000L)
        val volts = times.map { 48.0 - 0.12 * (it / 1_000.0) }
        val rate = VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS)!!
        assertEquals(0.01, rate, 1e-9)
    }

    @Test
    fun `one noisy sample cannot set the rate`() {
        // Nine healthy samples then a single dropout to 44 V. Measuring endpoint to
        // endpoint would read this as 0.037 V/cell/s — a false "pack is collapsing"
        // emergency. Fitting across the window keeps it at 0.018.
        val volts = List(9) { 48.0 } + 44.0
        val times = List(10) { it * 1_000L }

        val rate = VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS)!!
        assertEquals(0.0181818, rate, 1e-6)
        assertTrue("a single dropout must stay below the rapid-sag threshold", rate < 0.05)
    }

    // --- Refusals ------------------------------------------------------------------

    @Test
    fun `refuses when there are too few samples to fit`() {
        val volts = listOf(48.0, 47.9, 47.8)
        val times = listOf(0L, 1_000L, 2_000L)
        assertNull(VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS))
    }

    @Test
    fun `refuses when the window is too short in time`() {
        // Enough samples, but they span under three seconds: a trend needs time.
        val volts = listOf(48.0, 47.99, 47.98, 47.97)
        val times = listOf(0L, 500L, 1_000L, 1_500L)
        assertNull(VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS))
    }

    @Test
    fun `refuses when timestamps and voltages do not line up`() {
        val volts = List(10) { 48.0 }
        val times = List(9) { it * 1_000L }
        assertNull(VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS))
    }

    @Test
    fun `refuses when the cell count is unknown`() {
        val (volts, times) = steadyDecline(voltsPerCellPerSecond = 0.01, samples = 10)
        assertNull(VoltageTrend.declineVoltsPerCellPerSecond(volts, times, 0))
    }

    @Test
    fun `refuses when a sample is not a number`() {
        val volts = List(9) { 48.0 } + Double.NaN
        val times = List(10) { it * 1_000L }
        assertNull(VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS))
    }

    @Test
    fun `refuses a window whose timestamps never advance`() {
        // Enough samples, a plausible-looking decline, and no time has passed at all.
        val volts = List(10) { 48.0 - it * 0.05 }
        val times = List(10) { 0L }
        assertNull(VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS))
    }

    @Test
    fun `refuses an empty window`() {
        assertNull(VoltageTrend.declineVoltsPerCellPerSecond(emptyList(), emptyList(), CELLS))
    }

    @Test
    fun `the fitted rate is what the alert engine compares against`() {
        // A pack genuinely collapsing: 1.2 V/s across 12 cells is 0.1 V/cell/s, twice the
        // documented rapid-sag threshold. This is the value that must trip Emergency.
        val (volts, times) = steadyDecline(voltsPerCellPerSecond = 0.1, samples = 10)
        val rate = VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS)!!
        assertTrue(rate > AlertThresholds().declineVoltsPerCellPerSecond)
    }

    @Test
    fun `a healthy discharge does not trip the rapid-sag threshold`() {
        // The demo's hardest scenario: 28 A on a 16 Ah 12S pack inside the steep part of
        // the curve. Fast, but a discharge, not a collapse.
        val (volts, times) = steadyDecline(voltsPerCellPerSecond = 0.01, samples = 30)
        val rate = VoltageTrend.declineVoltsPerCellPerSecond(volts, times, CELLS)!!
        assertFalse(rate > AlertThresholds().declineVoltsPerCellPerSecond)
    }
}
