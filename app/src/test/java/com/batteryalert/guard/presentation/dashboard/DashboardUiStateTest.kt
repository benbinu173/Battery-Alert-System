package com.batteryalert.guard.presentation.dashboard

import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.BatteryAlert
import com.batteryalert.guard.presentation.CELL_RULES
import com.batteryalert.guard.presentation.PACK_VOLTAGE_RULES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state object is pure Kotlin, so the scoping rules that decide what colour each card
 * gets can be tested on the JVM rather than by eye on a device.
 *
 * The behaviour under test is the one that is easiest to get wrong and hardest to notice:
 * a card going red for a reason that has nothing to do with it.
 */
class DashboardUiStateTest {

    private companion object {
        fun alert(rule: AlertRule) = BatteryAlert(rule = rule, message = "m", action = "a")
    }

    @Test
    fun `an empty state is normal for every scope`() {
        val state = DashboardUiState()
        assertEquals(AlertLevel.NORMAL, state.alertLevel)
        assertNull(state.primaryAlert)
        assertEquals(AlertLevel.NORMAL, state.levelFor(CELL_RULES))
        assertEquals(AlertLevel.NORMAL, state.levelFor(PACK_VOLTAGE_RULES))
        assertEquals(AlertLevel.NORMAL, state.levelFor(AlertRule.RTL_REQUIRED))
    }

    @Test
    fun `the headline is the worst alert in the list`() {
        val state = DashboardUiState(
            alerts = listOf(alert(AlertRule.RTL_REQUIRED), alert(AlertRule.CHARGE_NOTICE)),
        )
        assertEquals(AlertLevel.CRITICAL, state.alertLevel)
        assertEquals(AlertRule.RTL_REQUIRED, state.primaryAlert?.rule)
    }

    // --- The regression this scoping exists to prevent ------------------------------

    @Test
    fun `an rtl alert does not colour the cell rules`() {
        // The RTL Required demo scenario: 2.4 km from home at a healthy 26%. Nothing is
        // wrong with the cells, and the cell-balance chart must not turn red and say so.
        val state = DashboardUiState(
            alerts = listOf(alert(AlertRule.RTL_REQUIRED), alert(AlertRule.CHARGE_NOTICE)),
        )
        assertEquals(AlertLevel.NORMAL, state.levelFor(CELL_RULES))
        assertEquals(AlertLevel.NORMAL, state.levelFor(PACK_VOLTAGE_RULES))
        assertEquals(AlertLevel.CRITICAL, state.levelFor(AlertRule.RTL_REQUIRED))
    }

    @Test
    fun `a cell fault does not colour the rtl threshold bar`() {
        // The Cell Imbalance scenario: 75% charge, plenty of flight left. The threshold bar
        // compares charge against the requirement and has nothing to say about a bad cell.
        val state = DashboardUiState(alerts = listOf(alert(AlertRule.CELL_IMBALANCE)))
        assertEquals(AlertLevel.CELL_FAULT, state.levelFor(CELL_RULES))
        assertEquals(AlertLevel.NORMAL, state.levelFor(AlertRule.RTL_REQUIRED))
    }

    @Test
    fun `a rapid sag colours the pack trend but not the cell chart`() {
        // Rapid sag is about what the pack is delivering, not about cell balance — a pack
        // can collapse with every cell matched.
        val state = DashboardUiState(alerts = listOf(alert(AlertRule.RAPID_DECLINE)))
        assertEquals(AlertLevel.EMERGENCY, state.levelFor(PACK_VOLTAGE_RULES))
        assertEquals(AlertLevel.NORMAL, state.levelFor(CELL_RULES))
    }

    // --- Scoping picks the worst within the scope -----------------------------------

    @Test
    fun `a scope reports its worst member, not its first`() {
        val state = DashboardUiState(
            alerts = listOf(
                alert(AlertRule.CELL_VOLTAGE_CRITICAL),
                alert(AlertRule.CELL_IMBALANCE),
            ),
        )
        assertEquals(AlertLevel.CRITICAL, state.levelFor(CELL_RULES))
    }

    @Test
    fun `a cell voltage alert scopes to both the cells and the pack`() {
        // It is a statement about the cells, and it is also a statement about the voltage
        // the pack is delivering, so both cards legitimately answer to it.
        val state = DashboardUiState(alerts = listOf(alert(AlertRule.CELL_VOLTAGE_EMERGENCY)))
        assertEquals(AlertLevel.EMERGENCY, state.levelFor(CELL_RULES))
        assertEquals(AlertLevel.EMERGENCY, state.levelFor(PACK_VOLTAGE_RULES))
        assertEquals(AlertLevel.NORMAL, state.levelFor(AlertRule.RTL_REQUIRED))
    }

    @Test
    fun `scopes never exceed the headline`() {
        // A card must never be louder than the banner above it.
        val state = DashboardUiState(
            alerts = listOf(
                alert(AlertRule.RTL_REQUIRED),
                alert(AlertRule.CELL_IMBALANCE),
                alert(AlertRule.CHARGE_NOTICE),
            ),
        )
        listOf(CELL_RULES, PACK_VOLTAGE_RULES).forEach { scope ->
            assertTrue(
                "$scope is louder than the headline",
                state.levelFor(scope).severity <= state.alertLevel.severity,
            )
        }
    }

    // --- Completeness ----------------------------------------------------------------

    @Test
    fun `every rule either colours a card or is deliberately headline-only`() {
        // Adding a rule to the matrix and forgetting to decide what it colours would
        // otherwise mean a condition that changes the banner and nothing else. This fails
        // until the decision is made explicitly, one way or the other.
        val scoped = CELL_RULES + PACK_VOLTAGE_RULES + AlertRule.RTL_REQUIRED
        val headlineOnly = setOf(AlertRule.CHARGE_NOTICE, AlertRule.CHARGE_WARNING)
        assertEquals(AlertRule.entries.toSet(), scoped + headlineOnly)
    }
}
