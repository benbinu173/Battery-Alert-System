package com.batteryalert.guard.presentation.dashboard

import com.batteryalert.guard.data.link.LinkMode
import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.BatteryAlert
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.usecase.CellHealth
import com.batteryalert.guard.domain.usecase.CellStats
import com.batteryalert.guard.domain.usecase.ResolvedBatteryConfiguration
import com.batteryalert.guard.domain.usecase.RtlAssessment

/**
 * One immutable snapshot of everything the dashboard renders.
 *
 * Compose never computes a safety number. Every value here was produced by the domain
 * layer; the UI only formats and lays it out. Fields that later phases populate are
 * already present (and null/defaulted) so the dashboard layout does not have to be
 * rewritten as each engine lands.
 */
data class DashboardUiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val demoMode: Boolean = false,

    /**
     * Which source the numbers on this screen came from.
     *
     * Carried so the footer can say something true in every mode. It used to say "simulated
     * telemetry, no aircraft is being monitored" as a constant, which was honest while the
     * simulator was the only source and would be a lie the moment the app is reading a real
     * one — and a safety display that misstates where its numbers came from is worse than one
     * that says nothing.
     */
    val linkMode: LinkMode = LinkMode.DEMO,

    // Battery
    val batteryPercentage: Int? = null,
    val totalVoltage: Double? = null,
    val current: Double? = null,
    val temperature: Double? = null,
    val remainingCapacityMah: Double? = null,
    /**
     * The airframe's configured pack size, or null when the operator has not set one.
     *
     * Kept beside [remainingCapacityMah] because the two are only meaningful together: a
     * remaining figure means something different on a 10 Ah pack than on a 30 Ah one, and
     * showing the first without the second is how an operator ends up trusting a number that
     * was built on someone else's airframe.
     */
    val packCapacityMah: Double? = null,
    val dischargeRateMahPerMin: Double? = null,

    // Cells
    val cellVoltages: List<Double> = emptyList(),
    val cellStats: CellStats? = null,

    // --- Phase 3: derived battery health -----------------------------------------
    /** Per-cell voltages with load sag added back (FR 2.1). */
    val restingCellVolts: List<Double> = emptyList(),
    val sagPerCellVolts: Double? = null,
    val restingPackVolts: Double? = null,
    /** Cell count and chemistry, resolved from cell telemetry or a baseline sample. */
    val batteryConfiguration: ResolvedBatteryConfiguration? = null,
    /** Per-cell resistance the sag compensation was run with, so the figure is traceable. */
    val internalResistanceOhmPerCell: Double = 0.0,
    /** Rolling window of recent samples, for the trend charts. */
    val history: TelemetryHistory = TelemetryHistory(),

    // Position / flight
    val gpsLocked: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val distanceToHomeMeters: Double? = null,
    val cruisingSpeedMps: Double? = null,
    val sprayingActive: Boolean = false,

    /**
     * FR 5.1. The same pure decision the [SafetyCoordinator] sends to the pump, evaluated
     * here so the dashboard cannot show an interlock state the interlock did not take.
     */
    val sprayInhibited: Boolean = false,
    val sprayInhibitReason: String? = null,

    // --- Phase 4 / Phase 5 ---------------------------------------------------------
    /** Dynamic RTL requirement (FR 3.1). Null means no estimate could be made. */
    val rtl: RtlAssessment? = null,
    val estimatedFlightMinutes: Double? = null,

    /**
     * Every alert condition currently true, worst first.
     *
     * All of them, not only the worst one: the requirements give each alert its own
     * indicator and action prompt, and a pack can be both imbalanced and low. [alertLevel]
     * is the headline, but the rest are still the reason to land.
     */
    val alerts: List<BatteryAlert> = emptyList(),

    // Demo controls
    val demoScenarios: List<DemoScenarioOption> = emptyList(),
    val speedOptions: List<Double> = SPEED_OPTIONS,
    val speedMultiplier: Double = 1.0,
) {
    /** Cell count to display, preferring the resolved configuration over the raw list. */
    val cellCount: Int? get() = batteryConfiguration?.cellCount ?: cellVoltages.size.takeIf { it > 0 }

    /** The headline level, derived so it cannot drift from [alerts]. */
    val alertLevel: AlertLevel
        get() = alerts.firstOrNull()?.level ?: AlertLevel.NORMAL

    /** The alert the banner leads with, or null when nothing is wrong. */
    val primaryAlert: BatteryAlert? get() = alerts.firstOrNull()

    /**
     * The worst active level among [rules], or [AlertLevel.NORMAL] when none of them fired.
     *
     * Cards use this instead of [alertLevel] so that a colour on a card is always about
     * that card. An RTL Critical must not turn the cell-balance chart red — the operator
     * would read that as a failing cell and land for the wrong reason.
     */
    fun levelFor(rules: Set<AlertRule>): AlertLevel =
        alerts.filter { it.rule in rules }
            .maxByOrNull { it.level.severity }
            ?.level
            ?: AlertLevel.NORMAL

    /** Single-rule convenience for cards that answer to exactly one rule. */
    fun levelFor(rule: AlertRule): AlertLevel = levelFor(setOf(rule))

    /** 1-based position of the lowest usable cell, or null when no cells are reported. */
    val weakestCellNumber: Int? get() = CellHealth.weakestCellNumber(cellVoltages)

    companion object {
        /** Demo playback rates, so a full drain can be shown in seconds rather than an hour. */
        val SPEED_OPTIONS = listOf(1.0, 5.0, 20.0, 60.0)
    }
}

