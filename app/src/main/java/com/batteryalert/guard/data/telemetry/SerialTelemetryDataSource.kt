package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.data.telemetry.mavlink.CellDataTrust
import com.batteryalert.guard.data.telemetry.mavlink.LinkHealth
import com.batteryalert.guard.data.telemetry.mavlink.MavlinkFrameParser
import com.batteryalert.guard.data.telemetry.mavlink.MavlinkTelemetryAssembler
import com.batteryalert.guard.di.TelemetryClock
import com.batteryalert.guard.di.TelemetryDispatcher
import com.batteryalert.guard.domain.model.BatteryTelemetry
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.model.FlightState
import com.batteryalert.guard.domain.model.GpsData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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

/**
 * A [TelemetryDataSource] reading real MAVLink from a [TelemetryTransport].
 *
 * ### Wired up, and reachable
 *
 * This used to carry a note saying nothing bound it. That is no longer true: `TelemetrySourceRouter`
 * constructs it, and the Link screen decides at runtime whether it or the simulator is the source.
 * When it is, the operator's telemetry comes through here.
 *
 * Note the distinction between *bound* and *bound to the interface*. `TelemetryModule` binds
 * `TelemetrySourceRouter` to [TelemetryDataSource], not this class, because which of the two
 * sources is in use is chosen on the Link screen rather than by the build. The name of the class
 * is now the only thing here that describes a specific wire, and it is the wrong name — it reads
 * UDP just as happily, because everything above the transport is protocol-agnostic. The
 * transport underneath it is what varies: `SwitchableTelemetryTransport` picks between
 * `UdpTelemetryTransport` and `UsbSerialTransport` from the same setting.
 *
 * ### Two ways a link can be broken
 *
 * - **Silence.** No bytes at all. Caught by [watchdogLoop], which is the only thing standing
 *   between a dropped cable and a dashboard frozen on the last good reading. A frozen reading
 *   is worse than a blank one, so the watchdog also re-publishes the snapshot on every tick,
 *   which is what actually clears the stale values out of the UI.
 * - **Noise.** Bytes arriving, nothing decoding. Not a link failure at all — it is a dialect
 *   failure, and [LinkHealth] is what says so. Kept as CONNECTED on purpose: the cable works.
 *
 * ### Coming back from a dropout
 *
 * A streaming read ends when the adapter is unplugged and throws when it is yanked. Either
 * way the coroutine that was reading it is finished, and without [retryLoop] the app would
 * sit on ERROR for the rest of the flight even after the cable went back in. Nobody is
 * looking at the tablet when this happens — the hands are on the sticks — so the recovery has
 * to be automatic.
 *
 * Reopening is triggered by the read stream *ending*, not by silence. A silent link is
 * usually the radio, not the port: the cable is fine, the aircraft is not talking, and
 * closing and reopening a healthy USB port every few seconds would churn the one piece of the
 * chain that is still working. Silence is left to [watchdogLoop], which already recovers on
 * its own the moment bytes start again.
 *
 * ### The one failure that is not retried
 *
 * A failure to open the port on the very first [connect] is reported as ERROR and left there.
 * That is the app launching with the adapter not yet enumerated, which is a moment when
 * somebody is holding the tablet and can act on it. See the note in [connect].
 *
 * The cost of that choice is worth stating plainly: nothing in the app calls [connect] a
 * second time, so a first attempt that fails stays failed until the screen is reopened. When
 * the USB transport exists it should either enumerate before this is called or bring its own
 * attach broadcast, and this is the seam where that will land.
 */
