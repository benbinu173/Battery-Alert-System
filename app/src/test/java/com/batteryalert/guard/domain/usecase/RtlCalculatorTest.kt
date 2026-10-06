package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These use the numbers from the demo scenarios in `MockTelemetryDataSource`, so they
 * double as a check that each scenario still lands in the alert band it is named for.
 * If someone retunes the simulator, these fail rather than the demo quietly going stale.
 */
class RtlCalculatorTest {

    private companion object {
        const val PACK_CAPACITY_MAH = 16_000.0
        const val MILLIAMPS_PER_MINUTE_PER_AMP = 1000.0 / 60.0
    }

    // --- Time to home ------------------------------------------------------------

    @Test
    fun `divides distance by speed`() {
        assertEquals(300.0, RtlCalculator.timeToHomeSeconds(2_400.0, 8.0)!!, 1e-9)
    }

    @Test
    fun `a hovering aircraft falls back to the default return speed`() {
        // Ground speed 0 means hovering, not that the trip home takes no time. Dividing by
        // it would give an infinite trip and a permanent 100% requirement.
        val hovering = RtlCalculator.timeToHomeSeconds(800.0, 0.0)!!
        assertEquals(800.0 / RtlCalculator.DEFAULT_RETURN_SPEED_MPS, hovering, 1e-9)
    }

    @Test
    fun `an unknown speed falls back to the default return speed`() {
        val unknown = RtlCalculator.timeToHomeSeconds(800.0, null)!!
        assertEquals(800.0 / RtlCalculator.DEFAULT_RETURN_SPEED_MPS, unknown, 1e-9)
    }

    @Test
    fun `already home takes no time`() {
        assertEquals(0.0, RtlCalculator.timeToHomeSeconds(0.0, 8.0)!!, 1e-9)
    }

    @Test
    fun `returns null when the distance is unknown or impossible`() {
        assertNull(RtlCalculator.timeToHomeSeconds(null, 8.0))
        assertNull(RtlCalculator.timeToHomeSeconds(-5.0, 8.0))
        assertNull(RtlCalculator.timeToHomeSeconds(Double.NaN, 8.0))
    }

    // --- Rate ---------------------------------------------------------------------

    @Test
    fun `converts a mAh rate into a percentage rate without being given a capacity`() {
        // 400 mAh/min out of a pack with 4160 mAh left at 26% -> 16000 mAh pack -> 2.5%/min.
        val rate = RtlCalculator.percentPerMinute(
            dischargeRateMahPerMin = 400.0,
            remainingCapacityMah = 4_160.0,
            batteryPercentage = 26.0,
        )!!
        assertEquals(2.5, rate, 1e-9)
    }

    @Test
    fun `returns null rather than guessing when an input is missing`() {
        assertNull(RtlCalculator.percentPerMinute(null, 4_160.0, 26.0))
        assertNull(RtlCalculator.percentPerMinute(400.0, null, 26.0))
        assertNull(RtlCalculator.percentPerMinute(400.0, 4_160.0, null))
        assertNull(RtlCalculator.percentPerMinute(0.0, 4_160.0, 26.0))
        assertNull(RtlCalculator.percentPerMinute(-400.0, 4_160.0, 26.0))
        assertNull(RtlCalculator.percentPerMinute(400.0, 0.0, 26.0))
    }

    @Test
    fun `withholds the rate near empty where the capacity estimate breaks down`() {
        // At 4% the derivation divides by 0.04 and every point of SOC error becomes a 25%
        // capacity error. Better to have no number than a confident wrong one.
        assertNull(RtlCalculator.percentPerMinute(400.0, 640.0, 4.0))
        assertNull(RtlCalculator.percentPerMinute(400.0, 640.0, 0.0))
    }

    // --- The full assessment ------------------------------------------------------

    @Test
    fun `the RTL Required scenario actually requires RTL`() {
        // 2.4 km out at 8 m/s, 24 A, 26% remaining.
        val rtl = RtlCalculator.assess(
            distanceToHomeMeters = 2_400.0,
            returnSpeedMps = 8.0,
            dischargeRateMahPerMin = 24.0 * MILLIAMPS_PER_MINUTE_PER_AMP,
            remainingCapacityMah = PACK_CAPACITY_MAH * 0.26,
            batteryPercentage = 26.0,
        )!!

        assertEquals(300.0, rtl.timeToHomeSeconds, 1e-9)
        assertEquals(2.5, rtl.percentPerMinute, 1e-9)
        assertEquals(12.5, rtl.tripPercent, 1e-9)
        assertEquals(15.0, rtl.safetyMarginPercent, 1e-9)
        assertEquals(27.5, rtl.requiredPercent, 1e-9)

        // 26% in hand against 27.5% needed: short by 1.5 points.
        assertEquals(-1.5, rtl.marginPercent(26.0)!!, 1e-9)
        assertTrue(rtl.isRequired(26.0))
    }

