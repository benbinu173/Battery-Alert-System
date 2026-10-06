package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.BatteryAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The alert matrix, asserted rule by rule, and then asserted again for what happens when
 * several rules fire together — which is the case the requirements do not cover and the
 * case that decides what the operator actually reads.
 */
class AlertEngineTest {

    private companion object {
        /** A pack that is healthy on every axis the engine looks at. */
        val HEALTHY = AlertInputs(
            batteryPercentage = 80.0,
            weakestCellVoltsUnderLoad = 3.90,
            cellDeltaVolts = 0.01,
            weakestCellNumber = 3,
            rtl = null,
            declineVoltsPerCellPerSecond = 0.0,
        )

        fun rtlRequiring(requiredPercent: Double) = RtlAssessment(
            requiredPercent = requiredPercent,
            tripPercent = requiredPercent - 15.0,
            safetyMarginPercent = 15.0,
            timeToHomeSeconds = 300.0,
            percentPerMinute = 5.0,
        )

        fun rulesOf(alerts: List<BatteryAlert>) = alerts.map { it.rule }
    }

    // --- Nothing is claimed when nothing is wrong ----------------------------------

    @Test
    fun `a healthy pack raises nothing`() {
        assertTrue(AlertEngine.evaluate(HEALTHY).isEmpty())
        assertEquals(AlertLevel.NORMAL, AlertEngine.levelOf(AlertEngine.evaluate(HEALTHY)))
    }

