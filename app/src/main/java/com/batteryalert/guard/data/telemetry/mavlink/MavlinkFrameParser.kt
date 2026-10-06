package com.batteryalert.guard.data.telemetry.mavlink

/**
 * Turns a stream of bytes from a serial link into validated MAVLink frames.
 *
 * The bytes arrive in whatever chunks the transport happens to deliver — a frame can be split
 * across two reads, and several can arrive in one. So this is a resynchronising streaming
 * parser rather than a function over a buffer: it holds the tail of whatever it could not
 * finish, and returns only the frames it could complete.
 *
 * ### Resynchronisation
 *
 * A serial link started mid-stream, or one that has suffered a glitch, will hand over bytes
 * that begin part-way through a frame. The parser cannot know where the next frame starts, so
 * it scans for a start marker and tries to build a frame there; the checksum is what decides
 * whether it guessed right. On failure it advances a single byte and tries again, which is
 * what makes a stream recover on its own after a burst of noise.
 *
 * The cost of that is a real one and worth stating: while resynchronising, payload bytes that
 * happen to equal `0xFD` or `0xFE` are tried as frame starts. They fail their checksums too,
 * so the outcome is correct, but the frame counters will show failures during a resync that
 * are not themselves the fault. [MavlinkFrameParserStats.checksumFailures] is a symptom
 * count, not a count of corrupted frames.
 *
 * ### Failing loudly
 *
 * Every byte this parser throws away is counted. A silent link and a link delivering garbage
 * look identical on a dashboard that only reads decoded values; they are very different
 * problems and the operator needs to be able to tell them apart.
 *
 * ### Threading
 *
 * Deliberately not thread-safe — one instance is driven from one coroutine reading one
 * transport. See [MavlinkDialect] for the same note on the constants it holds.
 */
