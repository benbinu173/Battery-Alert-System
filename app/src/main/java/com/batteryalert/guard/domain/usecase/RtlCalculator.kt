package com.batteryalert.guard.domain.usecase

/**
 * The dynamic return-to-launch requirement (FR 3.1).
 *
 * [requiredPercent] is the charge that must remain to fly home and still hold the reserve.
 * [tripPercent] is the flight home alone, and [safetyMarginPercent] is the reserve added on
 * top — kept separate so the operator can see which half is driving the number. A
 * requirement of 27% made of a 12% trip and a 15% reserve is a very different situation
 * from one made of a 25% trip and a 2% reserve.
 */
data class RtlAssessment(
    val requiredPercent: Double,
    val tripPercent: Double,
    val safetyMarginPercent: Double,
    val timeToHomeSeconds: Double,
    val percentPerMinute: Double,
) {
    /** Charge above (positive) or below (negative) the requirement. */
    fun marginPercent(remainingPercent: Double?): Double? =
        remainingPercent?.let { it - requiredPercent }

    fun isRequired(remainingPercent: Double?): Boolean =
        remainingPercent != null && remainingPercent < requiredPercent
}

/**
 * Dynamic smart RTL (FR 3.1-3.2).
 *
 * ### The hole in the specified formula
 *
 * FR 3.1 states `Required(%) = (Discharge Rate %/sec x time-to-home) + 15%`. The discharge
 * rate is a *percentage* per second, and nothing in the telemetry reports what percentage
 * is a percentage **of**. Voltage tells us the chemistry and cell count, not the capacity
 * in mAh, so the rate cannot be converted to a percentage without one more input.
 *
 * Rather than assume a pack size — which would silently be wrong on every airframe but one
 * — the capacity is derived from what the source *does* report. `remainingCapacityMah` and
 * `batteryPercentage` are two measurements of the same quantity, so:
 *
 * ```
 * capacity = remainingCapacityMah / (batteryPercentage / 100)
 * %/min    = dischargeRateMahPerMin / capacity x 100
 *          = dischargeRateMahPerMin x batteryPercentage / remainingCapacityMah
 * ```
 *
 * Substituting cancels the capacity entirely, which is why [percentPerMinute] takes no
 * capacity argument.
 *
 * **Its limitation, stated plainly:** the derivation divides by state of charge, so its
 * error is amplified as the pack empties — at 10% every 1-point error in the reported
 * percentage is a 10% error in capacity. That is tolerable for a threshold with a 15%
 * reserve behind it and not tolerable below [MIN_SOC_FOR_RATE] percent, where this returns
 * null and the caller must fall back to a voltage-based rule. A pack with a known capacity
 * should be given one; this is the estimate for when it has not been.
 */
object RtlCalculator {

    /** FR 3.1's 15% safety margin, as a fraction. */
    const val DEFAULT_SAFETY_MARGIN_FRACTION = 0.15

    /**
     * Return speed assumed when the aircraft is not reporting a usable one.
     *
     * Deliberately conservative: a slower assumed return means a longer time to home and
     * therefore a *higher* requirement, so being wrong here errs toward landing early.
     */
    const val DEFAULT_RETURN_SPEED_MPS = 8.0

    /** Below this the aircraft is hovering, and dividing by it yields an infinite trip. */
    private const val MIN_USABLE_RETURN_SPEED_MPS = 0.5

    /**
     * Below this state of charge the capacity derivation is too noisy to threshold on, so
     * the whole assessment is withheld rather than reported with false precision.
     */
    private const val MIN_SOC_FOR_RATE = 5.0

    private const val SECONDS_PER_MINUTE = 60.0

    /**
     * Straight-line time to home.
     *
     * This is a planning figure from distance over speed, not a flight plan: it ignores
     * wind, climb, and the fact that the aircraft may be flying away from home when the
     * number is computed. [returnSpeedMps] should be a cruise speed the airframe can
     * actually hold, not its instantaneous ground speed.
     *
     * @return null when the distance is unknown.
     */
    fun timeToHomeSeconds(
        distanceToHomeMeters: Double?,
        returnSpeedMps: Double?,
    ): Double? {
        val distance = distanceToHomeMeters?.takeIf { it.isFinite() && it >= 0.0 } ?: return null

        val speed = returnSpeedMps
            ?.takeIf { it.isFinite() && it >= MIN_USABLE_RETURN_SPEED_MPS }
            ?: DEFAULT_RETURN_SPEED_MPS

        return distance / speed
    }

    /**
     * Battery consumption expressed as a percentage of pack capacity per minute.
     *
     * @return null when the rate, the remaining capacity or the state of charge is
     *   missing or below [MIN_SOC_FOR_RATE].
     */
    fun percentPerMinute(
        dischargeRateMahPerMin: Double?,
        remainingCapacityMah: Double?,
        batteryPercentage: Double?,
    ): Double? {
        val rate = dischargeRateMahPerMin?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val remaining = remainingCapacityMah?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val percent = batteryPercentage?.takeIf { it.isFinite() && it > MIN_SOC_FOR_RATE } ?: return null

        return rate * percent / remaining
    }

    /**
     * The full FR 3.1 assessment.
     *
     * @return null when any input needed for the estimate is unavailable — which the
     *   caller must treat as "no RTL figure", never as "0% required".
     */
    fun assess(
        distanceToHomeMeters: Double?,
        returnSpeedMps: Double?,
        dischargeRateMahPerMin: Double?,
        remainingCapacityMah: Double?,
        batteryPercentage: Double?,
        safetyMarginFraction: Double = DEFAULT_SAFETY_MARGIN_FRACTION,
    ): RtlAssessment? {
        val timeToHome = timeToHomeSeconds(distanceToHomeMeters, returnSpeedMps) ?: return null
        val rate = percentPerMinute(
            dischargeRateMahPerMin = dischargeRateMahPerMin,
            remainingCapacityMah = remainingCapacityMah,
            batteryPercentage = batteryPercentage,
        ) ?: return null

        val trip = rate * (timeToHome / SECONDS_PER_MINUTE)
        val margin = safetyMarginFraction * 100.0

        return RtlAssessment(
            requiredPercent = trip + margin,
            tripPercent = trip,
            safetyMarginPercent = margin,
            timeToHomeSeconds = timeToHome,
            percentPerMinute = rate,
        )
    }
}
