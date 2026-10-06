package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.domain.model.BatteryTelemetry
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.model.FlightState
import com.batteryalert.guard.domain.model.GpsData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val TICK_MS = 1_000L
private const val CONNECT_HANDSHAKE_MS = 600L

private const val PACK_SERIES_CELLS = 12
private const val PACK_CAPACITY_MAH = 16_000.0

private const val WEAK_CELL_INDEX = 5
private const val CELL_NOISE_VOLTS = 0.003
private const val LOAD_NOISE_FRACTION = 0.05

private const val MIN_SPEED = 1.0
private const val MAX_SPEED = 60.0
private const val MAX_SUB_STEPS = 120

private const val AMBIENT_TEMPERATURE_C = 28.0
private const val TEMPERATURE_TIME_CONSTANT_S = 240.0
private const val IMBALANCE_RAMP_SECONDS = 8.0
private const val IMBALANCE_VISIBLE_THRESHOLD_V = 0.03

private const val HEADING_DRIFT_RAD_PER_SECOND = 0.002
private const val MILLIAMPS_PER_MINUTE_PER_AMP = 1000.0 / 60.0

private const val CRUISE_ALTITUDE_METERS = 18.0
private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
private const val HOME_LATITUDE = 12.9716
private const val HOME_LONGITUDE = 77.5946

/** (state-of-charge %, resting volts per cell), descending by SOC. */
private val SOC_CURVE = listOf(
    100.0 to 4.20,
    90.0 to 4.10,
    80.0 to 4.00,
    70.0 to 3.92,
    60.0 to 3.85,
    50.0 to 3.78,
    40.0 to 3.72,
    30.0 to 3.65,
    20.0 to 3.58,
    10.0 to 3.48,
    5.0 to 3.40,
    0.0 to 3.20,
)

/**
 * A physically-plausible software stand-in for the Skydroid telemetry link.
 *
 * This is deliberately *not* a random number generator. It coulomb-counts a 12S
 * 16 Ah pack, derives per-cell open-circuit voltage from a state-of-charge curve,
 * subtracts I x Ri sag under load, and reports the result through exactly the same
 * interface the real serial source will use. Every threshold, alert and RTL
 * decision exercised in demo mode is therefore exercised against realistic numbers.
 *
 * Hardware honesty: this class never pretends to be the drone. Anything reading it
 * knows it is simulated because demo mode is surfaced in the UI state.
 */
