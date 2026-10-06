package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltageSagTest {

    private val cells12sUnderLoad = List(12) { 3.90 }

    @Test
    fun `adds I x Ri back onto each measured cell`() {
        // 20 A through 0.004 ohm = 0.08 V of sag per cell.
        val result = VoltageSag.compensate(
            measuredCellVolts = cells12sUnderLoad,
            currentAmps = 20.0,
            internalResistanceOhmPerCell = 0.004,
        )

        assertNotNull(result)
        result!!
        assertEquals(0.08, result.sagPerCellVolts, 1e-9)
        assertEquals(3.98, result.restingCellVolts.first(), 1e-9)
        assertEquals(12 * 3.90, result.measuredPackVolts, 1e-9)
        assertEquals(12 * 3.98, result.restingPackVolts, 1e-9)
    }

    @Test
    fun `sag scales linearly with current`() {
        val at10A = VoltageSag.compensate(cells12sUnderLoad, 10.0, 0.004)!!
        val at30A = VoltageSag.compensate(cells12sUnderLoad, 30.0, 0.004)!!

        assertEquals(0.04, at10A.sagPerCellVolts, 1e-9)
        assertEquals(0.12, at30A.sagPerCellVolts, 1e-9)
    }

    @Test
    fun `zero current leaves the measured voltage unchanged`() {
        val result = VoltageSag.compensate(cells12sUnderLoad, currentAmps = 0.0, internalResistanceOhmPerCell = 0.004)!!

        assertEquals(0.0, result.sagPerCellVolts, 1e-9)
        assertEquals(result.measuredPackVolts, result.restingPackVolts, 1e-9)
    }

    @Test
    fun `charging current pushes resting voltage below measured`() {
        // Negative current means the pack is being charged, so it reads high under charge.
        val result = VoltageSag.compensate(cells12sUnderLoad, currentAmps = -10.0, internalResistanceOhmPerCell = 0.004)!!

        assertEquals(-0.04, result.sagPerCellVolts, 1e-9)
        assertTrue(result.restingPackVolts < result.measuredPackVolts)
    }

    @Test
    fun `returns null when current is unknown`() {
        assertNull(VoltageSag.compensate(cells12sUnderLoad, currentAmps = null, internalResistanceOhmPerCell = 0.004))
    }

    @Test
    fun `returns null for non-finite current`() {
        assertNull(VoltageSag.compensate(cells12sUnderLoad, Double.NaN, 0.004))
        assertNull(VoltageSag.compensate(cells12sUnderLoad, Double.POSITIVE_INFINITY, 0.004))
    }

    @Test
    fun `returns null when there are no usable cells`() {
        assertNull(VoltageSag.compensate(emptyList(), currentAmps = 20.0, internalResistanceOhmPerCell = 0.004))
        assertNull(VoltageSag.compensate(listOf(0.0, 0.0), currentAmps = 20.0, internalResistanceOhmPerCell = 0.004))
    }

    @Test
    fun `rejects a negative resistance rather than inventing a correction`() {
        assertNull(VoltageSag.compensate(cells12sUnderLoad, currentAmps = 20.0, internalResistanceOhmPerCell = -0.004))
    }

    @Test
    fun `ignores zeroed cells in a partial packet`() {
        // Some frames report only the populated cells; trailing zeros are padding, not
        // 0 V cells, and must not drag the pack sum down.
        val partial = listOf(3.90, 3.91, 3.89, 0.0, 0.0)
        val result = VoltageSag.compensate(partial, currentAmps = 20.0, internalResistanceOhmPerCell = 0.004)!!

        assertEquals(3, result.restingCellVolts.size)
    }
}
