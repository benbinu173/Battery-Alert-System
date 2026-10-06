package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.BatteryAlert
import java.util.Locale

/**
 * The thresholds from the alert matrix, in one place.
 *
 * They are a data class rather than constants so a test can move one boundary without
 * restating the others, and so it is visible in the type system that these are the only
 * numbers the safety claim depends on.
 */
data class AlertThresholds(
    val noticePercent: Double = 30.0,
    val warningPercent: Double = 20.0,
    val warningVoltsPerCell: Double = 3.65,
    val criticalVoltsPerCell: Double = 3.50,
    val emergencyVoltsPerCell: Double = 3.40,
    val imbalanceVolts: Double = 0.08,
    /**
     * "Rapid sag", which the requirements name but do not quantify.
     *
     * 0.05 V per cell per second empties a 12S pack from 3.65 to 3.40 V/cell in five
     * seconds — far faster than any honest discharge, and characteristic of a pack that
     * is collapsing rather than one that is merely low. It is deliberately a *rate*, so
     * it cannot be confused with the level thresholds above it.
     */
    val declineVoltsPerCellPerSecond: Double = 0.05,
)

/**
 * Everything the alert engine is allowed to look at.
 *
 * A single input object rather than seven parameters, because this signature is the
 * safety surface of the app: it should be possible to read the whole list of things a
 * decision can be based on in one place.
 */
data class AlertInputs(
    val batteryPercentage: Double? = null,
    /**
     * The **weakest cell under load**, not the pack average.
     *
     * The voltage thresholds are load readings, not resting ones: 3.40 V/cell is the point
     * where an ESC browns out, and it browns out because of what the pack delivers *under
     * current*. A resting-voltage rule would stay silent while the pack sagged through
     * cutoff. The weakest cell is the one that reaches that point first, and under load it
     * is also the one that sags hardest, so it is the correct single number to threshold.
     */
    val weakestCellVoltsUnderLoad: Double? = null,
    val cellDeltaVolts: Double? = null,
    val weakestCellNumber: Int? = null,
    val rtl: RtlAssessment? = null,
    val declineVoltsPerCellPerSecond: Double? = null,
)

/**
 * FR 3.2 / the alert matrix: decides *which* alerts are active and how bad the situation is.
 *
 * Two design decisions are worth stating, because neither is in the requirements:
 *
 * **It returns every active condition, not just the worst one.** The requirements give each
 * alert its own indicator, announcement and action prompt, which only makes sense if the
 * conditions are tracked separately. A pack can be both imbalanced and low, and collapsing
 * the pair into one label at the moment it matters most throws away the reason to land.
 * The dashboard takes the first element as the headline; the rest remain available.
 *
 * **It never returns a level without a rule that justifies it.** There is no `else` branch
 * that defaults to a colour. If no rule fires the list is empty and the level is NORMAL,
 * so an alert shown on screen always names the number that caused it.
 */
object AlertEngine {

