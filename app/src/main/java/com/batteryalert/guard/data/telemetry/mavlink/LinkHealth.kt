package com.batteryalert.guard.data.telemetry.mavlink

/**
 * What the MAVLink link is actually doing, for the diagnostics screen.
 *
 * The reason this type exists: on a real aircraft, "the dashboard is not updating" has at
 * least four causes that look identical from the outside —
 *
 * - nothing is plugged in;
 * - bytes are arriving but they are not MAVLink;
 * - frames are arriving and failing their checksums, because this app's dialect constants do
 *   not match the vehicle's;
 * - frames are arriving and being decoded fine, but this app does not understand them.
 *
 * They need completely different responses from the operator, and the decoded values are the
 * same in all four cases. The counters are the difference.
 */
data class LinkHealth(
    /** Frames that validated and decoded. The only number that means "working". */
    val framesDecoded: Int = 0,

    /**
     * Frames rejected at the checksum.
     *
     * Includes frames rejected while resynchronising after line noise, so this is a symptom
     * count rather than a count of corrupt frames — see `MavlinkFrameParser`.
     */
    val checksumFailures: Int = 0,

    /** Frames that framed cleanly but carry a message this app does not decode. */
    val unsupportedMessages: Int = 0,

    /** Bytes thrown away while looking for the start of a frame. */
    val bytesDiscarded: Int = 0,

    val cellDataTrust: CellDataTrust = CellDataTrust.NOT_REPORTED,

    /**
     * Message ids whose CRC extra this app shipped wrong, and the value the link proved.
     *
     * Normally empty. When it is not, the good news is that the parser has already corrected
     * itself; the information is here because a wrong constant means the *shipped* table is
     * wrong for this airframe and the value should be folded back into the source.
     */
    val dialectCorrections: Map<Int, Int> = emptyMap(),

    /**
     * True when bytes are arriving at all.
     *
     * Distinguishes a dead cable from a live one carrying something this app cannot read,
     * which is the single most useful thing to know before touching any wiring.
     */
    val bytesArriving: Boolean = false,

    /**
     * Times the app has tried and failed to reopen the port since the link last worked.
     *
     * Zero on a link that has never dropped, and reset to zero by the first byte of a live
     * connection — a link that went away and came back is a healthy link with a history, and
     * this is the history.
     *
     * Kept separate from [isClean] rather than folded into it: the connection state already
     * tells the operator a dropout is in progress, and a count that never returns to zero
     * after a successful recovery would make every later reading look suspect.
     */
    val reconnectAttempts: Int = 0,
) {
    /**
     * Nothing has gone wrong that the operator needs to act on.
     *
     * A link that dropped and was brought back is deliberately not counted here — the app has
     * dealt with it, and what is left for the operator to read is [reconnectAttempts]. This
     * predicate is about whether the *decoder* is being fed something it can trust.
     */
    val isClean: Boolean
        get() = checksumFailures == 0 && bytesDiscarded == 0 && unsupportedMessages == 0 &&
            dialectCorrections.isEmpty()
}
