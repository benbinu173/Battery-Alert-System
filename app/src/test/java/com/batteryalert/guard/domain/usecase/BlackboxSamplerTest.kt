package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.BlackboxSample
import com.batteryalert.guard.domain.model.BlackboxTrigger
import com.batteryalert.guard.domain.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recorder's decision table.
 *
 * These are the tests that decide whether the log is worth anything. A recorder that writes
 * everything is a disk-filler; one that writes on a timer has a hole in it exactly where the
 * interesting milliseconds are; one that writes nothing when nothing changes has no record
 * of the quiet parts of the flight that make the loud parts legible.
 */
class BlackboxSamplerTest {

    private val sampler = BlackboxSampler()

    private fun sample(
        level: AlertLevel = AlertLevel.NORMAL,
        rule: AlertRule? = null,
        connection: ConnectionState = ConnectionState.CONNECTED,
        gpsLocked: Boolean = true,
    ) = BlackboxSample(
        connectionState = connection,
        gpsLocked = gpsLocked,
        alertLevel = level,
        alertRule = rule,
    )

    @Test
    fun `the first frame of a session is always kept`() {
        assertEquals(
            BlackboxTrigger.SESSION_START,
            sampler.triggerFor(sample(), nowMillis = 1_000L),
        )
    }

    @Test
    fun `a quiet frame inside the cadence is skipped`() {
        sampler.accept(sample(), nowMillis = 0L)
        assertNull(sampler.triggerFor(sample(), nowMillis = 999L))
    }

    @Test
    fun `a quiet frame once the cadence elapses is kept`() {
        sampler.accept(sample(), nowMillis = 0L)
        assertEquals(
            BlackboxTrigger.CADENCE,
            sampler.triggerFor(sample(), nowMillis = 1_000L),
        )
    }

    @Test
    fun `the cadence is measured from the last kept frame, not the last seen one`() {
        sampler.accept(sample(), nowMillis = 0L)

        // Frames arrive every 250 ms and are skipped. If a skipped frame advanced the
        // clock, the cadence would never elapse and the log would silently stop.
        listOf(250L, 500L, 750L).forEach { at ->
            assertNull(sampler.triggerFor(sample(), nowMillis = at))
        }
        assertEquals(BlackboxTrigger.CADENCE, sampler.triggerFor(sample(), nowMillis = 1_000L))
    }

    @Test
    fun `an escalation is recorded at once, inside the cadence`() {
        sampler.accept(sample(level = AlertLevel.NOTICE), nowMillis = 0L)
        assertEquals(
            BlackboxTrigger.ALERT_CHANGE,
            sampler.triggerFor(
                sample(level = AlertLevel.EMERGENCY, rule = AlertRule.CELL_VOLTAGE_EMERGENCY),
                nowMillis = 10L,
            ),
        )
    }

    /**
     * The deliberate opposite of the announcement policy. A blackbox records what happened,
     * including the part where it stopped happening.
     */
    @Test
    fun `a change and a change back are both recorded`() {
        sampler.accept(sample(), nowMillis = 0L)

        assertEquals(
            BlackboxTrigger.ALERT_CHANGE,
            sampler.triggerFor(sample(level = AlertLevel.WARNING), nowMillis = 10L),
        )
        sampler.accept(sample(level = AlertLevel.WARNING), nowMillis = 10L)

        assertEquals(
            BlackboxTrigger.ALERT_CHANGE,
            sampler.triggerFor(sample(), nowMillis = 20L),
        )
    }

    @Test
    fun `losing the link is recorded at once`() {
        sampler.accept(sample(), nowMillis = 0L)
        assertEquals(
            BlackboxTrigger.LINK_CHANGE,
            sampler.triggerFor(
                sample(connection = ConnectionState.ERROR),
                nowMillis = 10L,
            ),
        )
    }

    @Test
    fun `losing the gps fix is recorded at once`() {
        sampler.accept(sample(), nowMillis = 0L)
        assertEquals(
            BlackboxTrigger.LINK_CHANGE,
            sampler.triggerFor(sample(gpsLocked = false), nowMillis = 10L),
        )
    }

    @Test
    fun `a cell fault starts full-resolution logging`() {
        sampler.accept(sample(), nowMillis = 0L)
        sampler.accept(sample(level = AlertLevel.CELL_FAULT), nowMillis = 10L)

        // Every subsequent frame, well inside the cadence.
        listOf(11L, 12L, 13L).forEach { at ->
            assertEquals(
                BlackboxTrigger.HIGH_RESOLUTION,
                sampler.triggerFor(sample(level = AlertLevel.CELL_FAULT), nowMillis = at),
            )
        }
    }

    @Test
    fun `a notice does not start full-resolution logging`() {
        sampler.accept(sample(), nowMillis = 0L)
        sampler.accept(sample(level = AlertLevel.NOTICE), nowMillis = 10L)
        assertNull(sampler.triggerFor(sample(level = AlertLevel.NOTICE), nowMillis = 11L))
    }

    @Test
    fun `an emergency keeps every frame`() {
        sampler.accept(sample(), nowMillis = 0L)
        sampler.accept(sample(level = AlertLevel.EMERGENCY), nowMillis = 10L)
        assertEquals(
            BlackboxTrigger.HIGH_RESOLUTION,
            sampler.triggerFor(sample(level = AlertLevel.EMERGENCY), nowMillis = 11L),
        )
    }

    /**
     * A device whose clock is corrected mid-flight — an NTP sync or a manual change — must
     * not lock the cadence out for the rest of the session.
     */
    @Test
    fun `a clock that steps backwards does not stop the log`() {
        sampler.accept(sample(), nowMillis = 1_000_000L)
        assertEquals(
            BlackboxTrigger.CADENCE,
            sampler.triggerFor(sample(), nowMillis = 500L),
        )
    }

    @Test
    fun `the cadence is configurable`() {
        val fast = BlackboxSampler(cadenceMillis = 200L)
        fast.accept(sample(), nowMillis = 0L)
        assertNull(fast.triggerFor(sample(), nowMillis = 199L))
        assertEquals(BlackboxTrigger.CADENCE, fast.triggerFor(sample(), nowMillis = 200L))
    }

    @Test
    fun `the trigger decision alone records nothing`() {
        // Asking twice without accepting must give the same answer: the sampler is only told
        // a frame was kept once the recorder has actually queued it.
        assertEquals(BlackboxTrigger.SESSION_START, sampler.triggerFor(sample(), nowMillis = 5L))
        assertEquals(BlackboxTrigger.SESSION_START, sampler.triggerFor(sample(), nowMillis = 5L))
    }
}