    @Test
    fun `the Normal Flight scenario is nowhere near requiring RTL`() {
        val rtl = RtlCalculator.assess(
            distanceToHomeMeters = 40.0,
            returnSpeedMps = 10.0,
            dischargeRateMahPerMin = 18.5 * MILLIAMPS_PER_MINUTE_PER_AMP,
            remainingCapacityMah = PACK_CAPACITY_MAH * 0.98,
            batteryPercentage = 98.0,
        )!!

        assertEquals(4.0, rtl.timeToHomeSeconds, 1e-9)
        assertEquals(15.128, rtl.requiredPercent, 1e-3)
        assertFalse(rtl.isRequired(98.0))
        assertEquals(82.87, rtl.marginPercent(98.0)!!, 0.01)
    }

    @Test
    fun `a longer trip home raises the requirement`() {
        fun requiredAt(distance: Double) = RtlCalculator.assess(
            distanceToHomeMeters = distance,
            returnSpeedMps = 8.0,
            dischargeRateMahPerMin = 400.0,
            remainingCapacityMah = 4_160.0,
            batteryPercentage = 26.0,
        )!!.requiredPercent

        assertTrue(requiredAt(2_400.0) > requiredAt(1_200.0))
        // Doubling the distance doubles the trip and leaves the reserve untouched.
        assertEquals(12.5 * 2, requiredAt(4_800.0) - 15.0, 1e-6)
    }

    @Test
    fun `the reserve is configurable and is reported separately from the trip`() {
        val noReserve = RtlCalculator.assess(
            distanceToHomeMeters = 2_400.0,
            returnSpeedMps = 8.0,
            dischargeRateMahPerMin = 400.0,
            remainingCapacityMah = 4_160.0,
            batteryPercentage = 26.0,
            safetyMarginFraction = 0.0,
        )!!

        assertEquals(0.0, noReserve.safetyMarginPercent, 1e-9)
        assertEquals(noReserve.tripPercent, noReserve.requiredPercent, 1e-9)

        val generousReserve = RtlCalculator.assess(
            distanceToHomeMeters = 2_400.0,
            returnSpeedMps = 8.0,
            dischargeRateMahPerMin = 400.0,
            remainingCapacityMah = 4_160.0,
            batteryPercentage = 26.0,
            safetyMarginFraction = 0.25,
        )!!
        assertEquals(25.0, generousReserve.safetyMarginPercent, 1e-9)
        assertEquals(37.5, generousReserve.requiredPercent, 1e-9)
    }

    @Test
    fun `returns null rather than zero percent when the source cannot support an estimate`() {
        // No GPS fix: no distance to home, so no RTL claim can be made.
        assertNull(
            RtlCalculator.assess(
                distanceToHomeMeters = null,
                returnSpeedMps = 8.0,
                dischargeRateMahPerMin = 400.0,
                remainingCapacityMah = 4_160.0,
                batteryPercentage = 26.0,
            ),
        )

        // Parked: no discharge rate, so the trip home cannot be costed.
        assertNull(
            RtlCalculator.assess(
                distanceToHomeMeters = 2_400.0,
                returnSpeedMps = 8.0,
                dischargeRateMahPerMin = 0.0,
                remainingCapacityMah = 4_160.0,
                batteryPercentage = 26.0,
            ),
        )
    }

    @Test
    fun `margin and requirement are unknown rather than assumed when charge is unknown`() {
        val rtl = RtlCalculator.assess(
            distanceToHomeMeters = 2_400.0,
            returnSpeedMps = 8.0,
            dischargeRateMahPerMin = 400.0,
            remainingCapacityMah = 4_160.0,
            batteryPercentage = 26.0,
        )!!

        assertNull(rtl.marginPercent(null))
        assertFalse(rtl.isRequired(null))
    }
}