@Singleton
class MockTelemetryDataSource @Inject constructor() :
    TelemetryDataSource,
    DemoTelemetryController {

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _telemetry = MutableStateFlow(BatteryTelemetry.EMPTY)
    private val _gps = MutableStateFlow(GpsData.EMPTY)
    private val _flight = MutableStateFlow(FlightState.EMPTY)

    private val _scenario = MutableStateFlow(MockScenario.NORMAL_FLIGHT)
    private val _speedMultiplier = MutableStateFlow(1.0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var simulationJob: Job? = null

    override fun telemetryFlow(): Flow<BatteryTelemetry> = _telemetry.asStateFlow()

    override fun gpsFlow(): Flow<GpsData> = _gps.asStateFlow()

    override fun flightStateFlow(): Flow<FlightState> = _flight.asStateFlow()

    override fun connectionStateFlow(): Flow<ConnectionState> = _connection.asStateFlow()

    override suspend fun connect() {
        if (simulationJob?.isActive == true) return

        _connection.value = ConnectionState.CONNECTING
        delay(CONNECT_HANDSHAKE_MS)
        _connection.value = ConnectionState.CONNECTED

        simulationJob = scope.launch { runSimulation(_scenario.value) }
    }

    override suspend fun disconnect() {
        simulationJob?.cancel()
        simulationJob = null
        _connection.value = ConnectionState.DISCONNECTED
        _telemetry.value = BatteryTelemetry.EMPTY
        _gps.value = GpsData.EMPTY
        _flight.value = FlightState.EMPTY
    }

    // --- DemoTelemetryController -------------------------------------------------

    override val isAvailable: Boolean = true

    override val scenarios: List<MockScenario> = MockScenario.entries.toList()

    override fun activeScenario(): Flow<MockScenario> = _scenario.asStateFlow()

    /**
     * Only records the request. The simulation loop notices the change on its next
     * tick and restarts from that scenario's designed starting state, which keeps all
     * mutable simulation state confined to a single coroutine.
     */
    override fun selectScenario(scenario: MockScenario) {
        _scenario.value = scenario
    }

    override fun speedMultiplier(): Flow<Double> = _speedMultiplier.asStateFlow()

    override fun setSpeedMultiplier(multiplier: Double) {
        _speedMultiplier.value = multiplier.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    // --- Simulation --------------------------------------------------------------

    private suspend fun runSimulation(startScenario: MockScenario) {
        var activeScenario = startScenario
        var sim = SimState.startingAt(startScenario.profile)

        while (currentCoroutineContext().isActive) {
            val scenario = _scenario.value
            if (scenario != activeScenario) {
                activeScenario = scenario
                sim = SimState.startingAt(scenario.profile)
            }

            val profile = scenario.profile
            val elapsedSimSeconds = TICK_MS / 1000.0 * _speedMultiplier.value
            sim = sim.advance(profile, elapsedSimSeconds)

            publish(sim, profile, scenario)
            delay(TICK_MS)
        }
    }

    private fun publish(sim: SimState, profile: ScenarioProfile, scenario: MockScenario) {
        val remainingMah = (PACK_CAPACITY_MAH - sim.consumedMah).coerceAtLeast(0.0)
        val stateOfCharge = remainingMah / PACK_CAPACITY_MAH * 100.0

        // Measured cell voltage = open-circuit voltage minus sag under the present
        // load. The voltage-sag use case adds the I x Ri term back to recover the
        // resting voltage, so these two must stay consistent.
        val restingVoltsPerCell = stateOfChargeToRestingVolts(stateOfCharge)
        val sagPerCell = sim.amps * profile.internalResistanceOhm
        val measuredBaseVolts = restingVoltsPerCell - sagPerCell

        val cellVoltages = buildCellVoltages(
            baseVolts = measuredBaseVolts,
            cellCount = PACK_SERIES_CELLS,
            spreadVolts = profile.cellSpreadVolts * sim.imbalanceRamp,
        )

        _telemetry.value = BatteryTelemetry(
            batteryPercentage = stateOfCharge.roundToInt().coerceIn(0, 100),
            totalVoltage = cellVoltages.sum(),
            current = sim.amps,
            cellVoltages = cellVoltages,
            temperature = sim.temperatureC,
            remainingCapacityMah = remainingMah,
            dischargeRateMahPerMin = sim.amps * MILLIAMPS_PER_MINUTE_PER_AMP,
        )

        _gps.value = GpsData(
            latitude = HOME_LATITUDE +
                sim.distanceHomeMeters * cos(sim.headingRad) / METERS_PER_DEGREE_LATITUDE,
            longitude = HOME_LONGITUDE +
                sim.distanceHomeMeters * sin(sim.headingRad) /
                (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(HOME_LATITUDE))),
            altitude = CRUISE_ALTITUDE_METERS + Random.nextDouble(-0.4, 0.4),
            distanceToHomeMeters = sim.distanceHomeMeters,
            hasFix = true,
        )

        _flight.value = FlightState(
            cruisingSpeedMps = (profile.cruiseSpeedMps + Random.nextDouble(-0.3, 0.3))
                .coerceAtLeast(0.5),
            payloadActive = scenario != MockScenario.EMERGENCY,
            sprayingActive = scenario != MockScenario.EMERGENCY,
        )
    }

    /**
     * Spreads a pack voltage across cells. When a spread is configured, one known cell
     * is made weaker than the rest — which is how a real imbalance presents itself.
     */
    private fun buildCellVoltages(baseVolts: Double, cellCount: Int, spreadVolts: Double): List<Double> {
        if (cellCount <= 0) return emptyList()
        val weakCell = WEAK_CELL_INDEX.coerceIn(0, cellCount - 1)
        return List(cellCount) { index ->
            val offset = if (index == weakCell) -spreadVolts else spreadVolts * 0.15
            baseVolts + offset + Random.nextDouble(-CELL_NOISE_VOLTS, CELL_NOISE_VOLTS)
        }
    }

    /**
     * Resting open-circuit voltage per cell for a Li-ion/LiPo pack, interpolated from a
     * piecewise linear state-of-charge curve. Ten-percent steps are ample for a
     * simulator; the real source reports measured voltage directly.
     */
    private fun stateOfChargeToRestingVolts(stateOfCharge: Double): Double {
        val soc = stateOfCharge.coerceIn(0.0, 100.0)
        for (i in SOC_CURVE.lastIndex downTo 1) {
            val (highSoc, highVolts) = SOC_CURVE[i - 1]
            val (lowSoc, lowVolts) = SOC_CURVE[i]
            if (soc in lowSoc..highSoc) {
                val span = highSoc - lowSoc
                val ratio = if (span == 0.0) 0.0 else (soc - lowSoc) / span
                return lowVolts + ratio * (highVolts - lowVolts)
            }
        }
        return SOC_CURVE.last().second
    }
}

private data class SimState(
    val consumedMah: Double,
    val distanceHomeMeters: Double,
    val headingRad: Double,
    val temperatureC: Double,
    val imbalanceRamp: Double,
    val amps: Double,
) {
    fun advance(profile: ScenarioProfile, elapsedSeconds: Double): SimState {
        // Sub-step so a high demo speed multiplier cannot drain the pack in one jump
        // and skip straight past the alert bands we are trying to demonstrate.
        val steps = ceil(elapsedSeconds).toInt().coerceIn(1, MAX_SUB_STEPS)
        val dt = elapsedSeconds / steps

        var next = this
        repeat(steps) { next = next.step(profile, dt) }
        return next
    }

    private fun step(profile: ScenarioProfile, dt: Double): SimState {
        val loadAmps = (profile.nominalAmps * (1.0 + Random.nextDouble(-LOAD_NOISE_FRACTION, LOAD_NOISE_FRACTION)))
            .coerceAtLeast(0.1)

        return copy(
            consumedMah = consumedMah + loadAmps * dt / 3.6, // A x s -> mAh
            distanceHomeMeters = (distanceHomeMeters + profile.distanceRateMps * dt)
                .coerceIn(0.0, profile.maxDistanceMeters),
            headingRad = headingRad + dt * HEADING_DRIFT_RAD_PER_SECOND,
            temperatureC = temperatureC +
                (profile.equilibriumTemperatureC - temperatureC) * (dt / TEMPERATURE_TIME_CONSTANT_S),
            imbalanceRamp = (imbalanceRamp + dt / IMBALANCE_RAMP_SECONDS).coerceAtMost(1.0),
            amps = loadAmps,
        )
    }

    companion object {
        fun startingAt(profile: ScenarioProfile): SimState {
            val startSoc = profile.startPercent.coerceIn(0.0, 100.0)
            return SimState(
                consumedMah = PACK_CAPACITY_MAH * (1.0 - startSoc / 100.0),
                distanceHomeMeters = profile.startDistanceMeters,
                headingRad = 0.6,
                temperatureC = AMBIENT_TEMPERATURE_C,
                // A real imbalance does not appear instantly, so neither does this one.
                // Scenarios without a meaningful spread start fully ramped.
                imbalanceRamp = if (profile.cellSpreadVolts > IMBALANCE_VISIBLE_THRESHOLD_V) 0.0 else 1.0,
                amps = profile.nominalAmps,
            )
        }
    }
}

/**
 * The physical situation behind each demo scenario. Values are chosen so the pack
 * lands inside the alert band the scenario is named for — see the comments.
 */
internal data class ScenarioProfile(
    val startPercent: Double,
    val startDistanceMeters: Double,
    val nominalAmps: Double,
    val internalResistanceOhm: Double,
    val cellSpreadVolts: Double,
    val distanceRateMps: Double,
    val maxDistanceMeters: Double,
    val equilibriumTemperatureC: Double,
    val cruiseSpeedMps: Double,
)

internal val MockScenario.profile: ScenarioProfile
    get() = when (this) {
        // ~4.1 V/cell and a tight spread: healthy, nothing trips.
        MockScenario.NORMAL_FLIGHT -> ScenarioProfile(
            startPercent = 98.0,
            startDistanceMeters = 40.0,
            nominalAmps = 18.5,
            internalResistanceOhm = 0.0040,
            cellSpreadVolts = 0.010,
            distanceRateMps = 1.5,
            maxDistanceMeters = 600.0,
            equilibriumTemperatureC = 38.0,
            cruiseSpeedMps = 10.0,
        )

        // ~23% and ~3.51 V/cell: trips both the percentage and the voltage Warning.
        MockScenario.LOW_BATTERY -> ScenarioProfile(
            startPercent = 23.0,
            startDistanceMeters = 260.0,
            nominalAmps = 22.0,
            internalResistanceOhm = 0.0040,
            cellSpreadVolts = 0.022,
            distanceRateMps = 0.8,
            maxDistanceMeters = 400.0,
            equilibriumTemperatureC = 44.0,
            cruiseSpeedMps = 9.0,
        )

        // Healthy cell voltage (~3.88 V) but a ~0.12 V spread: Cell Fault only, so the
        // imbalance is demonstrated without a low-voltage alert masking it.
        MockScenario.CELL_IMBALANCE -> ScenarioProfile(
            startPercent = 75.0,
            startDistanceMeters = 180.0,
            nominalAmps = 20.0,
            internalResistanceOhm = 0.0040,
            cellSpreadVolts = 0.105,
            distanceRateMps = 0.6,
            maxDistanceMeters = 250.0,
            equilibriumTemperatureC = 45.0,
            cruiseSpeedMps = 9.0,
        )

        // 26% at 2.4 km out: the dynamic RTL threshold (flight time home x discharge
        // rate + 15% margin) climbs above remaining, which is the Critical trigger.
        MockScenario.RTL_REQUIRED -> ScenarioProfile(
            startPercent = 26.0,
            startDistanceMeters = 2_400.0,
            nominalAmps = 24.0,
            internalResistanceOhm = 0.0045,
            cellSpreadVolts = 0.030,
            distanceRateMps = 0.0,
            maxDistanceMeters = 2_400.0,
            equilibriumTemperatureC = 46.0,
            cruiseSpeedMps = 8.0,
        )

        // ~3.30 V/cell, deep in the Emergency band.
        MockScenario.EMERGENCY -> ScenarioProfile(
            startPercent = 9.0,
            startDistanceMeters = 320.0,
            nominalAmps = 28.0,
            internalResistanceOhm = 0.0060,
            cellSpreadVolts = 0.035,
            distanceRateMps = 0.0,
            maxDistanceMeters = 320.0,
            equilibriumTemperatureC = 52.0,
            cruiseSpeedMps = 7.0,
        )
    }
