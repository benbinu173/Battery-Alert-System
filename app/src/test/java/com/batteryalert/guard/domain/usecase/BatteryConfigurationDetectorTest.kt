package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.BatteryChemistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryConfigurationDetectorTest {

    @Test
    fun `detects a fully charged 12S pack`() {
        // 12 x 4.20 = 50.4 V — the same pack the simulator models.
        val result = BatteryConfigurationDetector.detect(50.4)!!

        assertEquals(12, result.configuration.cellCount)
        assertEquals(50.4, result.configuration.fullPackVolts, 1e-9)
        assertEquals(0.0, result.errorVoltsPerCell, 1e-9)
    }

    @Test
    fun `detects a fully charged 6S pack`() {
        val result = BatteryConfigurationDetector.detect(25.2)!!
        assertEquals(6, result.configuration.cellCount)
    }

    @Test
    fun `detects a fully charged 14S pack`() {
        val result = BatteryConfigurationDetector.detect(58.8)!!
        assertEquals(14, result.configuration.cellCount)
    }

    @Test
    fun `prefers the chemistry whose full-charge voltage actually matches`() {
        // 6 x 4.35 = 26.1 V is a full LiHV pack. Li-ion and LiPo sit 0.15 V/cell away,
        // so LiHV has to win despite being declared last.
        val result = BatteryConfigurationDetector.detect(26.1)!!

        assertEquals(6, result.configuration.cellCount)
        assertEquals(BatteryChemistry.LI_HV, result.configuration.chemistry)
    }

    @Test
    fun `nominal reference resolves a partially discharged pack`() {
        // 22.2 V is 3.70 V/cell across 6S — nominal, not full. Under the default
        // full-charge reference every cell count is out of tolerance, so this returns
        // null rather than a confident wrong answer.
        assertNull(BatteryConfigurationDetector.detect(22.2))

        val nominal = BatteryConfigurationDetector.detect(
            baselinePackVolts = 22.2,
            reference = BatteryConfigurationDetector.Reference.NOMINAL,
        )!!
        assertEquals(6, nominal.configuration.cellCount)
        assertEquals(0.0, nominal.errorVoltsPerCell, 1e-9)
    }

    @Test
    fun `rejects voltages that fit no supported chemistry`() {
        assertNull(BatteryConfigurationDetector.detect(9.9))
        assertNull(BatteryConfigurationDetector.detect(500.0))
    }

    @Test
    fun `returns null for missing or unusable baseline`() {
        assertNull(BatteryConfigurationDetector.detect(null))
        assertNull(BatteryConfigurationDetector.detect(0.0))
        assertNull(BatteryConfigurationDetector.detect(-50.0))
        assertNull(BatteryConfigurationDetector.detect(Double.NaN))
        assertNull(BatteryConfigurationDetector.detect(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `custom thresholds are honoured`() {
        // Restricting the search to small packs must stop 12S from being found.
        val narrow = BatteryConfigurationDetector.detect(
            baselinePackVolts = 50.4,
            thresholds = BatteryConfigurationDetector.DEFAULT_THRESHOLDS.copy(candidateCellCounts = 2..8),
        )
        assertNull(narrow)
    }

    @Test
    fun `reports detection error so a weak match is visible`() {
        // 49.98 V is 4.165 V/cell across 12S — a good but not exact match.
        val result = BatteryConfigurationDetector.detect(49.98)!!

        assertEquals(12, result.configuration.cellCount)
        assertTrue("expected a small non-zero error", result.errorVoltsPerCell > 0.0)
        assertTrue("expected the match to stay inside tolerance", result.errorVoltsPerCell < 0.25)
    }
}
