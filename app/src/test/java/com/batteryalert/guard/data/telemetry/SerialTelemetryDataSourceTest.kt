package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.data.telemetry.mavlink.CellDataTrust
import com.batteryalert.guard.data.telemetry.mavlink.MavlinkMessageSpec
import com.batteryalert.guard.data.telemetry.mavlink.MavlinkTestFrames
import com.batteryalert.guard.domain.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link's own behaviour: connecting, going quiet, and noticing.
 *
 * This is the class that decides whether the dashboard says "connected", and everything else
 * in the app can be right while this reports a health that is not there. A frozen reading is
 * more dangerous than a blank one — the dashboard shows the operator a pack that looks like it
 * is holding steady — so the silence path gets its own tests rather than being left to the
 * parser and assembler suites, neither of which touches it.
 *
 * Both the thread and the clock are injected, so "the link went silent three seconds ago" is
 * an assertion rather than a sleep. Nothing here reads the wall clock.
 *
 * ### Why every test closes the link
 *
 * The read and watchdog loops are infinite by design, and the watchdog re-arms a timer on every
 * tick. A test that leaves them running hands the test runner a scheduler that never drains.
 * [withLink] disconnects in a `finally` so that a failing assertion cannot turn into a hung
 * build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerialTelemetryDataSourceTest {

    private val dispatcher = StandardTestDispatcher()

    /** A clock this test moves by hand, so silence is an input rather than an elapsed wait. */
    private var now = 1_000_000L

    private fun source(transport: TelemetryTransport) = SerialTelemetryDataSource(
        transport = transport,
        dispatcher = dispatcher,
        clock = { now },
    )

    /** Runs [body] against a connected-capable link and always closes it afterwards. */
    private suspend fun withLink(
        transport: FakeTransport = FakeTransport(),
        body: suspend (SerialTelemetryDataSource) -> Unit,
    ) {
        val link = source(transport)
        try {
            body(link)
        } finally {
            link.disconnect()
        }
    }

    /** Lets the read loop collect whatever the transport has already been handed. */
    private fun TestScope.settle() = runCurrent()

    /** Lets the watchdog tick at least twice, so its verdict is not a race with the clock. */
    private fun TestScope.letTheWatchdogRun() {
        advanceTimeBy(SerialTelemetryDataSource.WATCHDOG_INTERVAL_MILLIS * 2)
        runCurrent()
    }

    private suspend fun SerialTelemetryDataSource.connection() = connectionStateFlow().first()

    private suspend fun SerialTelemetryDataSource.telemetry() = telemetryFlow().first()

    private suspend fun SerialTelemetryDataSource.gps() = gpsFlow().first()

    private fun heartbeatFrame() = MavlinkTestFrames.frame(
        messageId = MavlinkMessageSpec.ID_HEARTBEAT,
        payload = MavlinkTestFrames.heartbeat(armed = true),
    )

    private fun sysStatusFrame() = MavlinkTestFrames.frame(
        messageId = MavlinkMessageSpec.ID_SYS_STATUS,
        payload = MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
    )

    // --- Connecting -------------------------------------------------------------------------

    @Test
    fun `a port that will not open reports an error rather than a dead link`() = runTest(dispatcher) {
        val transport = FakeTransport(openFailure = IllegalStateException("no adapter"))
        withLink(transport) { link ->
            link.connect()
            settle()

            // ERROR, not DISCONNECTED. "The cable is not plugged in" and "we have never tried"
            // need different words on the dashboard and they arrive through different paths.
            assertEquals(ConnectionState.ERROR, link.connection())

            // And not RECONNECTING, either. This is the app launching with the adapter still
            // unplugged: somebody is holding the tablet and setting the aircraft up, so the
            // useful answer is the fault itself rather than a hopeful "still trying".
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals("a first failure is the operator's to fix, not the app's", 1, transport.openCount)
            assertEquals(ConnectionState.ERROR, link.connection())
        }
    }

    @Test
    fun `the link is connected once bytes arrive`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            assertEquals(ConnectionState.CONNECTING, link.connection())

            transport.emit(heartbeatFrame())
            settle()

            assertEquals(ConnectionState.CONNECTED, link.connection())
        }
    }

    @Test
    fun `bytes arriving is enough to call the link connected even if nothing decodes`() =
        runTest(dispatcher) {
            val transport = FakeTransport()
            withLink(transport) { link ->
                link.connect()
                settle()
                transport.emit(ByteArray(64) { 0x11 })
                settle()

                // The cable works. Whatever is wrong is the dialect, and calling this a link
                // failure would send the operator to check wiring that is fine.
                assertEquals(ConnectionState.CONNECTED, link.connection())
                assertTrue(link.linkHealth().bytesArriving)
                assertEquals(0, link.linkHealth().framesDecoded)
            }
        }

    @Test
    fun `reconnecting while already connected is ignored`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            link.connect()
            settle()

            assertEquals(1, transport.openCount)
        }
    }

    // --- Values -----------------------------------------------------------------------------

    @Test
    fun `decoded messages reach the flows the dashboard reads`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.emit(sysStatusFrame())
            transport.emit(
                MavlinkTestFrames.frame(
                    messageId = MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
                    payload = MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
                ),
            )
            settle()

            assertEquals(50.4, link.telemetry().totalVoltage!!, 0.0001)
            assertEquals(74, link.telemetry().batteryPercentage)
            assertEquals(53.81145, link.gps().latitude!!, 0.000001)
            assertTrue(link.gps().hasFix)
        }
    }

    @Test
    fun `a frame split across two reads still decodes`() = runTest(dispatcher) {
        val transport = FakeTransport()
        val frame = sysStatusFrame()

        withLink(transport) { link ->
            link.connect()
            settle()
            transport.emit(frame.copyOfRange(0, 10))
            settle()
            assertNull(link.telemetry().totalVoltage)

            transport.emit(frame.copyOfRange(10, frame.size))
            settle()

            assertEquals(50.4, link.telemetry().totalVoltage!!, 0.0001)
        }
    }

    // --- Going quiet ------------------------------------------------------------------------

    @Test
    fun `a silent link is reported as an error and its readings are dropped`() =
        runTest(dispatcher) {
            val transport = FakeTransport()
            withLink(transport) { link ->
                link.connect()
                settle()
                transport.emit(sysStatusFrame())
                settle()
                assertEquals(50.4, link.telemetry().totalVoltage!!, 0.0001)

                // Nothing more arrives.
                now += SerialTelemetryDataSource.LINK_SILENCE_MILLIS + 1
                letTheWatchdogRun()

                assertEquals(ConnectionState.ERROR, link.connection())
                // The part that matters. A frozen 50.4 V reads as a healthy pack holding
                // steady, and it is worse than showing nothing at all.
                assertNull(link.telemetry().totalVoltage)
                assertNull(link.telemetry().batteryPercentage)
                assertFalse(link.linkHealth().bytesArriving)
            }
        }

    @Test
    fun `a port that opens but never speaks is reported as an error`() = runTest(dispatcher) {
        withLink { link ->
            link.connect()
            settle()
            assertEquals(ConnectionState.CONNECTING, link.connection())

            // Silence before the first byte is measured from the port opening, not from
            // nothing — a port that has produced nothing for no time at all is not healthy.
            now += SerialTelemetryDataSource.LINK_SILENCE_MILLIS + 1
            letTheWatchdogRun()

            assertEquals(ConnectionState.ERROR, link.connection())
        }
    }

    @Test
    fun `the link recovers on its own when bytes start again`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.emit(heartbeatFrame())
            settle()

            now += SerialTelemetryDataSource.LINK_SILENCE_MILLIS + 1
            letTheWatchdogRun()
            assertEquals(ConnectionState.ERROR, link.connection())

            transport.emit(heartbeatFrame())
            letTheWatchdogRun()

            // A dropout that lasts a moment must not need the operator to intervene.
            assertEquals(ConnectionState.CONNECTED, link.connection())
        }
    }

    @Test
    fun `readings come back after the link does`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.emit(sysStatusFrame())
            settle()

            now += SerialTelemetryDataSource.LINK_SILENCE_MILLIS + 1
            letTheWatchdogRun()
            assertNull(link.telemetry().totalVoltage)

            transport.emit(sysStatusFrame())
            settle()

            assertEquals(50.4, link.telemetry().totalVoltage!!, 0.0001)
        }
    }

    @Test
    fun `a port that has opened but said nothing is not called connected`() = runTest(dispatcher) {
        withLink { link ->
            link.connect()
            settle()

            // One watchdog tick, and the link is still well inside the silence window — so the
            // watchdog has nothing to complain about. It must not swing the other way either:
            // an open port that has produced no bytes is a connection being attempted, not a
            // connection, and calling it "Link up" would be the same healthy-but-quiet lie the
            // silence check exists to prevent, just earlier.
            letTheWatchdogRun()

            assertEquals(ConnectionState.CONNECTING, link.connection())
        }
    }

    @Test
    fun `a stream that ends without being asked is reopened`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.emit(heartbeatFrame())
            settle()
            assertEquals(ConnectionState.CONNECTED, link.connection())

            // A serial port does not end its stream to say goodbye.
            transport.endStream()
            settle()

            // RECONNECTING, not ERROR. The cable has been knocked loose mid-flight and the
            // operator's hands are on the sticks: the app has to fix this on its own, and
            // saying so is more use than a fault code nobody can act on.
            assertEquals(ConnectionState.RECONNECTING, link.connection())

            advanceTimeBy(SerialTelemetryDataSource.INITIAL_RETRY_MILLIS)
            runCurrent()

            assertEquals("the port is opened again with nobody touching the tablet", 2, transport.openCount)

            transport.emit(heartbeatFrame())
            settle()
            settle()

            assertEquals(ConnectionState.CONNECTED, link.connection())
        }
    }

    @Test
    fun `a transport that throws mid-stream is brought back`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.fail(IllegalStateException("adapter unplugged"))
            settle()

            assertEquals(ConnectionState.RECONNECTING, link.connection())

            advanceTimeBy(SerialTelemetryDataSource.INITIAL_RETRY_MILLIS)
            runCurrent()

            assertEquals(2, transport.openCount)
            assertFalse(
                "an open port with no bytes is not a link that is arriving",
                link.linkHealth().bytesArriving,
            )
        }
    }

    @Test
    fun `reopen attempts back off instead of hammering the port`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.endStream()
            settle()
            assertEquals(ConnectionState.RECONNECTING, link.connection())

            // From here the port never comes back, so the loop never gets past the first try.
            transport.openFailure = IllegalStateException("still gone")

            advanceTimeBy(SerialTelemetryDataSource.INITIAL_RETRY_MILLIS)
            runCurrent()
            assertEquals(1, link.linkHealth().reconnectAttempts)

            // Halfway into the *second* wait. If the gap had not doubled, this window would
            // have bought another attempt, and the assertion would read 2.
            advanceTimeBy(SerialTelemetryDataSource.INITIAL_RETRY_MILLIS)
            runCurrent()
            assertEquals("a grown gap means the shorter window buys nothing", 1, link.linkHealth().reconnectAttempts)

            advanceTimeBy(SerialTelemetryDataSource.INITIAL_RETRY_MILLIS)
            runCurrent()
            assertEquals(2, link.linkHealth().reconnectAttempts)
        }
    }

    @Test
    fun `a port that never comes back is still being tried`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.endStream()
            settle()
            transport.openFailure = IllegalStateException("still gone")

            // One window per attempt: 250, 500, 1000, 2000, then the ceiling of 5000 for
            // every attempt after it. The last two being the same size is what "the gap
            // stopped growing" means.
            listOf(250L, 500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L).forEach { gap ->
                advanceTimeBy(gap)
                runCurrent()
            }

            assertEquals(7, link.linkHealth().reconnectAttempts)

            // Seven failed attempts and roughly eighteen seconds of trying, and the honest
            // answer is still that the app is working on it. There is no number of failures
            // after which giving up becomes correct on an aircraft that is still in the air.
            assertEquals(ConnectionState.RECONNECTING, link.connection())
            assertNull(link.telemetry().totalVoltage)
        }
    }

    @Test
    fun `a link that recovers forgets how long it took`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.endStream()
            settle()

            transport.openFailure = IllegalStateException("still gone")
            advanceTimeBy(SerialTelemetryDataSource.INITIAL_RETRY_MILLIS)
            runCurrent()
            advanceTimeBy(SerialTelemetryDataSource.INITIAL_RETRY_MILLIS * 2)
            runCurrent()
            assertEquals(2, link.linkHealth().reconnectAttempts)

            transport.openFailure = null
            advanceTimeBy(SerialTelemetryDataSource.MAX_RETRY_MILLIS)
            runCurrent()
            settle()

            transport.emit(heartbeatFrame())
            settle()

            assertEquals(ConnectionState.CONNECTED, link.connection())
            // A count that never came back down would make every reading after the recovery
            // look suspect, when in fact the link is as good as it was before the dropout.
            assertEquals(0, link.linkHealth().reconnectAttempts)
        }
    }

    @Test
    fun `disconnecting stops the retry loop`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.endStream()
            settle()
            assertEquals(ConnectionState.RECONNECTING, link.connection())

            link.disconnect()
            settle()
            assertEquals(ConnectionState.DISCONNECTED, link.connection())

            val opensWhenTheOperatorClosedIt = transport.openCount
            advanceTimeBy(60_000)
            runCurrent()

            // Closing the port is the operator's decision. An app that quietly reopened it a
            // quarter of a second later would be undoing that, and would do it forever.
            assertEquals(opensWhenTheOperatorClosedIt, transport.openCount)
            assertEquals(ConnectionState.DISCONNECTED, link.connection())
        }
    }

    // --- Disconnecting ----------------------------------------------------------------------

    @Test
    fun `disconnecting is not an error`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.emit(sysStatusFrame())
            settle()

            link.disconnect()
            settle()

            assertEquals(ConnectionState.DISCONNECTED, link.connection())
            assertNull(link.telemetry().totalVoltage)
            assertTrue(transport.closed)
        }
    }

    @Test
    fun `a half frame from the previous link does not break the next one`() = runTest(dispatcher) {
        val transport = FakeTransport()
        val frame = sysStatusFrame()

        withLink(transport) { link ->
            link.connect()
            settle()
            // Two thirds of a frame, then the cable is pulled. The remainder never comes, so
            // the parser is holding a fragment that has no continuation on the new link.
            transport.emit(frame.copyOfRange(0, frame.size - 4))
            settle()
            link.disconnect()
            settle()

            link.connect()
            settle()
            // A whole, valid frame. If the fragment survived, this fails its checksum.
            transport.emit(frame)
            settle()

            assertEquals(50.4, link.telemetry().totalVoltage!!, 0.0001)
        }
    }

    @Test
    fun `values from the previous connection are not carried into the next one`() =
        runTest(dispatcher) {
            val transport = FakeTransport()
            withLink(transport) { link ->
                link.connect()
                settle()
                transport.emit(sysStatusFrame())
                settle()
                link.disconnect()
                settle()

                link.connect()
                settle()

                // A pack voltage from a link that no longer exists would be presented as
                // current.
                assertNull(link.telemetry().totalVoltage)
                assertNull(link.telemetry().batteryPercentage)
            }
        }

    // --- Link health ------------------------------------------------------------------------

    @Test
    fun `link health counts what was decoded and what was thrown away`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.emit(sysStatusFrame())
            transport.emit(byteArrayOf(0x00, 0x12, 0x34))
            settle()

            val health = link.linkHealth()

            assertEquals(1, health.framesDecoded)
            assertEquals(3, health.bytesDiscarded)
            // Nothing has claimed to report cells, so there is nothing to corroborate.
            assertEquals(CellDataTrust.NOT_REPORTED, health.cellDataTrust)
            assertTrue(health.bytesArriving)
            assertFalse(health.isClean)
        }
    }

    @Test
    fun `an empty link reports no health to speak of`() = runTest(dispatcher) {
        val link = source(FakeTransport())

        assertEquals(0, link.linkHealth().framesDecoded)
        assertEquals(CellDataTrust.NOT_REPORTED, link.linkHealth().cellDataTrust)
        assertFalse(link.linkHealth().bytesArriving)
        assertTrue(link.linkHealth().isClean)
    }

    @Test
    fun `the health a screen can follow is the health a caller can read`() = runTest(dispatcher) {
        val transport = FakeTransport()
        withLink(transport) { link ->
            link.connect()
            settle()
            transport.emit(sysStatusFrame())
            settle()

            // The diagnostics screen reads the flow and everything else reads the getter. Two
            // readings of the same link that could disagree would be worse than either alone.
            assertEquals(link.linkHealth(), link.linkHealthFlow().first())
            assertEquals(1, link.linkHealthFlow().first()?.framesDecoded)
        }
    }

    @Test
    fun `a link that has not looked yet reports nothing on the flow`() = runTest(dispatcher) {
        val link = source(FakeTransport())

        // Distinct from the getter, which answers with an all-zero LinkHealth. The screen has
        // to be able to tell "nothing has been measured" from "the measurement was zero", and
        // only the flow carries that.
        assertNull(link.linkHealthFlow().first())
    }

    /**
     * A transport that hands the test the taps.
     *
     * Deliberately not a mock library: the interesting behaviour is *when* bytes arrive
     * relative to what the watchdog is doing, and that is easier to read as a channel the test
     * pushes into than as a sequence of recorded interactions.
     */
    private class FakeTransport(
        /**
         * Makes [open] fail. Settable after construction so a test can let a link connect
         * normally, die, and *then* decide that the port never comes back.
         */
        var openFailure: Throwable? = null,
    ) : TelemetryTransport {

        private var chunks = Channel<ByteArray>(Channel.UNLIMITED)

        var openCount = 0
            private set

        var closed = false
            private set

        override val name: String = "fake"

        /**
         * Hands out a fresh byte stream, the way a real port does.
         *
         * Reusing one channel across connections would make a reopened link read from a stream
         * that [close] had already ended, so a reconnect would always come back empty and no
         * test about reconnecting could pass however correct the source was.
         */
        override suspend fun open() {
            openCount++
            openFailure?.let { throw it }
            chunks = Channel(Channel.UNLIMITED)
        }

        override fun incoming(): Flow<ByteArray> = chunks.consumeAsFlow()

        override suspend fun close() {
            closed = true
            chunks.close()
        }

        fun emit(bytes: ByteArray) {
            chunks.trySend(bytes)
        }

        /** Ends the stream the way a broken adapter does: quietly, with no error. */
        fun endStream() {
            chunks.close()
        }

        fun fail(cause: Throwable) {
            chunks.close(cause)
        }
    }
}
