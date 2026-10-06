package com.batteryalert.guard.presentation.dashboard

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.usecase.CellStats

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

    // Battery
    val batteryPercentage: Int? = null,
    val totalVoltage: Double? = null,
    val current: Double? = null,
    val temperature: Double? = null,
    val remainingCapacityMah: Double? = null,
    val dischargeRateMahPerMin: Double? = null,

    // Cells
    val cellVoltages: List<Double> = emptyList(),
    val cellStats: CellStats? = null,

    // Position / flight
    val gpsLocked: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val distanceToHomeMeters: Double? = null,
    val cruisingSpeedMps: Double? = null,
    val sprayingActive: Boolean = false,

    // --- Populated from Phase 4 / Phase 5 onward ---------------------------------
    /** Minimum battery % needed to get home plus the 15% safety margin. */
    val requiredRtlBattery: Double? = null,
    val estimatedFlightMinutes: Double? = null,
    val alertLevel: AlertLevel = AlertLevel.NORMAL,
    val alertMessage: String? = null,

    // Demo controls
    val demoScenarios: List<DemoScenarioOption> = emptyList(),
    val speedOptions: List<Double> = SPEED_OPTIONS,
    val speedMultiplier: Double = 1.0,
) {
    companion object {
        /** Demo playback rates, so a full drain can be shown in seconds rather than an hour. */
        val SPEED_OPTIONS = listOf(1.0, 5.0, 20.0, 60.0)
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
