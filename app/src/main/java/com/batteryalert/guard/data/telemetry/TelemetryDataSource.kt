package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.domain.model.BatteryTelemetry
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.model.FlightState
import com.batteryalert.guard.domain.model.GpsData
import kotlinx.coroutines.flow.Flow

/**
 * The single boundary between the app and *any* source of telemetry.
 *
 * Nothing above this interface knows whether the bytes came from a Skydroid GR01
 * over USB-UART, an internal /dev/ttySx node, or the simulator. Swapping the source
 * is a DI change, not a UI change.
 */
interface TelemetryDataSource {

    fun telemetryFlow(): Flow<BatteryTelemetry>

    fun gpsFlow(): Flow<GpsData>

    fun flightStateFlow(): Flow<FlightState>

    fun connectionStateFlow(): Flow<ConnectionState>

    suspend fun connect()

    suspend fun disconnect()
}

/**
 * Implemented only by simulated sources. Real hardware has no scenarios to select,
 * so the dashboard checks for this before offering demo controls.
 */
interface DemoTelemetryController {

    /** False for the no-op implementation bound when a real source is in use. */
    val isAvailable: Boolean

    val scenarios: List<MockScenario>
    fun activeScenario(): Flow<MockScenario>
    fun selectScenario(scenario: MockScenario)
    fun speedMultiplier(): Flow<Double>
    fun setSpeedMultiplier(multiplier: Double)
}
