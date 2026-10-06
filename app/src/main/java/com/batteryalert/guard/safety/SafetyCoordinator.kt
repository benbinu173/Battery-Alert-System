package com.batteryalert.guard.safety

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.BatteryAlert
import com.batteryalert.guard.domain.usecase.SprayInterlock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the alert engine's decisions into things that happen: a voice, a vibration, and a
 * spray interlock.
 *
 * It exists as its own type rather than as code inside the ViewModel for one reason: the
 * ViewModel's job is to produce state, and everything here is a *side effect*. Keeping them
 * apart is what lets the dashboard be recomposed, rotated or recreated without the app
 * repeating an announcement, and it is what would let this be moved to a foreground service
 * later without touching the alert engine at all.
 *
 * [onFrame] is expected to be called once per telemetry frame, in order, from one place.
 * The state it keeps is not synchronised, deliberately — see [AnnouncementPolicy].
 */
@Singleton
class SafetyCoordinator @Inject constructor(
    private val announcer: AlertAnnouncer,
    private val haptics: HapticChannel,
    private val spray: SprayController,
) {

    private val policy = AnnouncementPolicy()

    /** Last value sent to the pump, so the same command is not re-sent every second. */
    private var sprayInhibited: Boolean? = null

    /**
     * @param level the headline alert level, or [AlertLevel.NORMAL] when nothing is wrong
     * @param primary the alert driving [level], used for the spoken wording
     * @param batteryPercentage the charge the spray interlock is evaluated against
     * @param nowMillis wall clock; passed in rather than read here so the timing behaviour
     *   can be tested without waiting a minute
     */
    fun onFrame(
        level: AlertLevel,
        primary: BatteryAlert?,
        batteryPercentage: Double?,
        nowMillis: Long,
    ) {
        // The interlock applies to every frame. Whether to *speak* is rate-limited; whether
        // to protect the pack is not.
        applySprayInterlock(batteryPercentage)

        if (!policy.announceIfDue(level, nowMillis)) return

        announcer.announce(spokenText(level, primary))
        haptics.pulse(level)
    }

    /** Silences the voice and cancels any pattern in flight. */
    fun stop() {
        announcer.stop()
        haptics.cancel()
    }

    private fun applySprayInterlock(batteryPercentage: Double?) {
        val inhibited = SprayInterlock.shouldInhibit(batteryPercentage)

        // Only on change. An interlock that re-sends an identical command every second is
        // an interlock that will eventually be misread by whatever is on the other end.
        if (inhibited == sprayInhibited) return
        sprayInhibited = inhibited

        spray.setInhibited(
            inhibited = inhibited,
            reason = SprayInterlock.reason(batteryPercentage),
        )
    }

    private fun spokenText(level: AlertLevel, primary: BatteryAlert?): String = when {
        primary != null -> primary.spoken
        level == AlertLevel.NORMAL -> RECOVERED
        // Unreachable through the alert engine, which never raises a level without a rule.
        // Present so that a future rule added without wording is audible-shaped nonsense
        // rather than a crash or, worse, silence.
        else -> UNSPECIFIED_ALERT
    }

    private companion object {
        const val RECOVERED = "Battery recovered."
        const val UNSPECIFIED_ALERT = "Battery alert. Check the dashboard."
    }
}