@Singleton
class SerialTelemetryDataSource @Inject constructor(
    private val transport: TelemetryTransport,
    @TelemetryDispatcher private val dispatcher: CoroutineDispatcher,
    @TelemetryClock private val clock: () -> Long,
) : TelemetryDataSource, LinkHealthSource {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val parser = MavlinkFrameParser()
    private val assembler = MavlinkTelemetryAssembler()

    private val _telemetry = MutableStateFlow(BatteryTelemetry.EMPTY)
    private val _gps = MutableStateFlow(GpsData.EMPTY)
    private val _flight = MutableStateFlow(FlightState.EMPTY)
    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)

    private var readJob: Job? = null
    private var watchdogJob: Job? = null
    private var retryJob: Job? = null

    /** Milliseconds since the epoch of the last byte received, or 0 if none has been. */
    @Volatile
    private var lastByteAt: Long = 0L

    /** When the port opened, so silence can be measured before the first byte arrives. */
    @Volatile
    private var openedAt: Long = 0L

    /**
     * Failed attempts to reopen the port since the link last worked, for [LinkHealth].
     *
     * Reset by the first byte of a live connection rather than by a successful open: a port
     * that opens and stays silent has not recovered, and saying so would be the same
     * healthy-but-quiet lie this class exists to avoid.
     */
    @Volatile
    private var reconnectAttempts: Int = 0

    /**
     * The link's own account of itself.
     *
     * Null until the first [publish], which is what lets the diagnostics screen tell "this
     * source has not looked yet" from "this source looked and everything came back zero".
     *
     * A `StateFlow` rather than a plain field because it has to be two things at once: a
     * snapshot anyone can read synchronously, and a stream the diagnostics screen can follow
     * without polling.
     */
    private val _linkHealth = MutableStateFlow<LinkHealth?>(null)

    override fun telemetryFlow(): Flow<BatteryTelemetry> = _telemetry.asStateFlow()

    override fun gpsFlow(): Flow<GpsData> = _gps.asStateFlow()

    override fun flightStateFlow(): Flow<FlightState> = _flight.asStateFlow()

    override fun connectionStateFlow(): Flow<ConnectionState> = _connection.asStateFlow()

    /**
     * The link's current account of itself, for anything that wants the value rather than the
     * stream of it.
     *
     * A link that has not published yet reports [LinkHealth] with everything at zero — the
     * honest reading of a link that has done nothing. Callers that need to tell that apart
     * from a real measurement should collect [linkHealthFlow] instead, which stays null until
     * the first publish.
     */
    fun linkHealth(): LinkHealth = _linkHealth.value ?: LinkHealth()

    override fun linkHealthFlow(): Flow<LinkHealth?> = _linkHealth.asStateFlow()

    override suspend fun connect() {
        // A retry already in flight is a connect in progress. Starting a second one would
        // race two attempts for the same port, and the loser would close the winner's stream.
        if (readJob?.isActive == true || retryJob?.isActive == true) return

        _connection.value = ConnectionState.CONNECTING
        resetForNewLink()

        if (!openPort()) {
            // ERROR rather than RECONNECTING, and the difference is *who is watching*.
            //
            // This is the app starting up with the adapter already unplugged. Somebody is
            // holding the tablet and looking at the screen while they set the aircraft up, and
            // "Link error" is the actionable answer — the cable is the problem and only they
            // can fix it. A retry loop would replace that with a hopeful "Reconnecting" that
            // is true about the app and useless to the person.
            //
            // A dropout *after* the link has worked is the opposite situation, and it is the
            // one [retryLoop] exists for: the aircraft is in the air, nobody is looking at the
            // tablet, and there is no button anyone is going to press.
            _connection.value = ConnectionState.ERROR
            return
        }

        startLoops()
    }

    /**
     * Opens the port, reporting failure rather than throwing it.
     *
     * Cancellation is still thrown: a cancelled connect is not a failed one, and swallowing
     * it would leave the caller's coroutine running as though nothing had happened.
     */
    private suspend fun openPort(): Boolean = try {
        transport.open()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /**
     * Forgets everything the previous connection said.
     *
     * Both the parser and the assembler hold state belonging to a link that no longer exists.
     * A half-frame left in the parser would make the first frame of the new link fail for
     * reasons that have nothing to do with the new link, and a reading left in the assembler
     * would be presented as current while nothing is arriving.
     */
    private fun resetForNewLink() {
        parser.resetBuffer()
        assembler.reset()
        lastByteAt = 0L
        openedAt = now()
    }

    private fun startLoops() {
        readJob = scope.launch { readLoop() }
        watchdogJob = scope.launch { watchdogLoop() }
    }

    override suspend fun disconnect() {
        readJob?.cancel()
        watchdogJob?.cancel()
        retryJob?.cancel()
        readJob = null
        watchdogJob = null
        retryJob = null
        reconnectAttempts = 0

        try {
            transport.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Already gone. There is nothing left to fail at, and closing is not the moment
            // to raise an error the operator can do nothing about.
        }

        parser.resetBuffer()
        assembler.reset()
        publish()
        _connection.value = ConnectionState.DISCONNECTED
    }

    private suspend fun readLoop() {
        try {
            transport.incoming().collect { chunk ->
                lastByteAt = now()
                // A byte is the only proof a link exists, so it is also what ends a retry
                // cycle — not a successful open.
                reconnectAttempts = 0
                val frames = parser.feed(chunk)
                frames.forEach { frame -> assembler.onFrame(frame, now()) }
                publish()
                if (_connection.value != ConnectionState.CONNECTED &&
                    _connection.value != ConnectionState.DISCONNECTED
                ) {
                    // Covers the first connect and a recovery from a silent stretch alike.
                    // Never touches DISCONNECTED: the operator asking for the link to go
                    // away outranks a chunk that was already in flight when they asked.
                    _connection.value = ConnectionState.CONNECTED
                }
            }
            // A serial port does not end its stream to say goodbye. If this flow completes
            // without anyone asking it to, something broke.
            beginReconnect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            beginReconnect()
        }
    }

    /**
     * Starts the retry loop after the link died on its own.
     *
     * Deliberately does not cancel [readJob]: the only caller is [readLoop], which is the
     * coroutine that job represents and is about to return of its own accord. Cancelling it
     * from the inside would throw into the frame that is still unwinding.
     */
    private fun beginReconnect() {
        // A disconnect that raced the failure has already had the last word. Reopening a port
        // the operator just closed would undo their decision.
        if (_connection.value == ConnectionState.DISCONNECTED) return

        watchdogJob?.cancel()
        watchdogJob = null

        // The readings came from a link that no longer exists. Waiting for the silence window
        // would leave the last good voltage on screen beside a reconnecting badge — the exact
        // pairing the silence check exists to prevent, and no less wrong for being brief.
        assembler.reset()
        // Cleared alongside the readings so that LinkHealth does not report bytes arriving on
        // a link that has just ended: the last byte was microseconds ago, which satisfies the
        // silence window on a technicality and is exactly the wrong thing to tell the operator.
        lastByteAt = 0L
        _connection.value = ConnectionState.RECONNECTING
        publish()

        retryJob?.cancel()
        retryJob = scope.launch { retryLoop() }
    }

    /**
     * Keeps trying to bring the port back.
     *
     * Backs off so that an adapter left unplugged for an hour does not cost an open attempt
     * per second for an hour, and so that a device that fails to enumerate is not hammered
     * while it is still settling. The ceiling is low enough that replugging a cable mid-flight
     * is noticed within a few seconds.
     *
     * Unbounded on purpose. There is no number of failures after which the correct answer
     * becomes "stop trying" — the aircraft is in the air and the cable is the operator's
     * problem, not the app's. A hopeless link is still reported honestly while the app keeps
     * working on it: the state stays RECONNECTING, and [LinkHealth.reconnectAttempts] is what
     * says how hopeless.
     */
    private suspend fun retryLoop() {
        var delayMillis = INITIAL_RETRY_MILLIS
        while (currentCoroutineContext().isActive) {
            delay(delayMillis)

            resetForNewLink()
            if (openPort()) {
                // The port is back. That is a reason to expect bytes, not to claim them: the
                // state stays RECONNECTING until [readLoop] sees some.
                startLoops()
                return
            }

            reconnectAttempts++
            publish()
            delayMillis = (delayMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
        }
    }

    /**
     * Notices a link that has stopped talking.
     *
     * Nothing else in this class runs when bytes stop arriving, so without this the dashboard
     * would hold the last snapshot indefinitely.
     *
     * Re-running the snapshot on every tick is what expires individual *message types* on a
     * link that is still alive — the assembler times each one separately, and nothing else
     * would call it once the bytes stopped.
     *
     * Dropping the whole snapshot when the link itself goes silent is a different job, and it
     * cannot be left to the assembler's timers. Those windows are longer than the silence
     * window (3s against 2s for the battery), so for the gap between them the assembler
     * considers a reading fresh that arrived over a link this class is calling ERROR. That is
     * a live-looking voltage next to a broken-link badge, which is the exact thing the silence
     * check exists to prevent. The link is the only reason its readings can be believed, so
     * losing the link loses the readings.
     */
    private suspend fun watchdogLoop() {
        while (currentCoroutineContext().isActive) {
            delay(WATCHDOG_INTERVAL_MILLIS)
            if (readJob?.isActive != true) return

            // Before the first byte, silence is measured from the moment the port opened —
            // otherwise a port that opened and produced nothing would be called healthy for
            // one full window on the strength of having produced nothing for no time at all.
            val reference = if (lastByteAt != 0L) lastByteAt else openedAt
            val silent = reference == 0L || now() - reference > LINK_SILENCE_MILLIS

            if (silent) assembler.reset()

            publish()
            _connection.value = when {
                silent -> ConnectionState.ERROR

                // Bytes have arrived on this link at some point, so the link is real even if
                // this particular tick happened to be quiet. This is also what restores
                // CONNECTED after a silent stretch ends.
                lastByteAt != 0L -> ConnectionState.CONNECTED

                // The port is open, the silence window has not elapsed, and not one byte has
                // ever arrived. That is not a connection — bytes are the only evidence a link
                // exists — it is a connection being attempted, and whatever [connect] or
                // [retryLoop] already set (CONNECTING, or RECONNECTING) is the honest answer.
                // Without this branch the watchdog would call a port that has said nothing
                // "Link up" for the first two seconds of every attempt.
                else -> _connection.value
            }
        }
    }

    private fun publish() {
        val at = now()
        val snapshot = assembler.snapshot(at)

        _telemetry.value = snapshot.battery
        _gps.value = snapshot.gps
        _flight.value = snapshot.flight

        val stats = parser.stats()
        _linkHealth.value = LinkHealth(
            framesDecoded = stats.framesDecoded,
            checksumFailures = stats.checksumFailures,
            unsupportedMessages = stats.unsupportedMessages,
            bytesDiscarded = stats.bytesDiscarded,
            cellDataTrust = snapshot.cellDataTrust,
            dialectCorrections = parser.dialect.corrections,
            bytesArriving = lastByteAt != 0L && at - lastByteAt <= LINK_SILENCE_MILLIS,
            reconnectAttempts = reconnectAttempts,
        )
    }

    private fun now(): Long = clock()

    companion object {
        /**
         * How long the link may be silent before it is called broken.
         *
         * Two seconds. A healthy MAVLink stream sends a heartbeat once a second at minimum,
         * so this is roughly two missed heartbeats — long enough not to fire on a scheduling
         * hiccup or a brief dropout, short enough that the operator finds out while there is
         * still time to do something about it.
         */
        const val LINK_SILENCE_MILLIS = 2_000L

        /**
         * How often the link re-checks itself. Internal so a test can advance virtual time by
         * exactly one tick rather than by a number that would silently stop testing this if
         * the interval ever changed.
         */
        internal const val WATCHDOG_INTERVAL_MILLIS = 500L

        /**
         * How long to wait before the first attempt to reopen a port that died.
         *
         * Short, because the common cause is a cable that has just been knocked and the
         * common cure is that it is already back in. Waiting longer than a quarter of a
         * second to even try would only delay a recovery that is usually instant.
         */
        internal const val INITIAL_RETRY_MILLIS = 250L

        /**
         * Ceiling on the wait between reopen attempts.
         *
         * Five seconds. An adapter that has been left out for an hour is retried about twelve
         * times a minute rather than two hundred and forty, and a cable put back mid-flight is
         * still picked up fast enough to be worth having.
         */
        internal const val MAX_RETRY_MILLIS = 5_000L
    }
}
