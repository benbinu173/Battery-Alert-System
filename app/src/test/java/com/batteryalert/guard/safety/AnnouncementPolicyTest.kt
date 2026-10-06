package com.batteryalert.guard.safety

import com.batteryalert.guard.domain.model.AlertLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR 5.2 says "announce every 60 seconds". These tests pin down what that means at the
 * edges, because the literal reading — announce on a timer regardless of what changed —
 * is the one that gets an operator hurt: a pack that escalates from a Notice to an
 * Emergency two seconds after the last announcement must not wait fifty-eight seconds.
 */
class AnnouncementPolicyTest {

    private val policy = AnnouncementPolicy()

    @Test
    fun `says nothing when nothing is wrong`() {
        assertFalse(policy.announceIfDue(AlertLevel.NORMAL, nowMillis = 0L))
    }

    @Test
    fun `announces the first alert immediately`() {
        assertFalse(policy.announceIfDue(AlertLevel.NORMAL, nowMillis = 0L))
        assertTrue(policy.announceIfDue(AlertLevel.NOTICE, nowMillis = 1_000L))
    }

    @Test
    fun `does not repeat the same level before the interval`() {
        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 0L))
        assertFalse(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 59_999L))
    }

    @Test
    fun `repeats the same level once the interval has elapsed`() {
        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 0L))
        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 60_000L))
    }

    @Test
    fun `escalation is announced at once, not on the interval`() {
        assertTrue(policy.announceIfDue(AlertLevel.NOTICE, nowMillis = 0L))
        assertTrue(policy.announceIfDue(AlertLevel.EMERGENCY, nowMillis = 2_000L))
    }

    @Test
    fun `escalation in steps is announced at every step`() {
        assertTrue(policy.announceIfDue(AlertLevel.NOTICE, nowMillis = 0L))
        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 1_000L))
        assertTrue(policy.announceIfDue(AlertLevel.CRITICAL, nowMillis = 2_000L))
        assertTrue(policy.announceIfDue(AlertLevel.EMERGENCY, nowMillis = 3_000L))
    }

    @Test
    fun `a cell fault outranks a charge notice, and is announced at once`() {
        assertTrue(policy.announceIfDue(AlertLevel.NOTICE, nowMillis = 0L))
        assertTrue(policy.announceIfDue(AlertLevel.CELL_FAULT, nowMillis = 500L))
    }

    @Test
    fun `an unchanged lower level still waits for the interval`() {
        assertTrue(policy.announceIfDue(AlertLevel.EMERGENCY, nowMillis = 0L))
        // De-escalation without reaching normal is a repeat, not news.
        assertFalse(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 10_000L))
        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 60_000L))
    }

    @Test
    fun `recovery is announced once the interval has passed`() {
        assertFalse(policy.announceIfDue(AlertLevel.NORMAL, nowMillis = 0L))
        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 10_000L))
        assertFalse(policy.announceIfDue(AlertLevel.NORMAL, nowMillis = 20_000L))
        assertTrue(policy.announceIfDue(AlertLevel.NORMAL, nowMillis = 70_000L))
    }

    @Test
    fun `recovery is not repeated`() {
        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 0L))
        assertTrue(policy.announceIfDue(AlertLevel.NORMAL, nowMillis = 60_000L))
        assertFalse(policy.announceIfDue(AlertLevel.NORMAL, nowMillis = 120_000L))
    }

    /**
     * The property the class is built around. A reading that flickers across the threshold
     * must not talk over itself: the second Warning is compared against the Warning that was
     * *announced*, not against the momentary quiet in between.
     */
    @Test
    fun `a flickering level does not chatter`() {
        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 0L))

        // Wobbling above and below the line for a minute produces exactly nothing, because
        // the drops back to NORMAL are not announced and do not move the clock.
        listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L).forEach { at ->
            assertFalse(policy.announceIfDue(AlertLevel.NORMAL, nowMillis = at))
            assertFalse(policy.announceIfDue(AlertLevel.WARNING, nowMillis = at + 100L))
        }

        assertTrue(policy.announceIfDue(AlertLevel.WARNING, nowMillis = 60_000L))
    }

    @Test
    fun `a flicker that escalates still breaks through`() {
        assertTrue(policy.announceIfDue(AlertLevel.NOTICE, nowMillis = 0L))
        assertFalse(policy.announceIfDue(AlertLevel.NOTICE, nowMillis = 1_000L))
        assertTrue(policy.announceIfDue(AlertLevel.CRITICAL, nowMillis = 1_100L))
    }

    @Test
    fun `the interval is configurable`() {
        val fast = AnnouncementPolicy(repeatIntervalMillis = 5_000L)
        assertTrue(fast.announceIfDue(AlertLevel.WARNING, nowMillis = 0L))
        assertFalse(fast.announceIfDue(AlertLevel.WARNING, nowMillis = 4_999L))
        assertTrue(fast.announceIfDue(AlertLevel.WARNING, nowMillis = 5_000L))
    }
}
