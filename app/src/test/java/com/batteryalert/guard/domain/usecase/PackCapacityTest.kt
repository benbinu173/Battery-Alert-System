package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one input FR 2.3 and FR 3.1 both need and neither source reports.
 *
 * The tests below are mostly about what this refuses to answer. Every one of them is a case
 * where a plausible-looking number could be produced and would be wrong, and where being wrong
 * costs a flight-time estimate the operator has no way to check.
 */
class PackCapacityTest {

    // --- Deriving from a configured pack ---------------------------------------------------

    @Test
    fun `a configured pack gives up the charge that matches its state of charge`() {
        // The headline case: 16 Ah at 62% is 9 920 mAh. A 6S 16 Ah pack on a sprayer, which is
        // exactly the airframe this configuration point exists for.
        val remaining = PackCapacity.remainingCapacityMah(
            configuredCapacityMah = 16_000.0,
            batteryPercentage = 62,
        )

        assertEquals(9_920.0, remaining!!, 0.0001)
    }

    @Test
    fun `a full pack holds exactly its rated capacity`() {
        val remaining = PackCapacity.remainingCapacityMah(
            configuredCapacityMah = 22_000.0,
            batteryPercentage = 100,
        )

        assertEquals(22_000.0, remaining!!, 0.0001)
    }

    /**
     * An empty pack is a reading, not a missing one.
     *
     * Null here would be the safe-looking choice and the wrong one: FR 3.1's comparison is
     * against a threshold, and a null drops out of that comparison entirely, so the single most
     * urgent state the app can be in would be the one it has nothing to say about.
     */
    @Test
    fun `an empty pack is zero milliamp hours rather than an unknown`() {
        val remaining = PackCapacity.remainingCapacityMah(
            configuredCapacityMah = 16_000.0,
            batteryPercentage = 0,
        )

        assertEquals(0.0, remaining!!, 0.0)
    }

    // --- What wins, and what is refused ----------------------------------------------------

    @Test
    fun `a source that measures the charge left is believed over the arithmetic`() {
        // Same rule the assembler applies to the pack voltage: a measurement beats a
        // derivation wherever both exist. Here the source says 4 000 mAh and the configured
        // 16 Ah pack at 50% would say 8 000 — the source is the one that was there.
        val remaining = PackCapacity.remainingCapacityMah(
            reportedRemainingMah = 4_000.0,
            configuredCapacityMah = 16_000.0,
            batteryPercentage = 50,
        )

        assertEquals(4_000.0, remaining!!, 0.0001)
    }

    @Test
    fun `a reported zero is a measurement and still wins`() {
        val remaining = PackCapacity.remainingCapacityMah(
            reportedRemainingMah = 0.0,
            configuredCapacityMah = 16_000.0,
            batteryPercentage = 50,
        )

        assertEquals(0.0, remaining!!, 0.0)
    }

    @Test
    fun `nothing is reported when no capacity has been configured`() {
        // The whole point of this being a configuration point. Assuming a pack size would
        // produce a flight time that looks entirely reasonable and belongs to a different
        // aircraft.
        assertNull(
            PackCapacity.remainingCapacityMah(
                configuredCapacityMah = null,
                batteryPercentage = 62,
            ),
        )
    }

    @Test
    fun `nothing is reported when the percentage is missing`() {
        assertNull(
            PackCapacity.remainingCapacityMah(
                configuredCapacityMah = 16_000.0,
                batteryPercentage = null,
            ),
        )
    }

    @Test
    fun `a percentage outside zero to a hundred is a misread and is refused`() {
        // MAVLink's own "no estimate" sentinel, and a byte that decoded to 127. Both would
        // multiply a real capacity into a number larger than the pack holds.
        assertNull(
            PackCapacity.remainingCapacityMah(
                configuredCapacityMah = 16_000.0,
                batteryPercentage = -1,
            ),
        )
        assertNull(
            PackCapacity.remainingCapacityMah(
                configuredCapacityMah = 16_000.0,
                batteryPercentage = 127,
            ),
        )
    }

