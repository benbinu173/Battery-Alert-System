package com.batteryalert.guard.data.telemetry.mavlink

/**
 * The table mapping message id → CRC extra, as the parser will actually use it.
 *
 * It starts from the constants in [MavlinkMessageSpec] and treats them as a **cache of the
 * vehicle's dialect** rather than as a fact. When a frame's checksum does not match and
 * exactly one extra byte would have made it match, that is evidence — not proof — that the
 * shipped constant is wrong. Enough independent sightings of the same value and this table
 * adopts it.
 *
 * ### Why this exists
 *
 * The CRC extra is the only number in a MAVLink frame that cannot be derived from the frame,
 * checked against the payload, or read off the wire. A wrong one is indistinguishable from a
 * vehicle that has gone silent, and both look like a healthy connection. Everything else in
 * this parser can be tested against a specification; this table cannot be tested against
 * anything except the aircraft, and the whole point of the app is that you find out what the
 * aircraft is saying *before* you rely on it.
 *
 * ### Why the evidence bar is where it is
 *
 * A corrupted frame recovers a wrong extra maybe once in 256 — the candidate space is one
 * byte. So a single recovery is not evidence of anything, and adopting one immediately would
 * let line noise permanently break a message type. [CONFIRMATIONS_REQUIRED] independent
 * sightings of the *same* value are required, and a competing value resets the count. Line
 * noise does not produce the same wrong answer three times running.
 *
 * A recovery is never used to accept a frame. Acceptance always goes through a constant this
 * table already holds — see `MavlinkCrc.recoverExtra` for why.
 *
 * ### Threading
 *
 * Deliberately not thread-safe. It is driven from the single coroutine draining the serial
 * transport, and a lock here would be a lock on the alert path.
 */
internal class MavlinkDialect(
    private val confirmationsRequired: Int = CONFIRMATIONS_REQUIRED,
) {

    private val table: MutableMap<Int, Int> = MavlinkMessageSpec.seedTable().toMutableMap()

    /** Running tallies for candidate extras that disagree with the table. */
    private val candidates = mutableMapOf<Int, Candidate>()

    private val adopted = mutableMapOf<Int, Int>()
    private val disputed = mutableMapOf<Int, Int>()

    /** How many times a recovery was attempted and produced a unique answer. */
    var recoveriesObserved: Int = 0
        private set

    /** How many times the table was actually changed. Should normally be zero. */
    val correctionsAdopted: Int get() = adopted.size

    /** Message ids whose shipped constant the wire disagrees with, and what it proposed. */
    val disagreements: Map<Int, Int> get() = disputed.toMap()

    /** Message ids this dialect has actually rewritten, and the value it settled on. */
    val corrections: Map<Int, Int> get() = adopted.toMap()

    /** The extra to validate a frame of [messageId] with, or null if this dialect has none. */
    fun extraFor(messageId: Int): Int? = table[messageId]

    /**
     * Records an observed frame whose checksum fits [recoveredExtra] rather than the table's.
     *
     * Called only when a frame failed validation but a unique extra would have saved it. See
     * the class comment for why the counter is per-value and why the bar is three.
     */
    fun observeDisagreement(messageId: Int, recoveredExtra: Int) {
        recoveriesObserved++

        val current = table[messageId]
        if (current == recoveredExtra) return

        val previous = candidates[messageId]
        val next = if (previous != null && previous.extra == recoveredExtra) {
            previous.copy(count = previous.count + 1)
        } else {
            Candidate(recoveredExtra, count = 1)
        }
        candidates[messageId] = next

        // Report the disagreement whether or not it is acted on. The dashboard's job is to
        // tell the operator the truth about the link, and "the constant I shipped is wrong"
        // is a more useful thing to say than a bare "no telemetry".
        disputed[messageId] = recoveredExtra

        if (next.count >= confirmationsRequired) {
            table[messageId] = recoveredExtra
            adopted[messageId] = recoveredExtra
            candidates.remove(messageId)
        }
    }

    /** Restores the shipped constant for [messageId], forgetting any learned value. */
    fun forget(messageId: Int) {
        MavlinkMessageSpec.seedCrcExtra(messageId)?.let { table[messageId] = it }
            ?: table.remove(messageId)
        candidates.remove(messageId)
        adopted.remove(messageId)
        disputed.remove(messageId)
    }

    private data class Candidate(val extra: Int, val count: Int)

    companion object {
        /**
         * Independent, identical sightings required before the table is rewritten.
         *
         * Three: enough that a one-in-256 false recovery from line noise cannot repeat, few
         * enough that a genuinely wrong constant is corrected within the first second of a
         * link that is streaming at any normal rate.
         */
        const val CONFIRMATIONS_REQUIRED = 3
    }
}
