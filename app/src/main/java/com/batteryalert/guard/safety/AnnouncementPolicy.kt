package com.batteryalert.guard.safety

import com.batteryalert.guard.domain.model.AlertLevel

/**
 * Decides when an alert is worth saying out loud.
 *
 * FR 5.2 asks for an announcement every 60 seconds, which on its own is under-specified in
 * one direction that matters: if the pack goes from a Notice to an Emergency two seconds
 * after the last announcement, sixty seconds of silence is exactly the wrong response. So
 * the rule here is:
 *
 * - **First alert** — announced.
 * - **Anything worse than what was last announced** — announced immediately. Severity is
 *   monotone in how much trouble the aircraft is in, so a step up is always news.
 * - **Anything else** — announced once the 60-second interval has elapsed.
 * - **Recovery** (the level going back to normal) — announced on the same interval, so the
 *   operator is not left wondering whether the app has died or the problem has gone away.
 *
 * ### Why this does not chatter
 *
 * State is recorded **only when something is actually announced**. That single property is
 * what stops a flickering reading from producing a stream of alerts: if the level wobbles
 * up to a Warning and back down again, the second Warning is compared against the Warning
 * that was announced — not against the momentary drop in between — and stays silent until
 * the interval genuinely elapses. Without it, a noisy cell voltage would talk over itself.
 *
 * The class is deliberately not thread-safe. It is driven from one place, on one
 * dispatcher, by the frames of the telemetry flow.
 */
class AnnouncementPolicy(
    /** FR 5.2. Configurable because the requirement gives one figure for every severity. */
    val repeatIntervalMillis: Long = DEFAULT_REPEAT_INTERVAL_MILLIS,
) {

    private var lastAnnouncedLevel: AlertLevel? = null
    private var lastAnnouncedAtMillis: Long? = null

    /**
     * @return true when this level should be spoken now. Calling it records the
     *   announcement, so the answer for the same level and instant is never true twice.
     */
    fun announceIfDue(level: AlertLevel, nowMillis: Long): Boolean {
        val previous = lastAnnouncedLevel
        val elapsed = lastAnnouncedAtMillis?.let { nowMillis - it }

        val due = when {
            // Nothing has been said yet. Silence is only correct if there is nothing wrong.
            previous == null -> level != AlertLevel.NORMAL

            // Escalation. Immediate, and never rate-limited — this is the case the 60-second
            // figure would otherwise get dangerously wrong.
            level.severity > previous.severity -> true

            // Recovery, and the ordinary repeat. Both wait for the interval, and recovery
            // only speaks if the last thing said was not already "normal".
            level == AlertLevel.NORMAL -> previous != AlertLevel.NORMAL && intervalElapsed(elapsed)

            else -> intervalElapsed(elapsed)
        }

        if (!due) return false

        lastAnnouncedLevel = level
        lastAnnouncedAtMillis = nowMillis
        return true
    }

    private fun intervalElapsed(elapsedMillis: Long?): Boolean =
        elapsedMillis == null || elapsedMillis >= repeatIntervalMillis

    companion object {
        /** FR 5.2's announcement cadence. */
        const val DEFAULT_REPEAT_INTERVAL_MILLIS = 60_000L
    }
}
