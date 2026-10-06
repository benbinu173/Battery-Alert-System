package com.batteryalert.guard.data.telemetry.mavlink

/**
 * One validated MAVLink frame, already checksummed and stripped of its envelope.
 *
 * Deliberately a plain class rather than a `data class`: the payload is a `ByteArray`, and a
 * generated `equals` would compare arrays by identity while looking like it compared contents.
 * Nothing here needs value equality anyway — a frame is consumed, not stored.
 */
internal class MavlinkFrame(
    /** The message id, decoded from the one-byte (v1) or three-byte (v2) header field. */
    val messageId: Int,
    /** The payload, exactly as it arrived. Never extended, so trailing zeros stay dropped. */
    val payload: ByteArray,
    /** Sender's sequence number. Gaps are how the parser notices dropped frames. */
    val sequence: Int,
    val systemId: Int,
    val componentId: Int,
    /** 1 or 2 — which framing carried this message. */
    val version: Int,
) {
    val payloadLength: Int get() = payload.size

    override fun toString(): String =
        "MavlinkFrame(v$version id=$messageId sys=$systemId comp=$componentId " +
            "seq=$sequence payload=${payload.size}B)"

    companion object {
        /** MAVLink v1 start marker. */
        const val STX_V1 = 0xFE

        /** MAVLink v2 start marker. */
        const val STX_V2 = 0xFD

        /**
         * Bytes in a v1 frame before the payload: STX, len, seq, sysid, compid, msgid.
         */
        const val V1_HEADER_BYTES = 6

        /**
         * Bytes in a v2 frame before the payload: STX, len, incompat, compat, seq, sysid,
         * compid, and a three-byte message id.
         */
        const val V2_HEADER_BYTES = 10

        /** Two checksum bytes trailer every frame. */
        const val CHECKSUM_BYTES = 2

        /** Bytes of optional v2 signature trailer, present when incompat_flags bit 0 is set. */
        const val SIGNATURE_BYTES = 13

        /**
         * Message ids are 24 bits in v2 and 8 in v1. A payload longer than this is not a
         * plausible MAVLink frame and means the parser is looking at noise that happens to
         * contain a start marker.
         */
        const val MAX_PAYLOAD_BYTES = 255
    }
}

/**
 * Counters for the diagnostics screen.
 *
 * These exist so that a link which is *nearly* working is distinguishable from one that is
 * not working at all. "No telemetry" is the same on screen whether the cable is unplugged or
 * every frame is failing its checksum; these are what tell the two apart.
 *
 * Public because it is part of [LinkHealth], which the diagnostics screen reads.
 */
data class MavlinkFrameParserStats(
    val framesDecoded: Int = 0,
    val checksumFailures: Int = 0,
    val unsupportedMessages: Int = 0,
    val bytesDiscarded: Int = 0,
) {
    /** True when nothing has gone wrong that the operator needs to know about. */
    val isClean: Boolean
        get() = checksumFailures == 0 && bytesDiscarded == 0 && unsupportedMessages == 0
}
