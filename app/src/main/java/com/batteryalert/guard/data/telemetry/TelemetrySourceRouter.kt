package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.data.link.LinkMode
import com.batteryalert.guard.data.link.LinkSettings
import com.batteryalert.guard.data.link.LinkSettingsStore
import com.batteryalert.guard.data.telemetry.mavlink.LinkHealth
import com.batteryalert.guard.di.TelemetryDispatcher
import com.batteryalert.guard.domain.model.BatteryTelemetry
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.model.FlightState
import com.batteryalert.guard.domain.model.GpsData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The source of telemetry, chosen while the app is running.
 *
 * ### The problem this solves
 *
 * Dependency injection decides what a `TelemetryDataSource` is when the app is built. The
 * operator decides what it should be when they open the Link screen and pick the simulator or a
 * UDP port. Both of those are reasonable, and they happen at different times, so something has to
 * sit between them. This is that something.
 *
 * ### Why the flows are wrapped rather than mirrored
 *
 * Each flow is `flatMapLatest` over the settings, so the subscription moves to the newly chosen
 * source the instant the mode changes and there is no second copy of any reading to keep in step.
 * The alternative — collecting the active source and republishing into `MutableStateFlow`s — would
 * mean five more places where "what the dashboard shows" and "what the aircraft said" could
 * disagree, which is the failure this whole codebase is arranged to avoid.
 *
 * ### Restarting the link
 *
 * Changing the setting is not enough on its own. A socket bound to the old port, or an adapter
 * opened because the mode used to be USB, is still open after the store has moved on, and the
 * screen would say one thing while the app did another. So the collector below tears the old
 * source down and brings the new one up — but only when something was running in the first place,
 * because a settings change made before the first [connect] has nothing to restart.
 *
 * A change of *port* is included in that, which is why the collector watches the whole
 * [LinkSettings] rather than just the mode. The port only means anything to a UDP socket, so a
 * port change in any other mode is deliberately ignored rather than pointlessly cycling the
 * simulator back to its starting state.
 *
 * ### Why the simulator and the wire are treated as sources, not transports
 *
 * [SwitchableTelemetryTransport] already chooses between UDP and USB, so it might look like the
 * simulator belongs there too. It does not: the simulator produces domain objects directly and
 * has no bytes to offer, so it cannot be a `TelemetryTransport` at all. The switch between
 * "simulated" and "real" is therefore a switch between two [TelemetryDataSource]s, and the switch
 * between UDP and USB is a switch between two transports, and they are two different seams
 * because they are two different kinds of thing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TelemetrySourceRouter @Inject constructor(
    private val settings: LinkSettingsStore,
    private val mock: MockTelemetryDataSource,
    private val wire: SerialTelemetryDataSource,
    @TelemetryDispatcher dispatcher: CoroutineDispatcher,
) : TelemetryDataSource, LinkHealthSource, DemoTelemetryController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Whether the link is supposed to be up.
     *
     * Set by [connect] and cleared by [disconnect], so a settings change can tell "the operator
     * changed their mind about a link that is running" from "the operator changed their mind
     * about a link that was never started". Only the first needs a restart.
     */
    @Volatile
    private var started = false

    /**
     * The settings the running source was started with.
     *
     * Distinct from what the store currently holds: between a change being written and the
     * restart below completing, those are two different things, and the tear-down has to act on
     * what is actually open rather than on what the operator has just asked for.
     */
    @Volatile
    private var applied: LinkSettings = settings.settings.value

    init {
        scope.launch {
            settings.settings.collect { updated ->
                val previous = applied
                applied = updated

                if (updated == previous) return@collect
                if (!started) return@collect
                if (previous.mode == updated.mode && updated.mode != LinkMode.UDP) return@collect

                sourceFor(previous.mode).disconnect()
                sourceFor(updated.mode).connect()
            }
        }
    }

    // --- TelemetryDataSource ------------------------------------------------------------------

    override fun telemetryFlow(): Flow<BatteryTelemetry> =
        settings.settings.flatMapLatest { sourceFor(it.mode).telemetryFlow() }

    override fun gpsFlow(): Flow<GpsData> =
        settings.settings.flatMapLatest { sourceFor(it.mode).gpsFlow() }

    override fun flightStateFlow(): Flow<FlightState> =
        settings.settings.flatMapLatest { sourceFor(it.mode).flightStateFlow() }

    override fun connectionStateFlow(): Flow<ConnectionState> =
        settings.settings.flatMapLatest { sourceFor(it.mode).connectionStateFlow() }

    override suspend fun connect() {
        val current = settings.settings.value
        applied = current
        started = true
        sourceFor(current.mode).connect()
    }

    override suspend fun disconnect() {
        started = false
        sourceFor(applied.mode).disconnect()
    }

    // --- LinkHealthSource ---------------------------------------------------------------------

    /**
     * The wire's account of itself, or null while the simulator is running.
     *
     * Routed through [LinkHealthSource.NoLinkHealth] rather than answered by the simulator,
     * because the simulator has no wire and no answer to give. A set of zeroes from it would read
     * as "I watched the link and nothing went wrong", which is a measurement this mode cannot
     * make.
     */
    override fun linkHealthFlow(): Flow<LinkHealth?> =
        settings.settings.flatMapLatest { healthSourceFor(it.mode).linkHealthFlow() }

    // --- DemoTelemetryController --------------------------------------------------------------

    /**
     * Whether the demo controls belong on the dashboard.
     *
     * A getter rather than a constant because the answer moves with the mode, and the dashboard
     * reads it once per frame rather than once per launch — so the scenarios appear and disappear
     * as the operator switches, with no restart.
     */
    override val isAvailable: Boolean
        get() = applied.mode == LinkMode.DEMO

    /** Empty outside demo mode, which is what keeps the scenario chips off a real flight. */
    override val scenarios: List<MockScenario>
        get() = if (isAvailable) mock.scenarios else emptyList()

    /**
     * The scenario and speed flows are delegated **unconditionally**, including outside demo
     * mode.
     *
     * Not lazy, and that matters: `DashboardViewModel` combines these two into the state the
     * whole dashboard is built from, so a flow that stopped emitting outside demo mode would
     * stall every screen rather than merely hiding some chips. Its values are ignored when
     * [isAvailable] is false; the placeholder behaviour that `NoOpDemoController` used to supply
     * is now just the simulator's own values going unread.
     */
    override fun activeScenario(): Flow<MockScenario> = mock.activeScenario()

    override fun speedMultiplier(): Flow<Double> = mock.speedMultiplier()

    override fun selectScenario(scenario: MockScenario) = mock.selectScenario(scenario)

    override fun setSpeedMultiplier(multiplier: Double) = mock.setSpeedMultiplier(multiplier)

    private fun sourceFor(mode: LinkMode): TelemetryDataSource =
        if (mode == LinkMode.DEMO) mock else wire

    private fun healthSourceFor(mode: LinkMode): LinkHealthSource =
        if (mode == LinkMode.DEMO) LinkHealthSource.NoLinkHealth else wire
}
