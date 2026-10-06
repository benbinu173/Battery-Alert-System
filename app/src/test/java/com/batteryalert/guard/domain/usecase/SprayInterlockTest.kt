package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR 5.1's interlock point. The interesting cases are the boundaries and the unknown —
 * 20% is inclusive, and an unreadable charge inhibits rather than permits.
 */
class SprayInterlockTest {

    @Test
    fun `spraying is permitted comfortably above the interlock`() {
        assertFalse(SprayInterlock.shouldInhibit(80.0))
        assertFalse(SprayInterlock.shouldInhibit(20.1))
    }

    @Test
    fun `spraying is inhibited at the interlock point`() {
        assertTrue(SprayInterlock.shouldInhibit(20.0))
    }

    @Test
    fun `spraying is inhibited below the interlock point`() {
        assertTrue(SprayInterlock.shouldInhibit(19.9))
        assertTrue(SprayInterlock.shouldInhibit(0.0))
    }

    /** Unknown charge is the one place in the domain where null does not mean "no answer". */
    @Test
    fun `an unknown charge inhibits`() {
        assertTrue(SprayInterlock.shouldInhibit(null))
    }

    @Test
    fun `a non-finite charge inhibits`() {
        assertTrue(SprayInterlock.shouldInhibit(Double.NaN))
        assertTrue(SprayInterlock.shouldInhibit(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `the reason names the charge that engaged the interlock`() {
        assertEquals("Charge at 12%", SprayInterlock.reason(12.4))
        assertEquals("Charge at 20%", SprayInterlock.reason(20.0))
    }

    @Test
    fun `the reason says so when the charge is unknown`() {
        assertEquals("Charge unknown", SprayInterlock.reason(null))
        assertEquals("Charge unknown", SprayInterlock.reason(Double.NaN))
    }
}
