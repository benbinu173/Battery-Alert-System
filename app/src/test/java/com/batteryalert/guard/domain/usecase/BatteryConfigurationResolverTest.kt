package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.BatteryChemistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryConfigurationResolverTest {

    @Test
    fun `counts cells from cell telemetry rather than inferring them`() {
        val resolved = BatteryConfigurationResolver.resolve(
            cellVoltages = List(12) { 3.90 },
        )!!

        assertEquals(12, resolved.cellCount)
        assertEquals(ConfigurationBasis.CELL_TELEMETRY, resolved.basis)
    }

    @Test
    fun `does not claim a chemistry that voltage cannot prove`() {
        // 3.90 V/cell is a perfectly ordinary Li-ion or LiPo reading. Both chemistries
        // share nominal and full per-cell voltages, so neither can be asserted.
        val resolved = BatteryConfigurationResolver.resolve(cellVoltages = List(12) { 3.90 })!!

        assertNull(resolved.chemistry)
        assertFalse(resolved.chemistryIsCertain)
        assertEquals("12S", resolved.label)
    }

    @Test
    fun `cell telemetry still works when the pack is nearly empty`() {
        // The baseline-voltage path would mis-detect here; counting cells does not.
        val resolved = BatteryConfigurationResolver.resolve(cellVoltages = List(12) { 3.25 })!!

        assertEquals(12, resolved.cellCount)
        assertEquals(ConfigurationBasis.CELL_TELEMETRY, resolved.basis)
    }

    @Test
    fun `proves LiHV when the voltage is past what Li-ion can reach`() {
        val resolved = BatteryConfigurationResolver.resolve(cellVoltages = List(6) { 4.30 })!!

        assertEquals(6, resolved.cellCount)
        assertEquals(BatteryChemistry.LI_HV, resolved.chemistry)
        assertTrue(resolved.chemistryIsCertain)
        assertEquals("6S LiHV", resolved.label)
    }

    @Test
    fun `falls back to the baseline voltage when no cells are reported`() {
        val resolved = BatteryConfigurationResolver.resolve(baselinePackVolts = 50.4)!!

        assertEquals(12, resolved.cellCount)
        assertEquals(ConfigurationBasis.BASELINE_VOLTAGE, resolved.basis)
    }

    @Test
    fun `zeroed padding cells do not masquerade as telemetry`() {
        // Twelve zeros is a source that is not reporting cells, not a 12S pack at 0 V.
        val resolved = BatteryConfigurationResolver.resolve(
            cellVoltages = List(12) { 0.0 },
            baselinePackVolts = 50.4,
        )!!

        assertEquals(ConfigurationBasis.BASELINE_VOLTAGE, resolved.basis)
    }

    @Test
    fun `returns null when nothing usable is available`() {
        assertNull(BatteryConfigurationResolver.resolve())
        assertNull(BatteryConfigurationResolver.resolve(cellVoltages = List(12) { 0.0 }))
        assertNull(BatteryConfigurationResolver.resolve(baselinePackVolts = 9.9))
    }
}
