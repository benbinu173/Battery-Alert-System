package com.batteryalert.guard.domain.usecase

/**
 * FR 5.1: inhibit spraying below a charge level.
 *
 * This is the *decision*; the physical pump is behind [com.batteryalert.guard.safety.SprayController],
 * because the requirements specify the interlock behaviour without ever defining the
 * control interface for the pump.
 *
 * **Unknown charge inhibits.** Every other calculation in this package treats null as
 * "unknown" and refuses to answer. An interlock cannot do that: refusing to answer means
 * the pump keeps running, and the failure mode of a spray pump running on a nearly-empty
 * flight battery is an aircraft that does not come home. So this is the one place in the
 * app where unknown resolves to the restrictive answer, and it is deliberate rather than
 * an oversight. It is also the conservative direction — the cost of being wrong is a
 * missed spray pass, not a lost airframe.
 */
object SprayInterlock {

    /** FR 5.1's interlock point, as a percentage of pack capacity. */
    const val INHIBIT_AT_OR_BELOW_PERCENT = 20.0

    /**
     * @return true when spraying must be inhibited — including when the charge is unknown.
     */
    fun shouldInhibit(batteryPercentage: Double?): Boolean {
        val percentage = batteryPercentage?.takeIf { it.isFinite() }
            ?: return true
        return percentage <= INHIBIT_AT_OR_BELOW_PERCENT
    }

    /** Why the interlock is engaged, for the operator and for the blackbox log. */
    fun reason(batteryPercentage: Double?): String {
        val percentage = batteryPercentage?.takeIf { it.isFinite() }
            ?: return "Charge unknown"
        return "Charge at ${percentage.toInt()}%"
    }
}