/**
 * A fixed-length rolling window of recent telemetry.
 *
 * It lives in the UI state because the operator is the primary consumer: the trend charts
 * are its reason to exist. The alert engine also reads it — through the pure
 * `VoltageTrend` function, which is handed a copy and returns a number — so that "rapid
 * sag" can be a measured rate rather than an assertion.
 *
 * The timestamps are wall-clock, not simulated time, because they are what the app would
 * observe on a real serial link. Under demo acceleration that means the window spans the
 * same number of *ticks* but a much larger span of simulated flight, which is the honest
 * reading: an accelerated simulator is not evidence that a pack can sag that slowly.
 */
data class TelemetryHistory(
    val measuredPackVolts: List<Float> = emptyList(),
    val restingPackVolts: List<Float> = emptyList(),
    val amps: List<Float> = emptyList(),
    /** Wall-clock time of each sample, aligned with the three series above. */
    val sampleTimestampsMillis: List<Long> = emptyList(),
) {
    fun append(
        measuredPackVolts: Double?,
        restingPackVolts: Double?,
        amps: Double?,
        nowMillis: Long,
    ): TelemetryHistory {
        // The timestamp is recorded only when the voltage sample it belongs to is, so the
        // two series cannot drift out of alignment. VoltageTrend zips them, and a
        // misalignment would silently disable rapid-sag detection for the rest of the
        // session rather than fail loudly.
        val recorded = measuredPackVolts != null && measuredPackVolts.isFinite()
        return TelemetryHistory(
            measuredPackVolts = this.measuredPackVolts.push(measuredPackVolts),
            restingPackVolts = this.restingPackVolts.push(restingPackVolts),
            amps = this.amps.push(amps),
            sampleTimestampsMillis =
                if (recorded) this.sampleTimestampsMillis.push(nowMillis)
                else this.sampleTimestampsMillis,
        )
    }

    private fun List<Float>.push(value: Double?): List<Float> {
        if (value == null || !value.isFinite()) return this
        val appended = this + value.toFloat()
        return if (appended.size > WINDOW) appended.takeLast(WINDOW) else appended
    }

    private fun List<Long>.push(value: Long): List<Long> {
        val appended = this + value
        return if (appended.size > WINDOW) appended.takeLast(WINDOW) else appended
    }

    companion object {
        /** ~2 minutes at the simulator's 1 Hz publish rate. */
        const val WINDOW = 120
    }
}

/**
 * A demo scenario as the UI sees it. Keyed by string so the presentation layer never
 * has to import the mock source's enum.
 */
data class DemoScenarioOption(
    val key: String,
    val label: String,
    val description: String,
    val selected: Boolean,
)
