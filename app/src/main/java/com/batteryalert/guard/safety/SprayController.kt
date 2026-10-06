package com.batteryalert.guard.safety

import javax.inject.Inject

/**
 * The spray pump boundary (FR 5.1).
 *
 * **This interface exists because the requirements do not define one.** The interlock
 * *behaviour* is specified — stop spraying at 20% — but there is no pump protocol, no
 * register map and no vendor document anywhere in the specification. Inventing a wire
 * format and calling it "pump control" would be the single easiest way to make this
 * submission dishonest, so instead the behaviour is implemented and the hardware is left
 * as a seam with a defined shape.
 *
 * The bound implementation is [NoOpSprayController]. Replacing it is a one-line change in
 * `SafetyModule`, and no domain or presentation code has to move — the same arrangement as
 * `TelemetryDataSource`.
 */
interface SprayController {

    /**
     * Ask the spray system to stop or resume.
     *
     * @param inhibited true to stop spraying, false to allow it again
     * @param reason human-readable, for the operator's log rather than for the pump
     */
    fun setInhibited(inhibited: Boolean, reason: String)
}

/**
 * The only implementation bound today. It drives no hardware.
 *
 * It records what it was asked to do, which is not decoration: it is what lets the
 * dashboard state that the interlock is live, and what a test or a future real
 * implementation can assert against.
 */
class NoOpSprayController @Inject constructor() : SprayController {

    @Volatile
    var inhibited: Boolean = false
        private set

    @Volatile
    var reason: String? = null
        private set

    override fun setInhibited(inhibited: Boolean, reason: String) {
        this.inhibited = inhibited
        this.reason = reason
    }
}
