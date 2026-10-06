package com.batteryalert.guard.data.telemetry.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checksum, checked against something outside this repository.
 *
 * A round-trip test — build a frame, parse it, assert the fields came back — passes just as
 * happily with the polynomial reversed or the initial value wrong, because the builder and the
 * parser would share the mistake. Every other test in this package rests on this checksum
 * being the right algorithm, so the first test here pins it to a value published for
 * CRC-16/MCRF4XX and not derived from anything in this codebase.
 */
class MavlinkCrcTest {

    @Test
    fun `matches the published catalogue value for CRC-16 MCRF4XX`() {
        // The standard check value for this algorithm: the ASCII digits "123456789", summed
        // from the usual 0xFFFF seed with no CRC extra, give 0x6F91.
        val check = "123456789".toByteArray(Charsets.US_ASCII)

        assertEquals(0x6F91, MavlinkCrc.chain(check, 0, check.size))
    }

    @Test
    fun `folding in the extra is the last step of the frame checksum`() {
        val bytes = byteArrayOf(0x09, 0x00, 0x01, 0x01, 0x01, 0x00)

        assertEquals(
            MavlinkCrc.accumulate(MavlinkCrc.chain(bytes, 0, bytes.size), 50),
            MavlinkCrc.of(bytes, 0, bytes.size, extra = 50),
        )
    }

    @Test
    fun `accumulating only the selected range ignores the rest of the buffer`() {
        val framed = byteArrayOf(0x7F, 0x11, 0x22, 0x33, 0x7F)

        assertEquals(
            MavlinkCrc.chain(byteArrayOf(0x11, 0x22, 0x33), 0, 3),
            MavlinkCrc.chain(framed, 1, 3),
        )
    }

    // --- Recovery -------------------------------------------------------------------------

    @Test
    fun `recovers the extra a frame was built with`() {
        val body = MavlinkTestFrames.payload(MavlinkTestFrames.u16("voltage_battery", 50_400))
        val chain = MavlinkCrc.chain(body, 0, body.size)

        for (extra in 0..0xFF) {
            assertEquals(extra, MavlinkCrc.recoverExtra(chain, MavlinkCrc.accumulate(chain, extra)))
        }
    }

    @Test
    fun `every extra byte produces a different checksum`() {
        // The claim the recovery rests on, and the reason it can never find two answers. If
        // the fold were not injective, a corrupt frame could be explained by two different
        // extras and the parser would have to guess between them.
        val body = MavlinkTestFrames.payload(MavlinkTestFrames.u16("voltage_battery", 50_400))
        val chain = MavlinkCrc.chain(body, 0, body.size)

        val checksums = (0..0xFF).map { MavlinkCrc.accumulate(chain, it) }

        assertEquals("256 candidates must give 256 distinct checksums", 256, checksums.toSet().size)
    }

    @Test
    fun `finds nothing when no byte explains the checksum`() {
        val body = MavlinkTestFrames.payload(MavlinkTestFrames.u16("voltage_battery", 50_400))
        val chain = MavlinkCrc.chain(body, 0, body.size)

        // Chosen rather than guessed: take a checksum the 256 candidates provably cannot
        // produce. Picking one at random and hoping would be a test that passes or fails
        // depending on the input, which is worse than no test.
        val reachable = (0..0xFF).map { MavlinkCrc.accumulate(chain, it) }.toSet()
        val impossible = (0..0xFFFF).first { it !in reachable }

        assertNull(MavlinkCrc.recoverExtra(chain, impossible))
    }

    @Test
    fun `recovery is cheap enough to run on a live link`() {
        // 256 candidates per rejected frame, at the frame rates a telemetry link produces, is
        // nothing — but it is on the alert path's thread, so it is worth a bound rather than
        // an assumption. This asserts the whole space is searched in well under a millisecond.
        val body = MavlinkTestFrames.payload(MavlinkTestFrames.u16("voltage_battery", 50_400))
        val chain = MavlinkCrc.chain(body, 0, body.size)
        val received = MavlinkCrc.accumulate(chain, 154)

        val startedAt = System.nanoTime()
        repeat(1_000) { MavlinkCrc.recoverExtra(chain, received) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            "1000 recoveries took ${elapsedMillis}ms; expected well under 250ms",
            elapsedMillis < 250,
        )
    }
}