    @Test
    fun `an entirely unknown pack raises nothing`() {
        // Every field missing. The engine must not default into a colour: an alert on
        // screen has to name the number that caused it.
        val alerts = AlertEngine.evaluate(AlertInputs())
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a value that is not a number is ignored, not treated as zero`() {
        // NaN would compare false against every threshold and silently pass; a naive
        // implementation that coerced it to 0.0 would scream Emergency.
        val alerts = AlertEngine.evaluate(
            AlertInputs(
                batteryPercentage = Double.NaN,
                weakestCellVoltsUnderLoad = Double.NaN,
                cellDeltaVolts = Double.NaN,
            ),
        )
        assertTrue(alerts.isEmpty())
    }

    // --- Charge percentage ---------------------------------------------------------

    @Test
    fun `thirty percent is a notice`() {
        assertEquals(
            listOf(AlertRule.CHARGE_NOTICE),
            rulesOf(AlertEngine.evaluate(HEALTHY.copy(batteryPercentage = 28.0))),
        )
    }

    @Test
    fun `the notice boundary is inclusive`() {
        assertEquals(
            listOf(AlertRule.CHARGE_NOTICE),
            rulesOf(AlertEngine.evaluate(HEALTHY.copy(batteryPercentage = 30.0))),
        )
        assertTrue(AlertEngine.evaluate(HEALTHY.copy(batteryPercentage = 30.1)).isEmpty())
    }

    @Test
    fun `twenty percent is a warning`() {
        assertEquals(
            listOf(AlertRule.CHARGE_WARNING),
            rulesOf(AlertEngine.evaluate(HEALTHY.copy(batteryPercentage = 20.0))),
        )
    }

    @Test
    fun `a warning does not also report the notice underneath it`() {
        // At 18% both rules are literally true. Reporting both is noise at the moment the
        // operator can least afford it.
        val rules = rulesOf(AlertEngine.evaluate(HEALTHY.copy(batteryPercentage = 18.0)))
        assertEquals(listOf(AlertRule.CHARGE_WARNING), rules)
    }

    // --- Cell voltage --------------------------------------------------------------

    @Test
    fun `3_65 volts per cell is a warning`() {
        assertEquals(
            listOf(AlertRule.CELL_VOLTAGE_WARNING),
            rulesOf(AlertEngine.evaluate(HEALTHY.copy(weakestCellVoltsUnderLoad = 3.65))),
        )
    }

    @Test
    fun `3_50 volts per cell escalates to critical`() {
        assertEquals(
            listOf(AlertRule.CELL_VOLTAGE_CRITICAL),
            rulesOf(AlertEngine.evaluate(HEALTHY.copy(weakestCellVoltsUnderLoad = 3.50))),
        )
    }

    @Test
    fun `3_40 volts per cell escalates to emergency`() {
        assertEquals(
            listOf(AlertRule.CELL_VOLTAGE_EMERGENCY),
            rulesOf(AlertEngine.evaluate(HEALTHY.copy(weakestCellVoltsUnderLoad = 3.40))),
        )
    }

    @Test
    fun `each voltage band reports only its own rule`() {
        // Bands are exclusive: a cell at 3.30 V is an emergency, not an emergency plus a
        // critical plus a warning.
        val emergency = AlertEngine.evaluate(HEALTHY.copy(weakestCellVoltsUnderLoad = 3.30))
        assertEquals(1, emergency.size)
        assertEquals(AlertLevel.EMERGENCY, emergency.first().level)
    }

    // --- Cell fault ----------------------------------------------------------------

    @Test
    fun `an imbalance over the threshold is a cell fault`() {
        val alerts = AlertEngine.evaluate(
            HEALTHY.copy(cellDeltaVolts = 0.081, weakestCellNumber = 6),
        )
        assertEquals(listOf(AlertRule.CELL_IMBALANCE), rulesOf(alerts))
        assertEquals(AlertLevel.CELL_FAULT, alerts.first().level)
    }

    @Test
    fun `the imbalance boundary is strict`() {
        // The requirement says ΔV > 0.08 V. Exactly 0.08 V is not yet a fault.
        assertTrue(
            AlertEngine.evaluate(HEALTHY.copy(cellDeltaVolts = 0.08)).isEmpty(),
        )
    }

    @Test
    fun `an imbalance is reported even when the pack looks charged`() {
        // The Cell Imbalance demo scenario: 75% charge, healthy cell voltages, one dying
        // cell. No percentage rule or voltage rule would ever see this.
        val alerts = AlertEngine.evaluate(
            AlertInputs(
                batteryPercentage = 75.0,
                weakestCellVoltsUnderLoad = 3.77,
                cellDeltaVolts = 0.105,
                weakestCellNumber = 6,
                rtl = null,
                declineVoltsPerCellPerSecond = 0.0,
            ),
        )
        assertEquals(listOf(AlertRule.CELL_IMBALANCE), rulesOf(alerts))
    }

    @Test
    fun `the imbalance message names the failing cell`() {
        val alerts = AlertEngine.evaluate(HEALTHY.copy(cellDeltaVolts = 0.105, weakestCellNumber = 6))
        assertEquals("Cell 6 is 105 mV below the pack.", alerts.first().message)
    }

    @Test
    fun `an imbalance with no identified cell still reports the spread`() {
        val alerts = AlertEngine.evaluate(HEALTHY.copy(cellDeltaVolts = 0.105, weakestCellNumber = null))
        assertEquals("Cell spread is 105 mV below the pack.", alerts.first().message)
    }

    // --- FR 3.2 dynamic RTL --------------------------------------------------------

    @Test
    fun `charge below the dynamic requirement is critical`() {
        val alerts = AlertEngine.evaluate(
            HEALTHY.copy(batteryPercentage = 26.0, rtl = rtlRequiring(27.5)),
        )
        assertEquals(AlertLevel.CRITICAL, AlertEngine.levelOf(alerts))
        assertEquals(AlertRule.RTL_REQUIRED, alerts.first().rule)
        // 26% is also under the 30% notice line, and that remains true and reported — the
        // RTL rule does not replace it, it outranks it.
        assertTrue(alerts.any { it.rule == AlertRule.CHARGE_NOTICE })
    }

    @Test
    fun `the rtl rule outranks the charge notice it overlaps with`() {
        // Every realistic RTL trigger sits below the notice percentage, so these two rules
        // fire together almost always. Ordering, not filtering, is what keeps the headline
        // useful.
        val alerts = AlertEngine.evaluate(
            HEALTHY.copy(batteryPercentage = 26.0, rtl = rtlRequiring(27.5)),
        )
        assertEquals(listOf(AlertRule.RTL_REQUIRED, AlertRule.CHARGE_NOTICE), rulesOf(alerts))
    }

    @Test
    fun `the rtl message names both the remaining charge and the requirement`() {
        // The operator has to be able to check the arithmetic that is telling them to
        // turn around, so both numbers must be in the sentence.
        val alerts = AlertEngine.evaluate(
            HEALTHY.copy(batteryPercentage = 26.0, rtl = rtlRequiring(27.5)),
        )
        assertEquals("26% remaining, 28% needed to reach home.", alerts.first().message)
    }

    @Test
    fun `charge above the requirement raises nothing`() {
        assertTrue(
            AlertEngine.evaluate(HEALTHY.copy(batteryPercentage = 40.0, rtl = rtlRequiring(27.5)))
                .isEmpty(),
        )
    }

    @Test
    fun `an rtl requirement with no charge reading raises nothing`() {
        // isRequired() is false for an unknown percentage. The engine must not guess.
        assertTrue(
            AlertEngine.evaluate(
                HEALTHY.copy(batteryPercentage = null, rtl = rtlRequiring(27.5)),
            ).isEmpty(),
        )
    }

    @Test
    fun `an rtl context with no assessment raises nothing`() {
        assertTrue(
            AlertEngine.evaluate(HEALTHY.copy(batteryPercentage = 10.0, rtl = null))
                .none { it.rule == AlertRule.RTL_REQUIRED },
        )
    }

    // --- Rapid sag -----------------------------------------------------------------

    @Test
    fun `a collapsing pack is an emergency`() {
        val alerts = AlertEngine.evaluate(HEALTHY.copy(declineVoltsPerCellPerSecond = 0.06))
        assertEquals(listOf(AlertRule.RAPID_DECLINE), rulesOf(alerts))
        assertEquals(AlertLevel.EMERGENCY, alerts.first().level)
    }

    @Test
    fun `a fast but honest discharge is not a collapse`() {
        assertTrue(
            AlertEngine.evaluate(HEALTHY.copy(declineVoltsPerCellPerSecond = 0.04)).isEmpty(),
        )
    }

    @Test
    fun `a recovering pack is never a collapse`() {
        assertTrue(
            AlertEngine.evaluate(HEALTHY.copy(declineVoltsPerCellPerSecond = -0.2)).isEmpty(),
        )
    }

    // --- Arbitration ---------------------------------------------------------------

    @Test
    fun `the worst condition is reported first`() {
        val alerts = AlertEngine.evaluate(
            HEALTHY.copy(batteryPercentage = 18.0, weakestCellVoltsUnderLoad = 3.30),
        )
        assertEquals(2, alerts.size)
        assertEquals(AlertRule.CELL_VOLTAGE_EMERGENCY, alerts.first().rule)
        assertEquals(AlertLevel.EMERGENCY, AlertEngine.levelOf(alerts))
    }

    @Test
    fun `every active condition is returned, not just the worst`() {
        // Imbalanced at 18% charge: two genuinely different problems, and the one that
        // explains why the operator should not simply turn around and keep flying is the
        // cell fault. Returning only the headline would discard it.
        val alerts = AlertEngine.evaluate(
            HEALTHY.copy(batteryPercentage = 18.0, cellDeltaVolts = 0.12, weakestCellNumber = 6),
        )
        assertEquals(
            listOf(AlertRule.CHARGE_WARNING, AlertRule.CELL_IMBALANCE),
            rulesOf(alerts),
        )
    }

    @Test
    fun `ties at the same level report the situation-aware rule first`() {
        // 26% with a 27.5% requirement AND a cell at 3.48 V. Both are Critical. The RTL
        // rule is listed first because it accounts for how far from home the aircraft
        // actually is, so it is the more specific statement of the same severity.
        val alerts = AlertEngine.evaluate(
            AlertInputs(
                batteryPercentage = 26.0,
                weakestCellVoltsUnderLoad = 3.48,
                cellDeltaVolts = 0.03,
                weakestCellNumber = 2,
                rtl = rtlRequiring(27.5),
                declineVoltsPerCellPerSecond = 0.0,
            ),
        )
        assertEquals(AlertLevel.CRITICAL, AlertEngine.levelOf(alerts))
        assertEquals(AlertRule.RTL_REQUIRED, alerts.first().rule)
        assertTrue(alerts.any { it.rule == AlertRule.CELL_VOLTAGE_CRITICAL })
        assertFalse(alerts.any { it.level == AlertLevel.WARNING })
    }

    @Test
    fun `the ordering is by severity, not by the order the rules are checked`() {
        // A cell fault is checked first in the engine but must not outrank a warning.
        val alerts = AlertEngine.evaluate(
            HEALTHY.copy(batteryPercentage = 15.0, cellDeltaVolts = 0.12),
        )
        assertEquals(AlertRule.CHARGE_WARNING, alerts.first().rule)
    }

    @Test
    fun `every alert carries an action the operator can take`() {
        val alerts = AlertEngine.evaluate(
            AlertInputs(
                batteryPercentage = 15.0,
                weakestCellVoltsUnderLoad = 3.30,
                cellDeltaVolts = 0.12,
                weakestCellNumber = 6,
                rtl = rtlRequiring(40.0),
                declineVoltsPerCellPerSecond = 0.09,
            ),
        )
        assertEquals(5, alerts.size)
        alerts.forEach { alert ->
            assertTrue("${alert.rule} has no action prompt", alert.action.isNotBlank())
            assertTrue("${alert.rule} has no message", alert.message.isNotBlank())
        }
    }

    // --- Thresholds are a configuration point --------------------------------------

    @Test
    fun `the same reading changes rule when a threshold moves`() {
        // The requirement's numbers are defaults, not constants baked into the logic.
        val at15Percent = HEALTHY.copy(batteryPercentage = 15.0)

        // Under the specified 20% warning line, 15% is a warning.
        assertEquals(
            listOf(AlertRule.CHARGE_WARNING),
            rulesOf(AlertEngine.evaluate(at15Percent)),
        )

        // With the warning line lowered to 10%, the very same reading is only a notice —
        // still above the line, and still under the 30% notice line above it.
        assertEquals(
            listOf(AlertRule.CHARGE_NOTICE),
            rulesOf(AlertEngine.evaluate(at15Percent, AlertThresholds(warningPercent = 10.0))),
        )
    }

    @Test
    fun `a voltage threshold moves without disturbing the charge rules`() {
        // A healthy pack by every other measure, on a threshold set nervous enough to call
        // 3.90 V low. Only the voltage rule should move.
        val nervous = AlertThresholds(warningVoltsPerCell = 3.95)
        val alerts = AlertEngine.evaluate(
            HEALTHY.copy(weakestCellVoltsUnderLoad = 3.90),
            nervous,
        )
        assertEquals(listOf(AlertRule.CELL_VOLTAGE_WARNING), rulesOf(alerts))
    }

    // --- Presentation guarantees ---------------------------------------------------

    @Test
    fun `numbers use a decimal point whatever the device locale is`() {
        // "3,48 V" on a comma-decimal device would be a different number to a pilot
        // reading a foreign controller. The formatting is pinned to US English so the
        // decimal point is part of the claim rather than a display preference.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val alerts = AlertEngine.evaluate(HEALTHY.copy(weakestCellVoltsUnderLoad = 3.48))
            assertEquals("Weakest cell at 3.48 V under load.", alerts.first().message)
            assertFalse(alerts.first().message.contains("3,48"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
