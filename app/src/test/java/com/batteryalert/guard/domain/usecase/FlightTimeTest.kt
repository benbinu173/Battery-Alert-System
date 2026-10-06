package com.batteryalert.guard.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightTimeTest {

    @Test
    fun `converts amps to milliamp-hours per minute`() {
        // 18.5 A = 18,500 mA/h = ~308.3 mAh/min
        assertEquals(308.33, FlightTime.dischargeRateMahPerMin(18.5)!!, 0.01)
    }

    @Test
    fun `converts at the expected ratio`() {
        val rate = FlightTime.dischargeRateMahPerMin(60.0)!!
        assertEquals(1000.0, rate, 1e-9)
    }

    @Test
    fun `returns null for a parked aircraft drawing no current`() {
        // Guarding this is the whole point: 0 A would otherwise divide out to infinite
        // remaining flight time.
        assertNull(FlightTime.dischargeRateMahPerMin(0.0))
        assertNull(FlightTime.dischargeRateMahPerMin(0.01))
    }

    @Test
    fun `returns null for negative, unknown or implausible current`() {
        assertNull(FlightTime.dischargeRateMahPerMin(null))
        assertNull(FlightTime.dischargeRateMahPerMin(-5.0))
        assertNull(FlightTime.dischargeRateMahPerMin(Double.NaN))
        assertNull(FlightTime.dischargeRateMahPerMin(12_000.0))
    }

    @Test
    fun `estimates remaining minutes from capacity and rate`() {
        // 16,000 mAh at ~308.3 mAh/min is a little under 52 minutes.
        val minutes = FlightTime.estimatedMinutes(
            remainingCapacityMah = 16_000.0,
            dischargeRateMahPerMin = 308.33,
        )!!
        assertEquals(51.89, minutes, 0.05)
    }

    @Test
    fun `estimate halves when the load doubles`() {
        val light = FlightTime.estimatedMinutes(8_000.0, 200.0)!!
        val heavy = FlightTime.estimatedMinutes(8_000.0, 400.0)!!

        assertEquals(40.0, light, 1e-9)
        assertEquals(20.0, heavy, 1e-9)
    }

    @Test
    fun `returns null instead of dividing by zero or by unknown inputs`() {
        assertNull(FlightTime.estimatedMinutes(null, 300.0))
        assertNull(FlightTime.estimatedMinutes(16_000.0, null))
        assertNull(FlightTime.estimatedMinutes(16_000.0, 0.0))
        assertNull(FlightTime.estimatedMinutes(0.0, 300.0))
        assertNull(FlightTime.estimatedMinutes(-100.0, 300.0))
        assertNull(FlightTime.estimatedMinutes(Double.NaN, 300.0))
    }
}
