package com.batteryalert.guard.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batteryalert.guard.data.telemetry.DemoTelemetryController
import com.batteryalert.guard.data.telemetry.MockScenario
import com.batteryalert.guard.data.telemetry.TelemetryDataSource
import com.batteryalert.guard.domain.model.BatteryTelemetry
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.model.FlightState
import com.batteryalert.guard.domain.model.GpsData
import com.batteryalert.guard.domain.usecase.AlertEngine
import com.batteryalert.guard.domain.usecase.AlertInputs
import com.batteryalert.guard.domain.usecase.BatteryConfigurationResolver
import com.batteryalert.guard.domain.usecase.CellHealth
import com.batteryalert.guard.domain.usecase.FlightTime
import com.batteryalert.guard.domain.usecase.RtlCalculator
import com.batteryalert.guard.domain.usecase.SprayInterlock
import com.batteryalert.guard.domain.usecase.VoltageSag
import com.batteryalert.guard.domain.usecase.VoltageTrend
import com.batteryalert.guard.safety.SafetyCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinates telemetry, calculations and demo controls into a single stream of UI
 * state. It knows nothing about serial ports, MAVLink framing or Compose.
 *
 * The derived battery figures (sag compensation, discharge rate, remaining flight time,
 * pack configuration) are computed here, once, from pure use cases — not in a composable
 * and not in a formatter. Phase 4 and 5 add the RTL and alert engines at the same seam.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val telemetryDataSource: TelemetryDataSource,
    private val demoController: DemoTelemetryController,
    private val safetyCoordinator: SafetyCoordinator,
) : ViewModel() {

    private data class TelemetrySnapshot(
        val battery: BatteryTelemetry,
        val gps: GpsData,
        val flight: FlightState,
        val connection: ConnectionState,
    )

    private data class DemoSnapshot(
        val scenario: MockScenario,
        val speedMultiplier: Double,
    )

    private data class SourceFrame(
        val telemetry: TelemetrySnapshot,
        val demo: DemoSnapshot,
    )

    /**
     * One rendered frame plus the rolling history it contributed to. Carrying the history
     * through the stream (rather than in a mutable field) keeps the whole pipeline a pure
     * function of its inputs.
     */
    private data class Frame(
        val source: SourceFrame,
        val history: TelemetryHistory,
    )

    // combine() is only typed up to five flows, so the two halves are combined first.
    private val telemetryStream = combine(
        telemetryDataSource.telemetryFlow(),
        telemetryDataSource.gpsFlow(),
        telemetryDataSource.flightStateFlow(),
        telemetryDataSource.connectionStateFlow(),
    ) { battery, gps, flight, connection ->
        TelemetrySnapshot(battery, gps, flight, connection)
    }

    private val demoStream = combine(
        demoController.activeScenario(),
        demoController.speedMultiplier(),
    ) { scenario, speed ->
        DemoSnapshot(scenario, speed)
    }

    val uiState: StateFlow<DashboardUiState> =
        combine(telemetryStream, demoStream) { telemetry, demo ->
                SourceFrame(telemetry, demo)
            }
            .runningFold<SourceFrame, Frame?>(null) { previous, source ->
                val battery = source.telemetry.battery
                val sag = VoltageSag.compensate(
                    measuredCellVolts = battery.cellVoltages,
                    currentAmps = battery.current,
                    internalResistanceOhmPerCell = INTERNAL_RESISTANCE_OHM_PER_CELL,
                )

                // The history is a window on one continuous signal. Selecting a demo
                // scenario replaces the simulated pack with a different one, so a window
                // spanning the switch would contain two unrelated voltages — 49 V falling
                // to 38 V in a single sample, which the alert engine would correctly read
                // as a collapse and incorrectly announce as a rapid-sag Emergency. The
                // discontinuity is in the source, so the source change resets the window.
                val history = if (previous?.source?.demo?.scenario == source.demo.scenario) {
                    previous.history
                } else {
                    TelemetryHistory()
                }

                Frame(
                    source = source,
                    history = history.append(
                        measuredPackVolts = battery.totalVoltage,
                        restingPackVolts = sag?.restingPackVolts,
                        amps = battery.current,
                        // Wall clock, so the sag rate is measured in the same seconds a
                        // real serial link would be sampled in.
                        nowMillis = System.currentTimeMillis(),
                    ),
                )
            }
            .filterNotNull()
            .map { frame -> frame.toUiState() }
            // The side effects — voice, haptics, spray interlock — hang off the same
            // frames the dashboard renders, so what is announced and what is displayed
            // can never describe different situations. Note this sits upstream of
            // stateIn, so the app is silent when nothing is watching the dashboard,
            // which is the correct behaviour for a foreground safety display.
            .onEach { state ->
                safetyCoordinator.onFrame(
                    level = state.alertLevel,
                    primary = state.primaryAlert,
                    batteryPercentage = state.batteryPercentage?.toDouble(),
                    nowMillis = System.currentTimeMillis(),
                )
            }
            .stateIn(
                scope = viewModelScope,
                // Keeps the simulation alive across a brief configuration change without
                // leaving it running forever once the screen is genuinely gone.
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = DashboardUiState(),
            )

    init {
        viewModelScope.launch { telemetryDataSource.connect() }
    }

    fun onScenarioSelected(key: String) {
        val scenario = MockScenario.entries.firstOrNull { it.name == key } ?: return
        demoController.selectScenario(scenario)
    }

    fun onSpeedSelected(multiplier: Double) {
        demoController.setSpeedMultiplier(multiplier)
    }

    override fun onCleared() {
        super.onCleared()

        // Cutting the voice mid-sentence would be worse than never having spoken. The
        // announcer itself is an app-scoped singleton and outlives this ViewModel, so it
        // is silenced, not shut down — the next dashboard reuses the same engine.
        safetyCoordinator.stop()

        // LifecycleViewModel cancels viewModelScope *before* calling onCleared, so the
        // final disconnect has to be issued from a short-lived detached scope. This is
        // cosmetic for the mock; it becomes load-bearing once a serial port is open.
        CoroutineScope(Dispatchers.Default).launch { telemetryDataSource.disconnect() }
    }

    private fun Frame.toUiState(): DashboardUiState {
        val battery = source.telemetry.battery
        val gps = source.telemetry.gps
        val flight = source.telemetry.flight
        val demo = source.demo

        val sag = VoltageSag.compensate(
            measuredCellVolts = battery.cellVoltages,
            currentAmps = battery.current,
            internalResistanceOhmPerCell = INTERNAL_RESISTANCE_OHM_PER_CELL,
        )

        // The computed rate is what FR 2.3 asks for; the source's own figure is only a
        // fallback for when current itself is unusable.
        val dischargeRate = FlightTime.dischargeRateMahPerMin(battery.current)
            ?: battery.dischargeRateMahPerMin

        val configuration = BatteryConfigurationResolver.resolve(
            // Compensated cells when we have them: resting voltage is the one that
            // corresponds to state of charge.
            cellVoltages = sag?.restingCellVolts ?: battery.cellVoltages,
            baselinePackVolts = battery.totalVoltage,
        )

        // FR 3.1. Needs a distance, a return speed and a rate; null when any of them is
        // missing, which the UI renders as "no RTL figure" rather than 0% required.
        val rtl = RtlCalculator.assess(
            distanceToHomeMeters = gps.distanceToHomeMeters,
            returnSpeedMps = flight.cruisingSpeedMps,
            dischargeRateMahPerMin = dischargeRate,
            remainingCapacityMah = battery.remainingCapacityMah,
            batteryPercentage = battery.batteryPercentage?.toDouble(),
        )

        val cellStats = CellHealth.stats(battery.cellVoltages)

        // FR 5.1. Evaluated from the same pure decision the SafetyCoordinator acts on, so
        // the dashboard and the pump cannot disagree about whether spraying is allowed.
        val sprayInhibited = SprayInterlock.shouldInhibit(battery.batteryPercentage?.toDouble())

        // The rate at which the pack is losing volts per cell per second, fitted across
        // the history window. This is the "rapid sag" input the requirements name but do
        // not quantify; the threshold it is compared against lives in AlertThresholds.
        val decline = VoltageTrend.declineVoltsPerCellPerSecond(
            packVolts = history.measuredPackVolts.map { it.toDouble() },
            timestampsMillis = history.sampleTimestampsMillis,
            cellCount = configuration?.cellCount ?: battery.cellVoltages.size,
        )

        val alerts = AlertEngine.evaluate(
            AlertInputs(
                batteryPercentage = battery.batteryPercentage?.toDouble(),
                // Under load, not at rest: these thresholds mark where the pack stops
                // delivering, and a resting-voltage rule would stay silent while it sagged
                // through them. See AlertInputs.
                weakestCellVoltsUnderLoad = cellStats?.minVolts,
                cellDeltaVolts = cellStats?.deltaVolts,
                weakestCellNumber = CellHealth.weakestCellNumber(battery.cellVoltages),
                rtl = rtl,
                declineVoltsPerCellPerSecond = decline,
            ),
        )

        return DashboardUiState(
            connectionState = source.telemetry.connection,
            demoMode = demoController.isAvailable,
            batteryPercentage = battery.batteryPercentage,
            totalVoltage = battery.totalVoltage,
            current = battery.current,
            temperature = battery.temperature,
            remainingCapacityMah = battery.remainingCapacityMah,
            dischargeRateMahPerMin = dischargeRate,
            cellVoltages = battery.cellVoltages,
            cellStats = cellStats,
            restingCellVolts = sag?.restingCellVolts.orEmpty(),
            sagPerCellVolts = sag?.sagPerCellVolts,
            restingPackVolts = sag?.restingPackVolts,
            batteryConfiguration = configuration,
            internalResistanceOhmPerCell = INTERNAL_RESISTANCE_OHM_PER_CELL,
            history = history,
            gpsLocked = gps.hasFix,
            latitude = gps.latitude,
            longitude = gps.longitude,
            altitudeMeters = gps.altitude,
            distanceToHomeMeters = gps.distanceToHomeMeters,
            cruisingSpeedMps = flight.cruisingSpeedMps,
            sprayingActive = flight.sprayingActive,
            sprayInhibited = sprayInhibited,
            sprayInhibitReason = SprayInterlock
                .reason(battery.batteryPercentage?.toDouble())
                .takeIf { sprayInhibited },
            rtl = rtl,
            estimatedFlightMinutes = FlightTime.estimatedMinutes(
                remainingCapacityMah = battery.remainingCapacityMah,
                dischargeRateMahPerMin = dischargeRate,
            ),
            alerts = alerts,
            demoScenarios = demoController.scenarios.map { scenario ->
                DemoScenarioOption(
                    key = scenario.name,
                    label = scenario.label,
                    description = scenario.description,
                    selected = scenario == demo.scenario,
                )
            },
            speedOptions = DashboardUiState.SPEED_OPTIONS,
            speedMultiplier = demo.speedMultiplier,
        )
    }

    private companion object {
        /**
         * Per-cell internal resistance used for sag compensation.
         *
         * The requirements do not specify a value, so this is the documented default from
         * [VoltageSag] rather than a measured constant. It is surfaced in the UI so a
         * figure derived from it is never presented as though it were measured.
         */
        const val INTERNAL_RESISTANCE_OHM_PER_CELL =
            VoltageSag.DEFAULT_INTERNAL_RESISTANCE_OHM_PER_CELL
    }
}
