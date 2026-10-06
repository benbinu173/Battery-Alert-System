package com.batteryalert.guard.data.telemetry.mavlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser, driven the way a serial port drives it: arbitrary chunks, arbitrary splits,
 * and noise in between.
 */
class MavlinkFrameParserTest {

    private val heartbeatPayload = MavlinkTestFrames.heartbeat(armed = true)
    private val heartbeatFrame =
        MavlinkTestFrames.frame(MavlinkMessageSpec.ID_HEARTBEAT, heartbeatPayload)

    @Test
    fun `decodes a single v2 frame`() {
        val parser = MavlinkFrameParser()

        val frames = parser.feed(heartbeatFrame)

        assertEquals(1, frames.size)
        assertEquals(MavlinkMessageSpec.ID_HEARTBEAT, frames[0].messageId)
        assertEquals(2, frames[0].version)
        assertEquals(1, frames[0].systemId)
        assertEquals(1, frames[0].componentId)
        assertArrayEquals(heartbeatPayload, frames[0].payload)
    }

    @Test
    fun `decodes a v1 frame`() {
        val frame = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_HEARTBEAT,
            payload = heartbeatPayload,
            version = 1,
        )

        val frames = MavlinkFrameParser().feed(frame)

        assertEquals(1, frames.size)
        assertEquals(1, frames[0].version)
        assertEquals(MavlinkMessageSpec.ID_HEARTBEAT, frames[0].messageId)
    }

    @Test
    fun `decodes a v1 frame with a message id above the v1 range boundary`() {
        // BATTERY_STATUS is 147, which fits in a v1 byte — but the v1 header also cannot carry
        // ids above 255 at all, so this confirms the one-byte field is read unsigned.
        val payload = MavlinkTestFrames.batteryStatus(listOf(4_050, 4_048, 4_052))
        val frame = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_BATTERY_STATUS,
            payload = payload,
            version = 1,
        )

        val frames = MavlinkFrameParser().feed(frame)

        assertEquals(1, frames.size)
        assertEquals(147, frames[0].messageId)
    }

    @Test
    fun `reassembles a frame split across chunks`() {
        val parser = MavlinkFrameParser()

        // Byte at a time, which is what a slow link does and what a naive parser gets wrong.
        var frames = emptyList<MavlinkFrame>()
        for (byte in heartbeatFrame) {
            frames = frames + parser.feed(byteArrayOf(byte))
        }

        assertEquals(1, frames.size)
        assertArrayEquals(heartbeatPayload, frames[0].payload)
        assertEquals(0, parser.stats().bytesDiscarded)
    }

    @Test
    fun `decodes several frames arriving in one chunk`() {
        val parser = MavlinkFrameParser()
        val second = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_HEARTBEAT,
            payload = MavlinkTestFrames.heartbeat(armed = false),
        )

        val frames = parser.feed(heartbeatFrame + second)

        assertEquals(2, frames.size)
        assertTrue(MavlinkDecoders.heartbeat(frames[0].payload).armed)
        assertFalse(MavlinkDecoders.heartbeat(frames[1].payload).armed)
        assertEquals(2, parser.stats().framesDecoded)
    }

    @Test
    fun `resynchronises after leading garbage`() {
        val parser = MavlinkFrameParser()
        // Deliberately no 0xFD or 0xFE among these: garbage that contains a start marker would
        // be testing the checksum-rejection path, not the resynchronisation path.
        val garbage = byteArrayOf(0x00, 0x12, 0x34, 0xFF.toByte(), 0x7E)

        val frames = parser.feed(garbage + heartbeatFrame)

        assertEquals(1, frames.size)
        assertEquals(garbage.size, parser.stats().bytesDiscarded)
    }

    @Test
    fun `resynchronises and recovers the frame after a corrupted one`() {
        val parser = MavlinkFrameParser()
        val corrupted = heartbeatFrame.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()

        val next = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_HEARTBEAT,
            payload = MavlinkTestFrames.heartbeat(armed = false),
        )
        val frames = parser.feed(corrupted + next)

        assertEquals("the good frame after the bad one must still be found", 1, frames.size)
        assertEquals(0, frames[0].payload[6].toInt())
        assertTrue("the corrupt frame must be counted", parser.stats().checksumFailures > 0)
    }

    @Test
    fun `a corrupted payload is never handed on`() {
        val parser = MavlinkFrameParser()
        val corrupted = heartbeatFrame.copyOf()
        // A payload byte, so the message id survives intact. Flipping a byte in the header
        // instead would change the id — and an id this app does not decode is routed to the
        // unsupported counter, never the checksum counter, so the assertions below would be
        // about a different branch than the one this test is named for.
        val firstPayloadByte = MavlinkFrame.V2_HEADER_BYTES
        corrupted[firstPayloadByte] = (corrupted[firstPayloadByte] + 1).toByte()

        val frames = parser.feed(corrupted)

        assertTrue(frames.isEmpty())
        assertTrue(parser.stats().checksumFailures > 0)
        assertEquals(0, parser.stats().framesDecoded)
    }

    @Test
    fun `a stream of noise produces no frames and does not grow without bound`() {
        val parser = MavlinkFrameParser()
        val noise = ByteArray(64 * 1024) { index -> ((index * 31) and 0xFF).toByte() }

        val frames = parser.feed(noise)

        assertTrue(frames.isEmpty())
        assertTrue(
            "discarded bytes should account for the whole input",
            parser.stats().bytesDiscarded >= noise.size - MavlinkFrameParser.DEFAULT_MAX_BUFFERED_BYTES,
        )
    }

    @Test
    fun `counts messages it can frame but does not decode`() {
        val parser = MavlinkFrameParser()
        // id 9999 is not in the supported set. Recovery is not attempted for it, so it is
        // framed by its length byte and skipped.
        val frame = MavlinkTestFrames.frame(9999, ByteArray(12), crcExtra = 7)

        val frames = parser.feed(frame)

        assertTrue(frames.isEmpty())
        assertEquals(1, parser.stats().unsupportedMessages)
    }

    @Test
    fun `skips a v2 frame that carries a signature trailer`() {
        val parser = MavlinkFrameParser()
        val signed = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_HEARTBEAT,
            payload = heartbeatPayload,
            signed = true,
        )

        val frames = parser.feed(signed + heartbeatFrame)

        assertEquals("the signed frame and the plain one that follows it", 2, frames.size)
        assertEquals(2, parser.stats().framesDecoded)
    }

    // --- The dialect correcting itself -----------------------------------------------------

    @Test
    fun `a frame is accepted against the constant the dialect holds`() {
        val parser = MavlinkFrameParser()

        val frames = parser.feed(heartbeatFrame)

        assertEquals(1, frames.size)
        assertEquals(0, parser.stats().checksumFailures)
        assertTrue(parser.dialect.corrections.isEmpty())
    }

    @Test
    fun `a frame whose constant is wrong is rejected until the dialect has seen it three times`() {
        val parser = MavlinkFrameParser()
        // Built with an extra the shipped table does not agree with, standing in for an
        // airframe that runs a dialect this app has the constant wrong for.
        val wrongExtra = 99
        val frame = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_HEARTBEAT,
            payload = heartbeatPayload,
            crcExtra = wrongExtra,
        )

        val first = parser.feed(frame)
        val second = parser.feed(frame)
        val third = parser.feed(frame)

        assertTrue("nothing is decoded while the constant is in doubt", first.isEmpty())
        assertTrue(second.isEmpty())
        assertTrue(
            "the third sighting is the one that corrects the table, not one that decodes",
            third.isEmpty(),
        )
        assertEquals(wrongExtra, parser.dialect.corrections[MavlinkMessageSpec.ID_HEARTBEAT])
        assertTrue(parser.stats().checksumFailures >= 3)
    }

    @Test
    fun `the link comes back on its own once the dialect is corrected`() {
        val parser = MavlinkFrameParser()
        val frame = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_HEARTBEAT,
            payload = heartbeatPayload,
            crcExtra = 99,
        )

        repeat(3) { parser.feed(frame) }
        val recovered = parser.feed(frame)

        assertEquals("the fourth frame is decoded", 1, recovered.size)
        assertEquals(MavlinkMessageSpec.ID_HEARTBEAT, recovered[0].messageId)
    }

    @Test
    fun `a corrupted frame does not on its own rewrite the dialect`() {
        val parser = MavlinkFrameParser()
        val corrupted = heartbeatFrame.copyOf()
        corrupted[8] = (corrupted[8] + 1).toByte() // a payload byte, not the checksum

        parser.feed(corrupted)

        assertTrue(
            "one corrupt frame must never be enough to change what the parser believes",
            parser.dialect.corrections.isEmpty(),
        )
    }

    @Test
    fun `a disagreement is reported even while it is still only a suspicion`() {
        val parser = MavlinkFrameParser()
        val frame = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_HEARTBEAT,
            payload = heartbeatPayload,
            crcExtra = 99,
        )

        parser.feed(frame)

        // Reported immediately so the diagnostics can say "the constant is wrong" rather than
        // leaving the operator with a bare "no telemetry" while the table fills up.
        assertEquals(99, parser.dialect.disagreements[MavlinkMessageSpec.ID_HEARTBEAT])
        assertTrue(parser.dialect.corrections.isEmpty())
    }

    @Test
    fun `resetting the buffer keeps what the dialect has learned`() {
        val parser = MavlinkFrameParser()
        val frame = MavlinkTestFrames.frame(
            messageId = MavlinkMessageSpec.ID_HEARTBEAT,
            payload = heartbeatPayload,
            crcExtra = 99,
        )

        repeat(3) { parser.feed(frame) }
        parser.resetBuffer()

        assertEquals(
            "unplugging the cable does not change what the aircraft speaks",
            99,
            parser.dialect.corrections[MavlinkMessageSpec.ID_HEARTBEAT],
        )
        assertEquals("but a half-frame must not survive", 1, parser.feed(frame).size)
    }

    @Test
    fun `a partially received frame is held for the next chunk`() {
        val parser = MavlinkFrameParser()
        val cut = heartbeatFrame.size - 3

        val first = parser.feed(heartbeatFrame.copyOfRange(0, cut))
        val second = parser.feed(heartbeatFrame.copyOfRange(cut, heartbeatFrame.size))

        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
    }

    @Test
    fun `resetBuffer discards a partial frame`() {
        val parser = MavlinkFrameParser()
        val cut = heartbeatFrame.size - 3

        parser.feed(heartbeatFrame.copyOfRange(0, cut))
        parser.resetBuffer()
        val frames = parser.feed(heartbeatFrame.copyOfRange(cut, heartbeatFrame.size))

        assertTrue("the tail of an abandoned frame is not a frame", frames.isEmpty())
    }

    @Test
    fun `rejects a feed whose range does not match the array`() {
        val parser = MavlinkFrameParser()

        val failure = runCatching { parser.feed(ByteArray(4), offset = 2, length = 9) }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }
}
