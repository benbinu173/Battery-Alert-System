package com.batteryalert.guard.data.telemetry.mavlink

/**
 * The checksum MAVLink frames carry.
 *
 * This is CRC-16/MCRF4XX (also called X.25), seeded with 0xFFFF and not reflected out. It is
 * the same accumulator the reference implementation calls `crc_accumulate`, written the same
 * way, because there is no value in a cleverer formulation of a bit-twiddling loop that has
 * one correct answer.
 *
 * ### How a frame's checksum is built
 *
 * Over the bytes that follow the start marker and precede the checksum — length, sequence,
 * system and component ids, message id, and payload — and then over one further byte: the
 * message's own **CRC extra**. That last byte is what stops a frame being valid for more
 * than one message type, and it is why a parser cannot validate a frame whose message id it
 * does not recognise. See [MavlinkMessageSpec].
 *
 * ### What is verified here
 *
 * The algorithm is checked against the published catalogue value for CRC-16/MCRF4XX
 * (`"123456789"` → `0x6F91`), which is an external check rather than a self-consistency
 * one — a round-trip test would pass just as happily with the polynomial reversed.
 */
internal object MavlinkCrc {

    /** 0xFFFF, per the MAVLink specification. */
    const val INIT = 0xFFFF

    /** Folds one byte into a running checksum. */
    fun accumulate(crc: Int, byte: Int): Int {
        var temporary = (byte and 0xFF) xor (crc and 0xFF)
        temporary = (temporary xor (temporary shl 4)) and 0xFF
        return ((crc shr 8) xor (temporary shl 8) xor (temporary shl 3) xor (temporary shr 4)) and
            0xFFFF
    }

    /** The checksum of [bytes] *before* the CRC extra is folded in. */
    fun chain(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = INIT
        for (index in offset until offset + length) {
            crc = accumulate(crc, bytes[index].toInt())
        }
        return crc
    }

    /** The checksum of [bytes], with [extra] folded in last. */
    fun of(bytes: ByteArray, offset: Int, length: Int, extra: Int): Int =
        accumulate(chain(bytes, offset, length), extra)

    /**
     * Recovers the CRC extra a frame must have been built with, or null if none fits.
     *
     * This exists because the extra is the one number in a MAVLink frame that cannot be
     * derived from the frame or checked against anything the vehicle sends. A parser shipped
     * with a wrong extra rejects every frame of that message type and cannot tell the
     * difference between a wrong constant and a genuinely silent vehicle.
     *
     * The extra is a single byte and it is folded in as the very last step, so the whole
     * space is 256 candidates — cheap to search. [chain] gives the checksum over the frame
     * body; the extra is then whichever byte makes that equal the checksum on the wire.
     *
     * ### Why there is never more than one answer
     *
     * Folding a byte in is a bijection on the accumulated value. Writing the byte as two
     * nibbles `h` and `l`, the mixing step maps `(h, l)` to `(h ^ l, l)` — invertible — and
     * the final fold spreads the eight mixed bits across sixteen positions with each input
     * bit recoverable from the output. So the 256 candidates produce 256 *distinct* checksums
     * and at most one of them can equal what arrived.
     *
     * The uniqueness check below is kept anyway. The argument is sound but it is reasoning
     * about bit twiddling, and a cheap guard is a better place to be wrong than a comment.
     *
     * ### What a null means
     *
     * No byte explains this frame, so it is not a MAVLink frame that got corrupted — it is
     * not a frame at all. For random bytes this happens about 255 times in 256, which is why
     * this doubles as the parser's "that start marker was a coincidence" test.
     *
     * ### What this is not for
     *
     * A *diagnostic*, never an acceptance path. Roughly one corrupt frame in 256 has some
     * byte that explains it, so accepting on recovery would pass those through to the decode
     * path. Frames are accepted only against a constant the dialect already holds; this is
     * what tells the dialect that its constant is wrong.
     */
    fun recoverExtra(chain: Int, received: Int): Int? {
        var found: Int? = null
        for (candidate in 0..MAX_EXTRA) {
            if (accumulate(chain, candidate) == received) {
                if (found != null) return null // Should be impossible; see the note above.
                found = candidate
            }
        }
        return found
    }

    /** The CRC extra is a single byte, so this is the whole search space. */
    const val MAX_EXTRA = 0xFF
}
