package com.batteryalert.guard.domain.usecase

/**
 * How much charge is left in the pack, in mAh — the input FR 2.3's flight-time estimate and
 * FR 3.1's dynamic RTL both need and neither can measure on their own.
 *
 * ### Why this is a resolver and not a field
 *
 * The pack's *total* capacity is a property of the airframe. No message in this app's MAVLink
 * dialect reports it: `BATTERY_STATUS.current_consumed` is charge used since boot, which is a
 * different quantity entirely and a tempting substitute that would report a freshly-charged
 * pack as nearly empty on the second flight of the day. So the total capacity has to come from
 * configuration, and *remaining* charge follows from it and the state of charge the autopilot
 * does report.
 *
 * A source that does report remaining capacity directly is believed over the arithmetic —
 * same rule, and the same reason, as the assembler preferring the autopilot's own pack voltage
 * to one it sums from cells. A measurement beats a derivation wherever both exist.
 *
 * ### What a null here means
 *
 * The operator has not told the app what pack the aircraft is carrying. FR 2.3's estimate and
 * FR 3.1's requirement are then both withheld rather than computed from an assumed capacity.
 * That is deliberate and it is the whole point of this class existing as a configuration point:
 * a guessed capacity produces a *plausible* flight time, which is far more dangerous than an
 * absent one, because nothing on the dashboard would look wrong.
 */
object PackCapacity {

    /**
     * Below this, whatever was typed is not a drone pack — it is a typo or a stray digit.
     *
     * The smallest packs this app is plausibly pointed at are a few thousand mAh; the floor is
     * set well under that so a genuine small pack is never rejected, while a doubled or
     * truncated entry still is. Note the asymmetry: rejecting a real capacity costs the
     * operator a second entry, and accepting a wrong one costs them the flight-time figure.
     */
    const val MIN_PLAUSIBLE_CAPACITY_MAH = 500.0

    /** Above this it is a stack of packs or a typo. Both are rejected, for the same reason. */
    const val MAX_PLAUSIBLE_CAPACITY_MAH = 60_000.0

    /**
     * Whether a typed capacity is worth keeping.
     *
     * Exposed so the configuration screen can refuse an entry as it is made, rather than
     * accepting it and having the dashboard quietly ignore it later.
     */
    fun isPlausible(capacityMah: Double?): Boolean =
        capacityMah != null && capacityMah.isFinite() && capacityMah in CAPACITY_RANGE

    /**
     * Charge remaining in mAh, or null when the app cannot honestly say.
     *
     * @param reportedRemainingMah a source that measured this directly. Wins when present.
     * @param configuredCapacityMah the airframe's total capacity, as configured.
     * @param batteryPercentage the autopilot's state of charge.
     *
     * Zero is a real answer and is returned as one: a pack at 0% genuinely has nothing left,
     * and substituting null there would hide the single most urgent state the app can be in.
     */
    fun remainingCapacityMah(
        reportedRemainingMah: Double? = null,
        configuredCapacityMah: Double? = null,
        batteryPercentage: Int? = null,
    ): Double? {
        reportedRemainingMah
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { return it }

        val capacity = configuredCapacityMah
            ?.takeIf { isPlausible(it) }
            ?: return null

        // -1 is MAVLink's "no estimate", which the decoders already translate to null. The
        // range check here is for the same reason the decoders have one: a percentage of 127
        // is a misread byte, and multiplying a real capacity by it would produce a number
        // larger than the pack holds.
        val percent = batteryPercentage?.takeIf { it in 0..100 } ?: return null

        return capacity * percent / PERCENT
    }

    private val CAPACITY_RANGE = MIN_PLAUSIBLE_CAPACITY_MAH..MAX_PLAUSIBLE_CAPACITY_MAH

    private const val PERCENT = 100.0
}
