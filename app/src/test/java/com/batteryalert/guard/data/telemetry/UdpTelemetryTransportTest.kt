package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.data.link.LinkMode
import com.batteryalert.guard.data.link.LinkSettings
import com.batteryalert.guard.data.link.LinkSettingsStore
import com.batteryalert.guard.data.telemetry.mavlink.MavlinkFrameParser
import com.batteryalert.guard.data.telemetry.mavlink.MavlinkMessageSpec
import com.batteryalert.guard.data.telemetry.mavlink.MavlinkTestFrames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The UDP transport, against a real socket on loopback.
 *
 * ### Why this file is the most valuable one in the transport layer
 *
 * `UsbSerialTransport` is three hundred lines that no test in this project can execute: it needs
 * an adapter, a driver and an aircraft, and no amount of care while writing it changes that. This
 * one is plain Java — a `DatagramSocket` needs no Android, no permission and no hardware — so the
 * bytes really are sent, really are received, and the assertions are about what came out of a
 * socket rather than about what a fake was handed.
 *
 * That distinction is the whole point. A test against a fake transport proves the data source
 * drives the interface correctly; it cannot prove the transport ever worked. This proves a frame
 * goes in one end and arrives out the other.
 *
 * ### Real time, not virtual
 *
 * `runBlocking` and real timeouts rather than `runTest` and a virtual clock. The work being
 * waited on is a blocking `receive()` on a kernel socket, which no test scheduler can advance —
 * and a virtual-time test whose clock only moves when the test scheduler is idle would fire its
 * timeout while the socket was still legitimately waiting. Wall-clock, on loopback, with a
 * generous ceiling.
 */
class UdpTelemetryTransportTest {

    /**
     * A store with no disk behind it.
     *
     * The real store is SharedPreferences, which needs a `Context`; nothing above it needs
     * Android, and the transport only ever reads `.value`.
     */
    private class FakeLinkSettings(initial: LinkSettings) : LinkSettingsStore {
        private val state = MutableStateFlow(initial)
        override val settings: StateFlow<LinkSettings> = state.asStateFlow()
        override fun setMode(mode: LinkMode) { state.value = state.value.copy(mode = mode) }
        override fun setUdpPort(port: Int) { state.value = state.value.copy(udpPort = port) }
    }

    private fun transportOn(port: Int): UdpTelemetryTransport = UdpTelemetryTransport(
        settings = FakeLinkSettings(LinkSettings(mode = LinkMode.UDP, udpPort = port)),
        dispatcher = Dispatchers.IO,
    )

    /**
     * Ports the OS just handed out and that nothing is holding any more.
     *
     * All requested in one go, while every socket is still open, so they are distinct. A loop
     * that opened and closed one at a time could be handed the same number twice, and a test
     * that then bound both would fail for a reason that has nothing to do with the code under
     * test.
     */
    private fun freePorts(count: Int): List<Int> {
        val sockets = List(count) { DatagramSocket(0) }
        val ports = sockets.map { it.localPort }
        sockets.forEach { it.close() }
        return ports
    }

    private fun heartbeatFrame(): ByteArray = MavlinkTestFrames.frame(
        messageId = MavlinkMessageSpec.ID_HEARTBEAT,
        payload = MavlinkTestFrames.heartbeat(armed = true),
    )

    private fun sysStatusFrame(): ByteArray = MavlinkTestFrames.frame(
        messageId = MavlinkMessageSpec.ID_SYS_STATUS,
        payload = MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
    )

