package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.data.link.LinkSettingsStore
import com.batteryalert.guard.di.TelemetryDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MAVLink arriving over UDP, on the port the operator configured.
 *
 * ### The good news about this file
 *
 * It is the second implementation of [TelemetryTransport], and unlike the first it has no
 * Android dependency at all: a [DatagramSocket] is plain Java, so this class runs in a JVM unit
 * test on `127.0.0.1` with no device, no adapter and no aircraft. `UsbSerialTransport` cannot
 * make that claim and probably never will. Sending a real MAVLink frame to a real socket and
 * watching it come out the other side is the closest this project gets to proving a transport
 * works, and it is worth more than any amount of care taken over the untestable one.
 *
 * ### It listens; it does not ask
 *
 * The app binds a port and waits. It never sends: no heartbeat, no stream request, no
 * acknowledgement. That keeps the receive-only contract [TelemetryTransport] states, and it is
 * the correct model for a datalink or companion computer that is already broadcasting.
 *
 * It is also the model's one limitation, and the reason it is written down here rather than
 * discovered later: some links only start streaming once they have heard from a ground station,
 * because they need an address to reply to. Against those, this class binds successfully and
 * then reports silence forever, and the fix is a write path that does not exist yet. The
 * dashboard's two-second watchdog will say so rather than sitting on a frozen reading.
 *
 * ### Why the socket is not opened with SO_REUSEADDR
 *
 * Because the failure it hides is worse than the failure it prevents. With it, two processes can
 * bind the same port and the kernel hands each of them an arbitrary subset of the datagrams —
 * so a stale instance of this app left running would take half the telemetry and the symptom
 * would be intermittent frame loss, which looks exactly like a bad radio link and would send
 * somebody up a mast. Without it, the second bind fails loudly and the operator is told the port
 * is in use, which is true and actionable.
 *
 * ### Why the buffer is the size it is
 *
 * A datagram larger than the receive buffer is truncated *silently* by [DatagramPacket] — the
 * rest is discarded and no error is raised from anywhere. That would surface as a checksum
 * failure, which points at the radio, the aircraft or the encoder, and at nothing to do with
 * buffer sizes. One 64 KiB array removes the possibility at a cost of nothing worth measuring.
 */
@Singleton
class UdpTelemetryTransport @Inject constructor(
    private val settings: LinkSettingsStore,
    @TelemetryDispatcher private val dispatcher: CoroutineDispatcher,
) : TelemetryTransport {

    private var socket: DatagramSocket? = null

    /**
     * Shown on the connection card.
     *
     * The bound address is included because "which port did it actually open" is the first
     * question when nothing arrives, and "udp" on its own would send the operator to the Link
     * screen to find out something this class already knows.
     */
    override val name: String
        get() = socket?.let { "udp 0.0.0.0:${it.localPort}" } ?: "udp (not bound)"

    /**
     * Binds the configured port.
     *
     * ### Why this releases before it acquires
     *
     * Same reason as the USB transport's: nothing calls [close] between a link dropping and the
     * retry loop reopening it, so a socket left over from the previous attempt would leak a file
     * descriptor on every reopening — and the retry loop reopens on every trip round it.
     */
    override suspend fun open() {
        withContext(dispatcher) {
            release()

            val port = settings.settings.value.udpPort
            // The two-argument constructor is not used because it binds as a side effect of
            // construction, which leaves a socket that failed to bind impossible to close
            // cleanly — there is no reference to it and no way to distinguish it from one that
            // was never created.
            val bound = DatagramSocket(null)
            try {
                bound.bind(InetSocketAddress(port))
            } catch (failure: Exception) {
                runCatching { bound.close() }
                throw failure
            }
            socket = bound
        }
    }

    override fun incoming(): Flow<ByteArray> = callbackFlow {
        val active = socket ?: error("The transport was read before it was opened.")

        // Hoisted out of the loop deliberately: one array for the life of the link, reused for
        // every datagram. Allocating per packet would produce garbage at whatever rate the
        // aircraft is sending, and none of it would be worth collecting.
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)

        val reader = launch {
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    active.receive(packet)
                } catch (_: Exception) {
                    // The socket was closed underneath us. That is either this flow being
                    // cancelled or a genuine fault, and both end the read. Which one it was is
                    // decided by the caller — a cancelled collect is an operator closing the
                    // link, and a completed one is the retry loop's cue to reopen.
                    break
                }
                // Copied out before the next receive overwrites the buffer.
                trySend(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
            }
            close()
        }

        awaitClose {
            reader.cancel()
            // This, not the cancel above, is what actually unblocks receive(). Cancelling a
            // coroutine parked in a blocking socket call does not interrupt it; closing the
            // socket makes the call throw and the loop unwind.
            runCatching { active.close() }
        }
    }.flowOn(dispatcher)

    override suspend fun close() {
        withContext(dispatcher) { release() }
    }

    private fun release() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        /**
         * The largest payload a UDP datagram can carry, rounded up.
         *
         * Nothing this app parses comes close — a MAVLink v2 frame is 280 bytes at its longest —
         * but a datagram may carry several frames, and the cost of being wrong is a silent
         * truncation that presents as a checksum failure somewhere else entirely.
         */
        internal const val MAX_DATAGRAM_BYTES = 65_535
    }
}
