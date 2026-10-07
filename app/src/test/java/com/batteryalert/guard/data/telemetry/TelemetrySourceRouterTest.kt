package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.data.link.LinkMode
import com.batteryalert.guard.data.link.LinkSettings
import com.batteryalert.guard.data.link.LinkSettingsStore
import com.batteryalert.guard.domain.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing the telemetry source while the app is running.
 *
 * ### What is actually being tested here
 *
 * Not the flows — those are forwarded, and a forwarding bug would show up as a compile error
 * before it showed up as a wrong reading. What this file is about is the *lifecycle*: that a
 * setting change reaches the source that is already running, tears it down in the right order,
 * and starts the one the operator swapped to. Getting that wrong leaves a socket bound to the
 * old port or an adapter open for a mode the app has already left, and the screen would say one
 * thing while the app did another.
 *
 * ### Why the wire is a real `SerialTelemetryDataSource`
 *
 * The router takes the concrete class, not an interface, so the only way to observe what it did
 * is to give the real source a transport that records being opened. That is a better test than a
 * hand-written stand-in would have been: it exercises the actual retry loop and watchdog
 * starting up, which is the part a stub would have skipped.
 *
 * ### Why nothing here calls `advanceUntilIdle` after the wire connects
 *
 * The wire's watchdog re-arms a timer on every tick, so once it is running there is always
 * another scheduled task and "advance until idle" never returns. [settle] drains the dispatches
 * at the current virtual instant instead, which is what the suspension chain here actually
 * needs. `SerialTelemetryDataSourceTest` has the same constraint for the same reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetrySourceRouterTest {

    private val dispatcher = StandardTestDispatcher()

    /** A clock this test never moves; only the watchdog reads it, and it is not under test. */
    private var now = 1_000_000L

    private class FakeLinkSettings(initial: LinkSettings) : LinkSettingsStore {
        private val state = MutableStateFlow(initial)
        override val settings: StateFlow<LinkSettings> = state.asStateFlow()
        override fun setMode(mode: LinkMode) { state.value = state.value.copy(mode = mode) }
        override fun setUdpPort(port: Int) { state.value = state.value.copy(udpPort = port) }
    }

    private class RecordingTransport : TelemetryTransport {
        private var chunks = Channel<ByteArray>(Channel.UNLIMITED)

        var openCount = 0
            private set

        var closeCount = 0
            private set

        override val name: String = "recording"

        override suspend fun open() {
            openCount++
            chunks = Channel(Channel.UNLIMITED)
        }

        override fun incoming(): Flow<ByteArray> = chunks.consumeAsFlow()

        override suspend fun close() {
            closeCount++
            chunks.close()
        }
    }

    /**
     * A router over a wire that records being opened.
     *
     * Runs [body] and disconnects afterwards: the wire's read loop and watchdog are both
     * infinite, and a test that left them running would hand the runner a scheduler that never
     * drains.
     */
    private suspend fun TestScope.withRouter(
        settings: FakeLinkSettings,
        body: suspend (TelemetrySourceRouter, RecordingTransport) -> Unit,
    ) {
        val transport = RecordingTransport()
        val router = TelemetrySourceRouter(
            settings = settings,
            mock = MockTelemetryDataSource(),
            wire = SerialTelemetryDataSource(
                transport = transport,
                dispatcher = dispatcher,
                clock = { now },
            ),
            dispatcher = dispatcher,
        )
        try {
            body(router, transport)
        } finally {
            router.disconnect()
            runCurrent()
        }
    }

    /**
     * Drains the dispatches waiting at the current virtual instant.
     *
     * Several times, because setting a mode starts a suspension chain — the collector wakes, the
     * old source is torn down, the new one is opened, the loops are launched — and each hop is
     * another dispatch. Deliberately not `advanceUntilIdle`: see the class note.
     */
    private fun TestScope.settle() = repeat(4) { runCurrent() }

    private fun settingsOn(mode: LinkMode, port: Int = 14_550) =
        FakeLinkSettings(LinkSettings(mode = mode, udpPort = port))

    // --- Which source answers ----------------------------------------------------------------

    @Test
    fun `in demo mode the simulator is the source and the wire is never touched`() =
        runTest(dispatcher) {
            withRouter(settingsOn(LinkMode.DEMO)) { router, transport ->
                router.connect()
                advanceUntilIdle()

                assertEquals(ConnectionState.CONNECTED, router.connectionStateFlow().first())
                assertEquals(0, transport.openCount)
                assertTrue(router.isAvailable)
            }
        }

    @Test
    fun `in udp mode the wire is the source and the simulator's controls go away`() =
        runTest(dispatcher) {
            withRouter(settingsOn(LinkMode.UDP)) { router, transport ->
                router.connect()
                settle()

                assertEquals(1, transport.openCount)
                assertFalse(router.isAvailable)
                assertTrue(router.scenarios.isEmpty())
            }
        }

    @Test
    fun `switching to udp mid flight closes the simulator and opens the wire`() =
        runTest(dispatcher) {
            val settings = settingsOn(LinkMode.DEMO)

            withRouter(settings) { router, transport ->
                router.connect()
                advanceUntilIdle()
                assertEquals(0, transport.openCount)

                settings.setMode(LinkMode.UDP)
                settle()

                assertEquals(1, transport.openCount)
                assertFalse(router.isAvailable)
            }
        }

    /**
     * The other half of the same rule, and the one that would be easy to get wrong.
     *
     * An operator who sets the mode and then closes the app has not asked for a link to be
     * opened; they have said what to open when the app starts one. Starting it here would mean
     * a socket bound from a settings change, with nothing owning it.
     */
    @Test
    fun `a mode chosen before anything was started starts nothing`() = runTest(dispatcher) {
        val settings = settingsOn(LinkMode.DEMO)

        withRouter(settings) { _, transport ->
            settings.setMode(LinkMode.UDP)
            settle()

            assertEquals(0, transport.openCount)
        }
    }

    /**
     * A port change matters to the socket, and only to the socket.
     *
     * The restart has to notice the port and not just the mode, or the screen would say one port
     * while the socket stayed bound to the one before it — which is exactly the failure
     * [LinkSettings] carries both fields in one object to prevent.
     *
     * `14_551` is not a meaningful port. It only has to differ from the one before it, because
     * what is under test is whether the change is noticed, not what is done with the number —
     * that is `UdpTelemetryTransportTest`'s job.
     */
    @Test
    fun `changing the port while the wire is running reopens it`() = runTest(dispatcher) {
        val settings = settingsOn(LinkMode.UDP, port = 14_550)

        withRouter(settings) { router, transport ->
            router.connect()
            settle()
            assertEquals(1, transport.openCount)

            settings.setUdpPort(14_551)
            settle()

            // Torn down and brought back up, in that order: a reopen that skipped the close
            // would leave the first socket bound and the app listening in two places.
            assertEquals(2, transport.openCount)
            assertEquals(1, transport.closeCount)
        }
    }

    @Test
    fun `a port change before the link starts opens nothing`() = runTest(dispatcher) {
        val settings = settingsOn(LinkMode.UDP, port = 14_550)

        withRouter(settings) { _, transport ->
            settings.setUdpPort(14_551)
            settle()

            assertEquals(0, transport.openCount)
        }
    }

    // --- What the dashboard reads ------------------------------------------------------------

    /**
     * The load-bearing detail from the router's own documentation.
     *
     * `DashboardViewModel` combines these two flows into the state the whole dashboard renders
     * from. If they stopped emitting outside demo mode, every screen would stall rather than
     * merely losing its scenario chips — which is why the delegation is unconditional and the
     * values are gated by `isAvailable` instead.
     */
    @Test
    fun `the scenario and speed flows keep answering outside demo mode`() = runTest(dispatcher) {
        withRouter(settingsOn(LinkMode.UDP)) { router, _ ->
            assertEquals(MockScenario.NORMAL_FLIGHT, router.activeScenario().first())
            assertEquals(1.0, router.speedMultiplier().first()!!, 0.0)
        }
    }

    @Test
    fun `isAvailable follows the mode as it changes`() = runTest(dispatcher) {
        val settings = settingsOn(LinkMode.DEMO)

        withRouter(settings) { router, _ ->
            assertTrue(router.isAvailable)
            assertEquals(MockScenario.entries.toList(), router.scenarios)

            settings.setMode(LinkMode.UDP)
            settle()
            assertFalse(router.isAvailable)
            assertTrue(router.scenarios.isEmpty())

            settings.setMode(LinkMode.DEMO)
            settle()
            assertTrue(router.isAvailable)
        }
    }

    /**
     * The simulator has no wire, and says so by saying nothing.
     *
     * Null rather than a `LinkHealth` of zeroes, because zeroes are a measurement. The
     * diagnostics screen renders the two differently, and that is the whole point of the
     * distinction — see `LinkHealthSource.NoLinkHealth`.
     */
    @Test
    fun `the simulator reports no link health rather than a healthy one`() = runTest(dispatcher) {
        withRouter(settingsOn(LinkMode.DEMO)) { router, _ ->
            assertNull(router.linkHealthFlow().first())
        }
    }

    /**
     * The wire's own account, routed through rather than replaced.
     *
     * A router that answered this itself would be a second opinion about the same wire, and the
     * diagnostics screen's whole argument is that there is only one.
     */
    @Test
    fun `link health comes from the wire once the wire is the source`() = runTest(dispatcher) {
        withRouter(settingsOn(LinkMode.UDP)) { router, transport ->
            router.connect()
            settle()

            // Nothing has arrived, so the wire has published nothing yet — which is exactly the
            // answer that distinguishes it from the simulator's flat null. The transport was
            // opened, so the question really did reach the wire.
            assertNull(router.linkHealthFlow().first())
            assertEquals(1, transport.openCount)
        }
    }
}
