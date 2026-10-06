package com.batteryalert.guard.presentation

import androidx.compose.ui.graphics.Color
import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.presentation.theme.GuardColors

/**
 * Maps an alert level the domain layer already decided onto the colour that renders it.
 *
 * This is deliberately the *only* place UI code touches a semantic colour. Compose never
 * evaluates a threshold; it reads [AlertLevel] and looks up a swatch. The colour on screen
 * is therefore always traceable to a rule the alert engine actually fired.
 */
fun AlertLevel.indicatorColor(): Color = when (this) {
    AlertLevel.NORMAL -> GuardColors.Accent
    AlertLevel.NOTICE -> GuardColors.NoticeYellow
    AlertLevel.CELL_FAULT -> GuardColors.CellFaultOrange
    AlertLevel.WARNING -> GuardColors.WarningAmber
    AlertLevel.CRITICAL -> GuardColors.CriticalRed
    AlertLevel.EMERGENCY -> GuardColors.EmergencyRed
}

/** Short operator-facing label for the level, used on the alert banner. */
fun AlertLevel.label(): String = when (this) {
    AlertLevel.NORMAL -> "Normal"
    AlertLevel.NOTICE -> "Notice"
    AlertLevel.CELL_FAULT -> "Cell fault"
    AlertLevel.WARNING -> "Warning"
    AlertLevel.CRITICAL -> "Critical"
    AlertLevel.EMERGENCY -> "Emergency"
}

/**
 * Three-or-four word name for a specific rule, for the compact chip row under the banner.
 *
 * Deliberately names the *condition* rather than repeating the level: the banner has
 * already said "Critical", so the useful second line says "which critical".
 */
fun AlertRule.shortLabel(): String = when (this) {
    AlertRule.CHARGE_NOTICE -> "Charge low"
    AlertRule.CELL_IMBALANCE -> "Cell imbalance"
    AlertRule.CHARGE_WARNING -> "Charge critical"
    AlertRule.CELL_VOLTAGE_WARNING -> "Cell voltage"
    AlertRule.RTL_REQUIRED -> "RTL required"
    AlertRule.CELL_VOLTAGE_CRITICAL -> "Cell voltage"
    AlertRule.CELL_VOLTAGE_EMERGENCY -> "Cell voltage"
    AlertRule.RAPID_DECLINE -> "Rapid sag"
}

// --- Which alert is allowed to colour which card -------------------------------------
// Each dashboard card answers a different question, so each card must be coloured by the
// rules that are actually about that question. The alternative — every card taking the
// global worst level — means a Critical raised because the aircraft is 2.4 km from home
// also turns the cell-balance chart red, which reads as a failing cell. That is a false
// safety claim dressed as a colour, and it is worse than no colour at all.

/** Rules whose subject is the cells themselves. */
val CELL_RULES: Set<AlertRule> = setOf(
    AlertRule.CELL_IMBALANCE,
    AlertRule.CELL_VOLTAGE_WARNING,
    AlertRule.CELL_VOLTAGE_CRITICAL,
    AlertRule.CELL_VOLTAGE_EMERGENCY,
)

/** Rules whose subject is the voltage the pack is delivering. */
val PACK_VOLTAGE_RULES: Set<AlertRule> = CELL_RULES + AlertRule.RAPID_DECLINE

/**
 * Swatch for the spray interlock (FR 5.1).
 *
 * Takes the decision, not the charge: whether spraying is inhibited was settled by
 * `SprayInterlock` in the domain, and this only chooses what that means on screen. Kept
 * here with the other semantic colours so there is still exactly one place in the UI that
 * knows what a safety state looks like.
 */
fun interlockColor(inhibited: Boolean): Color =
    if (inhibited) GuardColors.WarningAmber else GuardColors.Healthy
