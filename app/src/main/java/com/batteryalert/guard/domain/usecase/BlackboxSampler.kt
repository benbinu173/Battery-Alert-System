package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.BlackboxSample
import com.batteryalert.guard.domain.model.BlackboxTrigger
import com.batteryalert.guard.domain.model.ConnectionState

/**
 * Decides whether a frame is worth writing to the blackbox, and why.
 *
 * A flight recorder that writes every frame is not a recorder, it is a disk-filler: a
 * 10 Hz link over a 40-minute flight is 24,000 rows of a pack that did not change. A
 * recorder that writes on a fixed timer is worse, because the timer is guaranteed to be
 * wrong exactly when it matters — the sample after the one that explains the crash is the
 * one that gets dropped.
 *
 * So the policy is a hybrid, and the priority order is the design:
 *
 * 1. **The first frame of a session** is always recorded. A log that does not begin is not
 *    a log.
 * 2. **Any change in alert level** is recorded immediately, outside the cadence. This is
 *    the row the whole feature exists for.
 * 3. **Any change in the link or the GPS fix** is recorded immediately. "The log stops
 *    here" and "the link dropped here" must not look the same.
 * 4. **While the aircraft is in a bad state**, every frame is recorded. The log goes to
 *    full resolution for the part of the flight that is worth reconstructing.
 * 5. **Otherwise, on the cadence.** The heartbeat that makes the log a record of the
 *    flight rather than a record of its problems.
 *
 * ### Why the cadence is not tied to the frame rate
 *
 * The mock source publishes at 1 Hz and the default cadence is 1 s, so today rules 2–4
 * look redundant. They are not, and they must not be removed once a real link is bound:
 * MAVLink telemetry arrives at 10–50 Hz, and a cadence of one second would then be
 * dropping 9 of every 10 frames at the exact moment the operator needs them. The policy is
 * written for the rate the hardware will deliver, not the rate the simulator happens to
 * deliver — which is the same reason `VoltageTrend` fits against wall-clock time.
 *
 * ### Not thread-safe, deliberately
 *
 * It is driven from one place, on one dispatcher, by the frames of the telemetry flow —
 * the same contract as [com.batteryalert.guard.safety.AnnouncementPolicy], for the same
 * reason.
 */
class BlackboxSampler(
    /** The steady-state logging rate. See the class comment for why this is not the frame rate. */
    val cadenceMillis: Long = DEFAULT_CADENCE_MILLIS,
) {

    private var lastWrittenMillis: Long? = null
    private var lastWrittenLevel: AlertLevel? = null
    private var lastWrittenConnection: ConnectionState? = null
    private var lastWrittenGpsLocked: Boolean? = null

    /**
     * @return why this frame should be recorded, or null to skip it.
     *
     *   Calling this records nothing. The caller is expected to call [accept] once the
     *   write has actually succeeded, so that a failed write does not silently erase a row
     *   from the sequence.
     */
    fun triggerFor(sample: BlackboxSample, nowMillis: Long): BlackboxTrigger? {
        val previousWrite = lastWrittenMillis
        val connection = sample.connectionState

        return when {
            // 1.
            previousWrite == null -> BlackboxTrigger.SESSION_START

            // 2. Compared against the last *written* level, so a change and a change back
            // are both recorded. That is the opposite of the announcement policy's
            // anti-chatter rule, and correct here: a blackbox records what happened, a
            // voice announces what is still true.
            sample.alertLevel != lastWrittenLevel -> BlackboxTrigger.ALERT_CHANGE

            // 3.
            connection != lastWrittenConnection || sample.gpsLocked != lastWrittenGpsLocked ->
                BlackboxTrigger.LINK_CHANGE

            // 4. CELL_FAULT and above. A notice is interesting; a fault is evidence.
            sample.alertLevel.severity >= HIGH_RESOLUTION_FROM_SEVERITY ->
                BlackboxTrigger.HIGH_RESOLUTION

            // 5. Guarded rather than trusted: a clock that steps backwards must not lock
            // the cadence out for the rest of the flight.
            nowMillis - previousWrite >= cadenceMillis || nowMillis < previousWrite ->
                BlackboxTrigger.CADENCE

            else -> null
        }
    }

    /** Records that a frame was written, so the next [triggerFor] has something to compare. */
    fun accept(sample: BlackboxSample, nowMillis: Long) {
        lastWrittenMillis = nowMillis
        lastWrittenLevel = sample.alertLevel
        lastWrittenConnection = sample.connectionState
        lastWrittenGpsLocked = sample.gpsLocked
    }

    companion object {
        /**
         * One row a second.
         *
         * At the row cap in `BlackboxDao` that is a little over five hours of continuous
         * logging, which is several flights' worth of the part of the log that is not at
         * full resolution.
         */
        const val DEFAULT_CADENCE_MILLIS = 1_000L

        /** Full-resolution logging starts at a cell fault and above. */
        private val HIGH_RESOLUTION_FROM_SEVERITY = AlertLevel.CELL_FAULT.severity
    }
}
