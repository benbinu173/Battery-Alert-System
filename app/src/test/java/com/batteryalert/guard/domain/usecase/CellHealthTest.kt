package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellHealthTest {

    @Test
    fun `computes min max average and delta across a pack`() {
        val cells = listOf(4.05, 4.06, 4.04, 4.06, 4.05, 3.94)

        val stats = CellHealth.stats(cells)!!

        assertEquals(6, stats.count)
        assertEquals(3.94, stats.minVolts, 1e-9)
        assertEquals(4.06, stats.maxVolts, 1e-9)
        assertEquals(0.12, stats.deltaVolts, 1e-9)
        assertEquals(4.0333, stats.averageVolts, 1e-4)
    }

    @Test
    fun `delta is zero for a perfectly balanced pack`() {
        val stats = CellHealth.stats(List(12) { 3.85 })!!
        assertEquals(0.0, stats.deltaVolts, 1e-9)
    }

    @Test
    fun `delta crosses the fault threshold on a real imbalance`() {
        // One cell 0.12 V below its neighbours: past the 0.08 V fault threshold.
        val cells = List(12) { 3.85 }.toMutableList().also { it[5] = 3.73 }

        val delta = CellHealth.deltaVolts(cells)!!
        assertTrue("expected delta above the 0.08 V fault threshold", delta > 0.08)
    }

    @Test
    fun `healthy spread stays under the fault threshold`() {
        val cells = List(12) { 3.85 }.toMutableList().also { it[5] = 3.84 }

        val delta = CellHealth.deltaVolts(cells)!!
        assertTrue("expected delta under the 0.08 V fault threshold", delta < 0.08)
    }

    @Test
    fun `returns null when no cells are reported`() {
        assertNull(CellHealth.stats(emptyList()))
        assertNull(CellHealth.deltaVolts(emptyList()))
    }

    @Test
    fun `ignores zeroed padding cells from a partial packet`() {
        // A frame carrying 4 populated cells and 8 zeros must report 4 cells, not a
        // 3.9 V delta against a dead zero.
        val partial = listOf(3.90, 3.91, 3.89, 3.90) + List(8) { 0.0 }

        val stats = CellHealth.stats(partial)!!
        assertEquals(4, stats.count)
        assertTrue(stats.deltaVolts < 0.05)
    }

    @Test
    fun `ignores non-finite readings`() {
        val withJunk = listOf(3.90, Double.NaN, 3.91, Double.POSITIVE_INFINITY, 3.89)

        val stats = CellHealth.stats(withJunk)!!
        assertEquals(3, stats.count)
        assertEquals(3.89, stats.minVolts, 1e-9)
    }
}
