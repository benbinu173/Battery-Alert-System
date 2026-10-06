package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.BlackboxRecord
import com.batteryalert.guard.domain.model.BlackboxSample
import com.batteryalert.guard.domain.model.BlackboxTrigger
import com.batteryalert.guard.domain.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reduction the flight summary is built from.
 *
 * The point of testing arithmetic this simple is that it is the arithmetic an operator will
 * read *instead of* the log. A summary that quietly reports the wrong minimum is worse than
 * no summary, because it will be believed.
 */
class BlackboxAnalysisTest {

    private fun record(
        atMillis: Long,
        level: AlertLevel = AlertLevel.NORMAL,
        rule: AlertRule? = null,
        percentage: Double? = 90.0,
        packVolts: Double? = 48.0,
        cellVolts: Double? = 4.00,
        amps: Double? = 10.0,
        celsius: Double? = 30.0,
        distance: Double? = 100.0,
        connection: ConnectionState = ConnectionState.CONNECTED,
        gpsLocked: Boolean = true,
        sessionId: Long = 1L,
    ) = BlackboxRecord(
        timestampMillis = atMillis,
        sessionId = sessionId,
        trigger = BlackboxTrigger.CADENCE,
        sample = BlackboxSample(
            connectionState = connection,
            gpsLocked = gpsLocked,
            batteryPercentage = percentage,
            packVolts = packVolts,
            currentAmps = amps,
            temperatureCelsius = celsius,
            weakestCellVolts = cellVolts,
            distanceToHomeMeters = distance,
            alertLevel = level,
            alertRule = rule,
        ),
    )

    @Test
    fun `an empty log has no summary`() {
        assertNull(BlackboxAnalysis.summarise(emptyList()))
    }

    @Test
    fun `a single row summarises as a flight of one sample`() {
        val summary = BlackboxAnalysis.summarise(listOf(record(atMillis = 1_000L)))!!
        assertEquals(1, summary.sampleCount)
        assertEquals(0L, summary.durationMillis)
        assertEquals(AlertLevel.NORMAL, summary.worstLevel)
    }

    @Test
    fun `the worst level is the worst, not the last`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(
                record(0L, level = AlertLevel.NORMAL),
                record(1_000L, level = AlertLevel.CRITICAL, rule = AlertRule.RTL_REQUIRED),
                record(2_000L, level = AlertLevel.NOTICE, rule = AlertRule.CHARGE_NOTICE),
            ),
        )!!
        assertEquals(AlertLevel.CRITICAL, summary.worstLevel)
    }

    @Test
    fun `rules are reported in the order the flight met them, without repeats`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(
                record(0L, level = AlertLevel.CELL_FAULT, rule = AlertRule.CELL_IMBALANCE),
                record(1_000L, level = AlertLevel.NORMAL),
                record(2_000L, level = AlertLevel.CELL_FAULT, rule = AlertRule.CELL_IMBALANCE),
                record(3_000L, level = AlertLevel.WARNING, rule = AlertRule.CHARGE_WARNING),
            ),
        )!!
        assertEquals(
            listOf(AlertRule.CELL_IMBALANCE, AlertRule.CHARGE_WARNING),
            summary.rulesRaised,
        )
    }

    @Test
    fun `lowest values are minima across the whole flight`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(
                record(0L, percentage = 90.0, packVolts = 48.0, cellVolts = 4.00),
                record(1_000L, percentage = 22.0, packVolts = 42.1, cellVolts = 3.51),
                record(2_000L, percentage = 61.0, packVolts = 46.0, cellVolts = 3.90),
            ),
        )!!
        assertEquals(22.0, summary.lowestPercentage!!, 1e-9)
        assertEquals(42.1, summary.lowestPackVolts!!, 1e-9)
        assertEquals(3.51, summary.lowestCellVolts!!, 1e-9)
    }

    /** "Unknown" must not become zero — the one convention the whole codebase shares. */
    @Test
    fun `a field no row reported summarises as unknown, not as zero`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(
                record(0L, percentage = null, cellVolts = null, celsius = null),
                record(1_000L, percentage = null, cellVolts = null, celsius = null),
            ),
        )!!
        assertNull(summary.lowestPercentage)
        assertNull(summary.lowestCellVolts)
        assertNull(summary.peakTemperatureCelsius)
    }

    @Test
    fun `an unknown field does not win the minimum`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(
                record(0L, packVolts = null),
                record(1_000L, packVolts = 44.0),
            ),
        )!!
        assertEquals(44.0, summary.lowestPackVolts!!, 1e-9)
    }

    /**
     * Current is stored as positive discharge today, but the sign convention belongs to the
     * source. Taking the magnitude means a source that reports charge as positive cannot
     * make "peak current" report the largest charge instead of the largest draw.
     */
    @Test
    fun `peak current is a magnitude`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(
                record(0L, amps = 10.0),
                record(1_000L, amps = -42.5),
                record(2_000L, amps = 18.0),
            ),
        )!!
        assertEquals(42.5, summary.peakAmps!!, 1e-9)
    }

    @Test
    fun `the furthest distance is the maximum`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(record(0L, distance = 100.0), record(1_000L, distance = 2_400.0)),
        )!!
        assertEquals(2_400.0, summary.maxDistanceFromHomeMeters!!, 1e-9)
    }

    @Test
    fun `a flight that never lost the link or the fix held throughout`() {
        val summary = BlackboxAnalysis.summarise(listOf(record(0L), record(1_000L)))!!
        assertTrue(summary.linkHeldThroughout)
    }

    @Test
    fun `a dropped link is not a held link`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(record(0L), record(1_000L, connection = ConnectionState.ERROR)),
        )!!
        assertFalse(summary.linkHeldThroughout)
    }

    @Test
    fun `a lost gps fix is not a held link`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(record(0L), record(1_000L, gpsLocked = false)),
        )!!
        assertFalse(summary.linkHeldThroughout)
    }

    @Test
    fun `rows are ordered by time whatever order they arrive in`() {
        val summary = BlackboxAnalysis.summarise(
            listOf(
                record(5_000L, sessionId = 7L),
                record(1_000L, sessionId = 7L),
                record(9_000L, sessionId = 7L),
            ),
        )!!
        assertEquals(1_000L, summary.startedAtMillis)
        assertEquals(9_000L, summary.endedAtMillis)
        assertEquals(8_000L, summary.durationMillis)
        assertEquals(7L, summary.sessionId)
    }
}
