package com.batteryalert.guard.domain.usecase

/**
 * Discharge-rate tracking and remaining-flight-time estimation (FR 2.3, Module 10).
 *
 * The estimate is time-to-empty at the *present* load, which is the only thing the
 * telemetry can support. It is not a promise: a spray pump kicking in, a headwind or a
 * climb will invalidate it. Every caller-facing name says "estimated" for that reason.
 */
object FlightTime {

    private const val MILLIAMPS_PER_MINUTE_PER_AMP = 1000.0 / 60.0

    /** Anything above this is not a real drone pack; treated as a bad reading. */
    private const val MAX_PLAUSIBLE_AMPS = 400.0

    /** Guards against a stale near-zero current producing a nonsense multi-day estimate. */
    private const val MIN_USEFUL_AMPS = 0.05

    /**
     * Converts instantaneous current into a discharge rate in mAh/min.
     *
     * @return null for unknown, non-finite, non-positive or implausible current. A
     *   parked aircraft draws ~0 A, and dividing by that would report infinite flight
     *   time; null is the honest answer there.
     */
    fun dischargeRateMahPerMin(currentAmps: Double?): Double? {
        val amps = currentAmps?.takeIf { it.isFinite() } ?: return null
        if (amps < MIN_USEFUL_AMPS || amps > MAX_PLAUSIBLE_AMPS) return null
        return amps * MILLIAMPS_PER_MINUTE_PER_AMP
    }

    /**
     * Estimated minutes of flight remaining at the present discharge rate.
     *
     * @return null when remaining capacity or rate is unknown or non-positive.
     */
    fun estimatedMinutes(
        remainingCapacityMah: Double?,
        dischargeRateMahPerMin: Double?,
    ): Double? {
        val remaining = remainingCapacityMah?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val rate = dischargeRateMahPerMin?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return remaining / rate
    }
}