    // --- What counts as a real pack --------------------------------------------------------

    @Test
    fun `a capacity that is not a drone pack is refused at both ends`() {
        // A truncated entry and a doubled one, which produce opposite nonsense: a flight time
        // of seconds, or one of several hours, from a pack that is not in the aircraft.
        assertNull(PackCapacity.remainingCapacityMah(configuredCapacityMah = 100.0, batteryPercentage = 50))
        assertNull(
            PackCapacity.remainingCapacityMah(
                configuredCapacityMah = 160_000.0,
                batteryPercentage = 50,
            ),
        )
    }

    @Test
    fun `the plausible range has endpoints that are themselves plausible`() {
        // Inclusive, so the boundary values are accepted rather than off by one. A floor that
        // excluded its own bound would be a rule nobody could satisfy at the edge.
        assertTrue(PackCapacity.isPlausible(PackCapacity.MIN_PLAUSIBLE_CAPACITY_MAH))
        assertTrue(PackCapacity.isPlausible(PackCapacity.MAX_PLAUSIBLE_CAPACITY_MAH))
        assertFalse(PackCapacity.isPlausible(PackCapacity.MIN_PLAUSIBLE_CAPACITY_MAH - 1.0))
        assertFalse(PackCapacity.isPlausible(PackCapacity.MAX_PLAUSIBLE_CAPACITY_MAH + 1.0))
    }

    @Test
    fun `a missing or non finite capacity is not plausible`() {
        assertFalse(PackCapacity.isPlausible(null))
        assertFalse(PackCapacity.isPlausible(Double.NaN))
        assertFalse(PackCapacity.isPlausible(Double.POSITIVE_INFINITY))
    }

    // --- The chain it exists to close ------------------------------------------------------

    /**
     * The reason any of this matters: with a capacity configured, the discharge rate stops
     * depending on the state of charge.
     *
     * `RtlCalculator.percentPerMinute` derives capacity from `remaining / (percentage / 100)`,
     * and the substitution cancels — but only when the remaining figure came from a real pack.
     * With the configured capacity in place, 2 000 mAh/min out of 16 000 mAh is 12.5%/min at
     * *any* state of charge, which is what FR 3.1's formula needs in order to be a prediction
     * rather than a restatement of the current reading.
     */
    @Test
    fun `a configured capacity makes the rtl rate independent of the state of charge`() {
        val rate = 2_000.0
        val capacity = 16_000.0

        val atFull = RtlCalculator.percentPerMinute(
            dischargeRateMahPerMin = rate,
            remainingCapacityMah = PackCapacity.remainingCapacityMah(
                configuredCapacityMah = capacity,
                batteryPercentage = 95,
            ),
            batteryPercentage = 95.0,
        )
        val atHalf = RtlCalculator.percentPerMinute(
            dischargeRateMahPerMin = rate,
            remainingCapacityMah = PackCapacity.remainingCapacityMah(
                configuredCapacityMah = capacity,
                batteryPercentage = 50,
            ),
            batteryPercentage = 50.0,
        )

        assertEquals(12.5, atFull!!, 0.0001)
        assertEquals(12.5, atHalf!!, 0.0001)
    }

    /**
     * And what the same chain does without one.
     *
     * Kept as a test because it is the failure this class prevents: the derivation still works
     * arithmetically whenever a source happens to report remaining capacity, which is why the
     * gap was easy to miss — every pure-function test of `RtlCalculator` passes either way.
     */
    @Test
    fun `without a configured capacity the rtl rate has no inputs at all`() {
        assertNull(
            RtlCalculator.percentPerMinute(
                dischargeRateMahPerMin = 2_000.0,
                remainingCapacityMah = PackCapacity.remainingCapacityMah(
                    configuredCapacityMah = null,
                    batteryPercentage = 50,
                ),
                batteryPercentage = 50.0,
            ),
        )
    }
}