    /**
     * Opens a collector, sends [datagrams] to [port], and returns what came out.
     *
     * The collector is started *before* anything is sent. A datagram that arrives at a bound
     * socket is queued by the kernel, so this ordering is not strictly required — but starting
     * the flow first is what makes the assertion about the transport rather than about how
     * quickly the test got to the send.
     */
    private suspend fun exchange(
        transport: UdpTelemetryTransport,
        port: Int,
        datagrams: List<ByteArray>,
    ): List<ByteArray> = coroutineScope {
        val collected = async(Dispatchers.IO) {
            withTimeout(RECEIVE_TIMEOUT_MILLIS) { transport.incoming().take(datagrams.size).toList() }
        }

        val sender = DatagramSocket()
        try {
            datagrams.forEach { bytes ->
                sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), port))
            }
        } finally {
            sender.close()
        }

        collected.await()
    }

    // --- Receiving ---------------------------------------------------------------------------

    @Test
    fun `a datagram sent to the bound port arrives as bytes`() = runBlocking {
        val port = freePorts(1).single()
        val transport = transportOn(port)
        transport.open()
        try {
            // `0xFD` is MAVLink v2's magic byte, so these read as the start of a real frame.
            // The `.toByte()` is required rather than cosmetic: `0xFD` is 253, which does not fit
            // a signed Byte, and `byteArrayOf` takes Bytes.
            val sent = byteArrayOf(0xFD.toByte(), 0x09, 0x00, 0x00, 0x2A)

            assertArrayEquals(sent, exchange(transport, port, listOf(sent)).single())
        } finally {
            transport.close()
        }
    }

    /**
     * The case a transport that assumed one datagram equals one frame would fail silently on.
     *
     * Datagram boundaries are not message boundaries. A sender that batches its stream into one
     * packet is behaving perfectly normally, and a transport that dropped everything after the
     * first message would look like a link that decodes *something* — the worst kind of broken.
     */
    @Test
    fun `two frames in one datagram are both decoded`() = runBlocking {
        val port = freePorts(1).single()
        val transport = transportOn(port)
        transport.open()
        try {
            val both = heartbeatFrame() + sysStatusFrame()

            val received = exchange(transport, port, listOf(both)).single()
            val frames = MavlinkFrameParser().feed(received)

            assertEquals(
                listOf(MavlinkMessageSpec.ID_HEARTBEAT, MavlinkMessageSpec.ID_SYS_STATUS),
                frames.map { it.messageId },
            )
        } finally {
            transport.close()
        }
    }

    /**
     * And the case the other kind of transport would fail on, which for UDP is just as ordinary.
     *
     * Nothing about a datagram says it holds a whole frame. This is the assertion that the
     * transport forwards chunks rather than messages — the contract `TelemetryTransport` states
     * in as many words, and the reason a streaming parser rather than a per-packet one sits
     * above it.
     */
    @Test
    fun `a frame split across two datagrams is decoded once, whole`() = runBlocking {
        val port = freePorts(1).single()
        val transport = transportOn(port)
        transport.open()
        try {
            val frame = heartbeatFrame()
            val first = frame.copyOfRange(0, 8)
            val second = frame.copyOfRange(8, frame.size)

            val received = exchange(transport, port, listOf(first, second))
            assertEquals(2, received.size)

            // Fed to one parser in arrival order, because that is what the data source does with
            // them: two emissions, one frame, no loss at the seam.
            val parser = MavlinkFrameParser()
            val decoded = parser.feed(received[0]) + parser.feed(received[1])

            assertEquals(1, decoded.size)
            assertEquals(MavlinkMessageSpec.ID_HEARTBEAT, decoded.single().messageId)
        } finally {
            transport.close()
        }
    }

    /**
     * A datagram bigger than anything this app parses still comes back whole.
     *
     * `DatagramPacket` truncates to the receive buffer silently — no exception, no partial-read
     * flag, nothing. Against a buffer sized for one MAVLink frame the overflow would surface as
     * a checksum failure, which points at the radio, the aircraft or the encoder and at nothing
     * to do with buffer sizes. 3 000 bytes is not a frame and never will be; it is here to be
     * larger than the obvious wrong answer.
     */
    @Test
    fun `a datagram larger than a frame is not silently truncated`() = runBlocking {
        val port = freePorts(1).single()
        val transport = transportOn(port)
        transport.open()
        try {
            val payload = ByteArray(3_000) { index -> (index % 251).toByte() }

            assertArrayEquals(payload, exchange(transport, port, listOf(payload)).single())
        } finally {
            transport.close()
        }
    }

    // --- Binding -----------------------------------------------------------------------------

    /**
     * The failure `SO_REUSEADDR` would have hidden.
     *
     * With it, a second process binds the same port successfully and the kernel splits the
     * datagrams between them arbitrarily — so a stale instance of this app would take half the
     * telemetry, and the symptom would be intermittent frame loss that looks exactly like a bad
     * radio link. Without it the second bind fails, the operator is told the port is in use, and
     * that is both true and actionable.
     */
    @Test
    fun `a port that is already bound is refused rather than shared`() = runBlocking {
        val port = freePorts(1).single()
        val first = transportOn(port)
        first.open()
        try {
            val failure = runCatching { transportOn(port).open() }.exceptionOrNull()

            // BindException on every platform this runs on, but it is asserted as IOException
            // rather than by name: the class is an implementation detail of the JDK's socket
            // layer and a test that fails on a different JDK for a wording reason is not
            // testing the transport.
            assertTrue("expected the second bind to fail, got $failure", failure is IOException)
        } finally {
            first.close()
        }
    }

    @Test
    fun `reopening reads the port again and releases the one before it`() = runBlocking {
        // The retry loop reopens without a close in between, so a transport that failed to
        // release here would leak a file descriptor on every trip round it — and a link that
        // keeps dropping is exactly when the loop is going fastest.
        val (firstPort, secondPort) = freePorts(2)
        val store = FakeLinkSettings(LinkSettings(mode = LinkMode.UDP, udpPort = firstPort))
        val transport = UdpTelemetryTransport(settings = store, dispatcher = Dispatchers.IO)

        transport.open()
        assertEquals("udp 0.0.0.0:$firstPort", transport.name)

        store.setUdpPort(secondPort)
        transport.open()
        try {
            assertEquals("udp 0.0.0.0:$secondPort", transport.name)

            // The first port is free again, so something else can have it. Holding it would be
            // the leak, and it is invisible from the outside until the app has been running for
            // an hour.
            val reuser = DatagramSocket(null)
            try {
                reuser.bind(InetSocketAddress(firstPort))
            } finally {
                reuser.close()
            }
        } finally {
            transport.close()
        }
    }

    @Test
    fun `the name reports what is bound, and says so when nothing is`() = runBlocking {
        val port = freePorts(1).single()
        val transport = transportOn(port)

        // Before binding it says so, rather than naming a port it is not on. "Which port did it
        // actually open" is the first question when nothing arrives.
        assertEquals("udp (not bound)", transport.name)

        transport.open()
        try {
            assertEquals("udp 0.0.0.0:$port", transport.name)
        } finally {
            transport.close()
        }

        assertEquals("udp (not bound)", transport.name)
    }

    // --- Closing -----------------------------------------------------------------------------

    /**
     * Closing the socket is what unblocks the reader.
     *
     * `DatagramSocket.receive()` blocks, and cancelling the coroutine parked in it does not
     * interrupt it — a test that relied on cancellation alone would hang here and take the build
     * with it. This is the assertion behind `awaitClose` closing the socket, and it is the
     * reason the read loop's exit is a completion rather than a cancellation.
     */
    @Test
    fun `closing while a read is parked ends the flow instead of hanging it`() = runBlocking {
        val port = freePorts(1).single()
        val transport = transportOn(port)
        transport.open()

        val collected = async(Dispatchers.IO) {
            withTimeout(RECEIVE_TIMEOUT_MILLIS) { transport.incoming().toList() }
        }

        // Long enough for the reader to park in receive(). Nothing is sent, so the only thing
        // that can end the collection is the close below.
        delay(PARKED_MILLIS)
        transport.close()

        assertEquals(emptyList<ByteArray>(), collected.await())
    }

    @Test
    fun `closing a transport that was never opened is not an error`() = runBlocking {
        // The retry loop tears the link down on the way through, including after an open that
        // failed. Raising here would turn an ordinary unreachable adapter into a crash.
        transportOn(freePorts(1).single()).close()
    }

    @Test
    fun `reading before opening fails loudly rather than reporting an empty link`() = runBlocking {
        val transport = transportOn(freePorts(1).single())

        val failure = runCatching { transport.incoming().first() }.exceptionOrNull()

        assertTrue("expected a misuse failure, got $failure", failure is IllegalStateException)
    }

    private companion object {
        /** Generous: loopback delivery is microseconds, and a slow CI machine is not a bug. */
        const val RECEIVE_TIMEOUT_MILLIS = 5_000L

        /** How long the reader is given to reach the blocking call before it is closed. */
        const val PARKED_MILLIS = 200L
    }
}
