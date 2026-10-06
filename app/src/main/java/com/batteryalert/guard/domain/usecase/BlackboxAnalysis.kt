package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.BlackboxRecord
import com.batteryalert.guard.domain.model.ConnectionState
import kotlin.math.abs

/**
 * What one flight looked like, reduced from its log.
 *
 * The diagnostic view could simply print the rows, and that is what a raw log is for. But
 * the question a person actually asks a blackbox — *did anything happen, and how bad did it
 * get* — is a summary over the rows, and computing it in a composable would put arithmetic
 * in the layout. It is computed here, where it can be tested, and the screen only renders
 * the answer.
 */
data class BlackboxSessionSummary(
    val sessionId: Long,
    val sampleCount: Int,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    /** The worst level reached anywhere in the session. */
    val worstLevel: AlertLevel,
    /**
     * The rules that fired, in the order they were first seen, without duplicates.
     *
     * Typed, not pre-formatted: how a rule is named on screen is the presentation layer's
     * business, and a summary that carried its own strings could not be re-worded without
     * editing the domain.
     */
    val rulesRaised: List<AlertRule>,
    val lowestPercentage: Double?,
    val lowestPackVolts: Double?,
    val lowestCellVolts: Double?,
    val peakAmps: Double?,
    val peakTemperatureCelsius: Double?,
    val maxDistanceFromHomeMeters: Double?,
    /** True when the link was never in ERROR and never lost its GPS fix. */
    val linkHeldThroughout: Boolean,
) {
    val durationMillis: Long get() = endedAtMillis - startedAtMillis
}

/**
 * Reduces a session's rows to a summary.
 *
 * An empty list has no summary rather than a zeroed one — a flight that was never logged
 * is not a flight that lasted zero seconds at 0%.
 */
object BlackboxAnalysis {

    fun summarise(records: List<BlackboxRecord>): BlackboxSessionSummary? {
        if (records.isEmpty()) return null

        val ordered = records.sortedBy { it.timestampMillis }
        val samples = ordered.map { it.sample }

        return BlackboxSessionSummary(
            sessionId = ordered.first().sessionId,
            sampleCount = ordered.size,
            startedAtMillis = ordered.first().timestampMillis,
            endedAtMillis = ordered.last().timestampMillis,
            worstLevel = samples.maxByOrNull { it.alertLevel.severity }?.alertLevel
                ?: AlertLevel.NORMAL,
            // First-seen order, not worst-first: the diagnostic view reads as a timeline,
            // and "the imbalance appeared, then the pack went critical" is the story.
            rulesRaised = ordered.mapNotNull { it.sample.alertRule }.distinct(),
            lowestPercentage = samples.minOfOrNull { it.batteryPercentage ?: Double.MAX_VALUE }
                ?.takeIf { it != Double.MAX_VALUE },
            lowestPackVolts = samples.minOfOrNull { it.packVolts ?: Double.MAX_VALUE }
                ?.takeIf { it != Double.MAX_VALUE },
            lowestCellVolts = samples.minOfOrNull { it.weakestCellVolts ?: Double.MAX_VALUE }
                ?.takeIf { it != Double.MAX_VALUE },
            // Magnitudes, so the sign convention of current cannot flip which end is
            // "peak" — the log records discharge as positive, but a future source may not.
            peakAmps = samples.mapNotNull { sample -> sample.currentAmps?.let { abs(it) } }
                .maxOrNull(),
            peakTemperatureCelsius = samples.mapNotNull { it.temperatureCelsius }.maxOrNull(),
            maxDistanceFromHomeMeters = samples.mapNotNull { it.distanceToHomeMeters }
                .maxOrNull(),
            linkHeldThroughout = samples.all {
                it.connectionState == ConnectionState.CONNECTED && it.gpsLocked
            },
        )
    }
}