internal class MavlinkFrameParser(
    val dialect: MavlinkDialect = MavlinkDialect(),
    private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
) {

    private var buffer = ByteArray(INITIAL_BUFFER_BYTES)
    private var size = 0

    private var framesDecoded = 0
    private var checksumFailures = 0
    private var unsupportedMessages = 0
    private var bytesDiscarded = 0

    fun stats(): MavlinkFrameParserStats = MavlinkFrameParserStats(
        framesDecoded = framesDecoded,
        checksumFailures = checksumFailures,
        unsupportedMessages = unsupportedMessages,
        bytesDiscarded = bytesDiscarded,
    )

    /**
     * Forgets any partial frame.
     *
     * Called when the link is torn down: half a frame from the previous connection has no
     * continuation in the next one, and keeping it would make the first successful frame after
     * reconnecting fail its checksum for reasons that have nothing to do with the new link.
     *
     * The dialect's constants are deliberately *not* cleared. They are a property of the
     * aircraft, and unplugging the cable does not change what the aircraft speaks.
     */
    fun resetBuffer() {
        size = 0
    }

    /** Feeds received bytes and returns every frame that completed as a result. */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<MavlinkFrame> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "feed(${bytes.size} bytes, offset=$offset, length=$length) is out of range"
        }
        append(bytes, offset, length)
        return drain()
    }

    // --- Buffer --------------------------------------------------------------------------

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return

        var sourceOffset = offset
        var sourceLength = length

        // A chunk larger than the whole buffer cannot be usefully buffered; keep its tail,
        // which is the only part that could still complete a frame.
        if (sourceLength > maxBufferedBytes) {
            val dropped = sourceLength - maxBufferedBytes
            bytesDiscarded += dropped
            sourceOffset += dropped
            sourceLength = maxBufferedBytes
        }

        val overflow = (size + sourceLength) - maxBufferedBytes
        if (overflow > 0) {
            val drop = overflow.coerceAtMost(size)
            System.arraycopy(buffer, drop, buffer, 0, size - drop)
            size -= drop
            bytesDiscarded += drop
        }

        ensureCapacity(size + sourceLength)
        System.arraycopy(bytes, sourceOffset, buffer, size, sourceLength)
        size += sourceLength
    }

    private fun ensureCapacity(required: Int) {
        if (buffer.size >= required) return
        var capacity = buffer.size
        while (capacity < required) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    // --- Framing -------------------------------------------------------------------------

    private fun drain(): List<MavlinkFrame> {
        val frames = mutableListOf<MavlinkFrame>()
        var cursor = 0

        while (true) {
            val start = indexOfStartMarker(cursor)
            if (start < 0) {
                bytesDiscarded += size - cursor
                cursor = size
                break
            }
            if (start > cursor) {
                // Bytes before the marker could never have been part of a frame.
                bytesDiscarded += start - cursor
                cursor = start
            }

            // Same signed-Byte trap as indexOfStartMarker: mask before comparing, and mind the
            // parentheses — `a and 0xFF == b` parses as `a and (0xFF == b)`.
            val version = if ((buffer[start].toInt() and 0xFF) == MavlinkFrame.STX_V2) 2 else 1
            val headerBytes =
                if (version == 2) MavlinkFrame.V2_HEADER_BYTES else MavlinkFrame.V1_HEADER_BYTES

            if (size - start < headerBytes) break // Header itself is split across chunks.

            val payloadLength = buffer[start + 1].toInt() and 0xFF
            val signed = version == 2 &&
                (buffer[start + 2].toInt() and SIGNATURE_PRESENT_FLAG) != 0
            val signatureBytes = if (signed) MavlinkFrame.SIGNATURE_BYTES else 0
            val frameBytes = headerBytes + payloadLength +
                MavlinkFrame.CHECKSUM_BYTES + signatureBytes

            if (size - start < frameBytes) break // Payload still arriving.

            val messageId = messageIdAt(start, version)
            val checksumOffset = start + headerBytes + payloadLength
            val received = (buffer[checksumOffset].toInt() and 0xFF) or
                ((buffer[checksumOffset + 1].toInt() and 0xFF) shl 8)
            // The checksum covers the header from its length byte onwards, plus the payload.
            // The start marker and the checksum itself are excluded; a v2 signature is not
            // covered either.
            val body = MavlinkCrc.chain(buffer, start + 1, headerBytes - 1 + payloadLength)

            val knownExtra = dialect.extraFor(messageId)
            val matchesKnown =
                knownExtra != null && MavlinkCrc.accumulate(body, knownExtra) == received

            if (!matchesKnown) {
                // Not a frame this app will hand to anyone.
                if (!MavlinkMessageSpec.isSupported(messageId)) {
                    // Framed but unverifiable, and not something this app decodes. Counted so
                    // the diagnostics can show that the link is alive and speaking a dialect
                    // richer than this app understands — which is very different from a link
                    // that is silent.
                    unsupportedMessages++
                    cursor = start + frameBytes
                    continue
                }

                // A supported message whose checksum does not fit. Two possibilities, and this
                // cannot tell them apart on one frame: the constant is wrong, or the frame is
                // corrupt. So nothing is emitted either way — a recovered extra is evidence
                // about the *dialect*, never a licence to decode a frame that failed its
                // checksum. See `MavlinkCrc.recoverExtra`.
                checksumFailures++
                MavlinkCrc.recoverExtra(body, received)?.let { recovered ->
                    dialect.observeDisagreement(messageId, recovered)
                    // The body checked out under some extra, so the length byte is credible
                    // and the next frame is exactly where the header says it is.
                    cursor = start + frameBytes
                } ?: run {
                    // Nothing explains this frame. The start marker was a false positive and
                    // the only way forward is to look for the next one.
                    cursor = start + 1
                }
                continue
            }

            frames += MavlinkFrame(
                messageId = messageId,
                payload = buffer.copyOfRange(start + headerBytes, checksumOffset),
                sequence = buffer[start + sequenceOffset(version)].toInt() and 0xFF,
                systemId = buffer[start + systemIdOffset(version)].toInt() and 0xFF,
                componentId = buffer[start + componentIdOffset(version)].toInt() and 0xFF,
                version = version,
            )
            framesDecoded++

            cursor = start + frameBytes
        }

        compact(cursor)
        return frames
    }

    private fun indexOfStartMarker(from: Int): Int {
        var index = from
        while (index < size) {
            // `and 0xFF` is load-bearing. A Kotlin Byte is signed, so 0xFD reads as -3 and 0xFE
            // as -2, and neither would ever equal the 253 and 254 the constants hold. Without
            // the mask no start marker is ever found, and the parser silently discards the
            // entire stream as noise — which looks exactly like a dead link.
            val byte = buffer[index].toInt() and 0xFF
            if (byte == MavlinkFrame.STX_V1 || byte == MavlinkFrame.STX_V2) return index
            index++
        }
        return -1
    }

    /** Drops everything before [from], keeping the incomplete tail for the next read. */
    private fun compact(from: Int) {
        if (from <= 0) return
        val remaining = size - from
        if (remaining > 0) System.arraycopy(buffer, from, buffer, 0, remaining)
        size = remaining
    }

    // --- Header field offsets ------------------------------------------------------------
    // v1: STX, len, seq, sysid, compid, msgid[1]
    // v2: STX, len, incompat, compat, seq, sysid, compid, msgid[3]

    private fun sequenceOffset(version: Int): Int = if (version == 2) 4 else 2

    private fun systemIdOffset(version: Int): Int = if (version == 2) 5 else 3

    private fun componentIdOffset(version: Int): Int = if (version == 2) 6 else 4

    private fun messageIdAt(start: Int, version: Int): Int =
        if (version == 2) {
            (buffer[start + 7].toInt() and 0xFF) or
                ((buffer[start + 8].toInt() and 0xFF) shl 8) or
                ((buffer[start + 9].toInt() and 0xFF) shl 16)
        } else {
            buffer[start + 5].toInt() and 0xFF
        }

    companion object {
        private const val INITIAL_BUFFER_BYTES = 1024

        /**
         * Ceiling on bytes held while resynchronising.
         *
         * A frame is at most 280 bytes, so anything approaching this is a link emitting noise
         * rather than telemetry. Buffering is bounded so that a broken link degrades into
         * discarded bytes rather than into an out-of-memory error on the flight controller's
         * own tablet.
         */
        const val DEFAULT_MAX_BUFFERED_BYTES = 4096

        /** `incompat_flags` bit 0: a 13-byte signature trailer follows the checksum. */
        private const val SIGNATURE_PRESENT_FLAG = 0x01
    }
}
