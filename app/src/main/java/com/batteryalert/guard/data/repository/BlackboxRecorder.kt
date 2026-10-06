package com.batteryalert.guard.data.repository

import com.batteryalert.guard.domain.model.BlackboxRecord
import com.batteryalert.guard.domain.model.BlackboxSample
import com.batteryalert.guard.domain.usecase.BlackboxSampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The flight recorder: decides what is worth keeping and writes it without ever slowing
 * the telemetry down.
 *
 * ### Why this is not just `repository.append(...)`
 *
 * The pipeline that produces alerts is the same pipeline that would produce log rows, and
 * an alert three hundred milliseconds late because SQLite was busy is a safety regression
 * caused by an audit feature. So the write path is split in two:
 *
 * - **On the caller's thread**, the sampler decides whether the frame is worth keeping and
 *   the record is handed to a bounded channel. That is an offer, not a write, and it does
 *   not suspend, block, or touch a disk.
 * - **On a single IO coroutine**, records are drained in order and persisted.
 *
 * The single consumer is not incidental: a `launch` per row would let SQLite commit them
 * out of order, and a blackbox whose rows are not in the order the aircraft lived them is
 * a blackbox that cannot be read as a timeline.
 *
 * ### Losing rows loudly
 *
 * If the channel ever fills — a slow disk on a fast link — the *oldest* queued row is
 * dropped, because the rows nearest an event matter most, and [recordsDropped] is
 * incremented. A recorder that silently loses rows is worse than one that admits it: the
 * first makes a gap in the log look like a gap in the flight.
 */
@Singleton
class BlackboxRecorder @Inject constructor(
    private val repository: BlackboxRepository,
    private val sampler: BlackboxSampler,
) {

    /**
     * Its own scope, not the caller's.
     *
     * The dashboard ViewModel is recreated across configuration changes and its
     * `viewModelScope` dies with it; the recorder does not, so a queued row survives a
     * rotation instead of being cancelled mid-write.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dropped = AtomicInteger(0)

    private val queue = Channel<BlackboxRecord>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { dropped.incrementAndGet() },
    )

    /**
     * The current flight.
     *
     * Assigned from the first accepted frame and kept until [endSession], so navigating to
     * the diagnostic view and back does not split one flight into two — the recorder
     * outlives the ViewModel that feeds it.
     */
    @Volatile
    private var sessionId: Long? = null

    /** How many rows were lost to a full queue. Surfaced so the log can say so. */
    val recordsDropped: Int get() = dropped.get()

    init {
        scope.launch {
            for (record in queue) {
                // A storage failure must not kill the drain loop: the very next record
                // could be the one that explains the flight.
                runCatching { repository.append(record) }
            }
        }
    }

    /**
     * Offers one frame to the recorder.
     *
     * @return true when the frame was kept. Cheap, non-blocking, and safe to call from the
     *   telemetry pipeline on every frame.
     */
    fun offer(sample: BlackboxSample, nowMillis: Long): Boolean {
        val trigger = sampler.triggerFor(sample, nowMillis) ?: return false

        val session = sessionId ?: nowMillis.also { sessionId = it }
        val record = BlackboxRecord(
            timestampMillis = nowMillis,
            sessionId = session,
            trigger = trigger,
            sample = sample,
        )

        val accepted = queue.trySend(record).isSuccess
        // Only after the record is actually queued. Recording the stamp for a row that was
        // never kept would make the next frame compare against a sample the log does not
        // contain — and the cadence would then be measured from a row that does not exist.
        if (accepted) sampler.accept(sample, nowMillis)
        return accepted
    }

    /**
     * Ends the current flight, so the next frame starts a new session.
     *
     * Called when the telemetry link is deliberately closed. A reconnection after a lost
     * link is intentionally *not* a new session: the gap is part of the flight, and the
     * `LINK_CHANGE` rows on either side of it are the evidence.
     */
    fun endSession() {
        sessionId = null
    }

    private companion object {
        /**
         * Deep enough that a normal flight never fills it, shallow enough that a stalled
         * database cannot grow without bound. At the 1 Hz cadence this is over eight
         * minutes of backlog.
         */
        const val QUEUE_CAPACITY = 512
    }
}