    /**
     * Evaluates every rule and returns the active ones, worst first, then in the order
     * [AlertRule] declares them.
     */
    fun evaluate(
        inputs: AlertInputs,
        thresholds: AlertThresholds = AlertThresholds(),
    ): List<BatteryAlert> {
        val alerts = mutableListOf<BatteryAlert>()

        // --- Cell fault ---------------------------------------------------------
        // Checked before the charge rules so that an imbalance is never masked by a
        // healthy percentage: the CELL_IMBALANCE scenario sits at 75% charge with a
        // dying cell, which no percentage rule would ever notice.
        val delta = inputs.cellDeltaVolts
        if (delta != null && delta.isFinite() && delta > thresholds.imbalanceVolts) {
            val subject = inputs.weakestCellNumber?.let { "Cell $it is " } ?: "Cell spread is "
            alerts += BatteryAlert(
                rule = AlertRule.CELL_IMBALANCE,
                message = "$subject${millivolts(delta)} mV below the pack.",
                spoken = "$subject${millivolts(delta)} millivolts below the pack.",
                action = "Land and inspect the pack.",
            )
        }

        // --- Charge percentage --------------------------------------------------
        val percentage = inputs.batteryPercentage
        if (percentage != null && percentage.isFinite()) {
            if (percentage <= thresholds.warningPercent) {
                alerts += BatteryAlert(
                    rule = AlertRule.CHARGE_WARNING,
                    message = "Battery at ${percent(percentage)}.",
                    spoken = "Battery at ${spokenPercent(percentage)}.",
                    action = "Return to home now.",
                )
            } else if (percentage <= thresholds.noticePercent) {
                // else-if, not a second if: at 18% both rules are true, and reporting
                // "notice" underneath a warning is noise, not information.
                alerts += BatteryAlert(
                    rule = AlertRule.CHARGE_NOTICE,
                    message = "Battery at ${percent(percentage)}.",
                    spoken = "Battery at ${spokenPercent(percentage)}.",
                    action = "Plan your return to home.",
                )
            }
        }

        // --- Cell voltage, low to critical --------------------------------------
        // Bands are exclusive: a cell at 3.30 V is an emergency, not an emergency plus a
        // critical plus a warning all describing the same reading.
        val cellVolts = inputs.weakestCellVoltsUnderLoad
        if (cellVolts != null && cellVolts.isFinite()) {
            when {
                cellVolts <= thresholds.emergencyVoltsPerCell -> alerts += BatteryAlert(
                    rule = AlertRule.CELL_VOLTAGE_EMERGENCY,
                    message = "Weakest cell at ${volts(cellVolts)} V under load — at cutoff.",
                    spoken = "Weakest cell at ${spokenVolts(cellVolts)} under load. " +
                        "At cutoff.",
                    action = "Land immediately.",
                )

                cellVolts <= thresholds.criticalVoltsPerCell -> alerts += BatteryAlert(
                    rule = AlertRule.CELL_VOLTAGE_CRITICAL,
                    message = "Weakest cell at ${volts(cellVolts)} V under load.",
                    spoken = "Weakest cell at ${spokenVolts(cellVolts)} under load.",
                    action = "Land immediately.",
                )

                cellVolts <= thresholds.warningVoltsPerCell -> alerts += BatteryAlert(
                    rule = AlertRule.CELL_VOLTAGE_WARNING,
                    message = "Weakest cell at ${volts(cellVolts)} V under load.",
                    spoken = "Weakest cell at ${spokenVolts(cellVolts)} under load.",
                    action = "Return to home now.",
                )
            }
        }

        // --- FR 3.2 dynamic RTL -------------------------------------------------
        // isRequired() is false for an unknown percentage, so the null is already
        // handled; the smart cast is repeated here only because the compiler cannot
        // see through the call.
        val rtl = inputs.rtl
        if (rtl != null && percentage != null && rtl.isRequired(percentage)) {
            alerts += BatteryAlert(
                rule = AlertRule.RTL_REQUIRED,
                message = "${percent(percentage)} remaining, " +
                    "${percent(rtl.requiredPercent)} needed to reach home.",
                spoken = "${spokenPercent(percentage)} remaining. " +
                    "${spokenPercent(rtl.requiredPercent)} needed to reach home.",
                action = "Return to home immediately.",
            )
        }

        // --- Rapid sag ----------------------------------------------------------
        val decline = inputs.declineVoltsPerCellPerSecond
        if (decline != null && decline.isFinite() &&
            decline > thresholds.declineVoltsPerCellPerSecond
        ) {
            alerts += BatteryAlert(
                rule = AlertRule.RAPID_DECLINE,
                message = "Pack falling ${volts(decline)} V/cell per second.",
                spoken = "Pack falling ${spokenVolts(decline)} per cell per second.",
                action = "Land immediately — the pack is collapsing under load.",
            )
        }

        return alerts.sortedWith(
            compareByDescending<BatteryAlert> { it.level.severity }.thenBy { it.rule.ordinal },
        )
    }

    /**
     * The single level the UI renders, derived from the alerts rather than recomputed.
     * An empty list is NORMAL — the engine has no path that produces a level without a
     * rule behind it.
     */
    fun levelOf(alerts: List<BatteryAlert>): AlertLevel =
        alerts.firstOrNull()?.level ?: AlertLevel.NORMAL

    // Telemetry is locale-independent: "3.48" must not become "3,48" on a device set to
    // a comma-decimal locale, because the decimal point is part of the safety claim — and
    // because a voice engine reading "3,48" aloud says something else entirely.
    private fun volts(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun percent(value: Double): String = String.format(Locale.US, "%.0f%%", value)

    private fun millivolts(volts: Double): String =
        String.format(Locale.US, "%.0f", volts * 1_000.0)

    // The spoken forms spell the units out. TTS engines vary in how they read "V" and
    // "%", and the operator hearing this is not looking at the screen.
    private fun spokenVolts(value: Double): String =
        String.format(Locale.US, "%.2f volts", value)

    private fun spokenPercent(value: Double): String =
        String.format(Locale.US, "%.0f percent", value)
}
