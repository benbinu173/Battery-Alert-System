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
import com.batteryalert.guard.domain.usecase.CellHealth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinates telemetry, calculations and demo controls into a single stream of UI
 * state. It knows nothing about serial ports, MAVLink framing or Compose.
 *
 * Phase 3-5 will add battery-health, RTL and alert use cases here; the shape of
 * [uiState] is already wide enough to carry their results.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val telemetryDataSource: TelemetryDataSource,
    private val demoController: DemoTelemetryController,
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
            telemetry.toUiState(demo)
        }.stateIn(
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
        // LifecycleViewModel cancels viewModelScope *before* calling onCleared, so the
        // final disconnect has to be issued from a short-lived detached scope. This is
        // cosmetic for the mock; it becomes load-bearing once a serial port is open.
        CoroutineScope(Dispatchers.Default).launch { telemetryDataSource.disconnect() }
    }

    private fun TelemetrySnapshot.toUiState(demo: DemoSnapshot): DashboardUiState = DashboardUiState(
        connectionState = connection,
        demoMode = demoController.isAvailable,
        batteryPercentage = battery.batteryPercentage,
        totalVoltage = battery.totalVoltage,
        current = battery.current,
        temperature = battery.temperature,
        remainingCapacityMah = battery.remainingCapacityMah,
        dischargeRateMahPerMin = battery.dischargeRateMahPerMin,
        cellVoltages = battery.cellVoltages,
        cellStats = CellHealth.stats(battery.cellVoltages),
        gpsLocked = gps.hasFix,
        latitude = gps.latitude,
        longitude = gps.longitude,
        altitudeMeters = gps.altitude,
        distanceToHomeMeters = gps.distanceToHomeMeters,
        cruisingSpeedMps = flight.cruisingSpeedMps,
        sprayingActive = flight.sprayingActive,
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
