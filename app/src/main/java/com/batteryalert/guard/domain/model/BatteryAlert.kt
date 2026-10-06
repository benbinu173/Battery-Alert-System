package com.batteryalert.guard.domain.model

/**
 * The named conditions the alert engine can raise, and the level each one carries.
 *
 * Declared in the order they are reported when several fire at the same level. That
 * ordering is a judgement, not something the requirements state: RTL is listed ahead of
 * the fixed cell-voltage rules because it is the situation-aware one — it accounts for
 * how far the aircraft actually is from home — so it is the more specific statement when
 * both agree the situation is Critical.
 */
enum class AlertRule(val level: AlertLevel) {
    /** FR: Notice at 30% remaining. */
    CHARGE_NOTICE(AlertLevel.NOTICE),

    /** FR: Cell Fault when ΔV exceeds 0.08 V. */
    CELL_IMBALANCE(AlertLevel.CELL_FAULT),

    /** FR: Warning at 20% remaining. */
    CHARGE_WARNING(AlertLevel.WARNING),

    /** FR: Warning at or below 3.65 V per cell. */
    CELL_VOLTAGE_WARNING(AlertLevel.WARNING),

    /** FR 3.2: remaining charge is below the dynamic requirement. */
    RTL_REQUIRED(AlertLevel.CRITICAL),

    /** FR: Critical at or below 3.50 V per cell. */
    CELL_VOLTAGE_CRITICAL(AlertLevel.CRITICAL),

    /** FR: Emergency at or below 3.40 V per cell. */
    CELL_VOLTAGE_EMERGENCY(AlertLevel.EMERGENCY),

    /** FR: Emergency on rapid sag. */
    RAPID_DECLINE(AlertLevel.EMERGENCY),
}

/**
 * One raised condition, with the sentence the operator reads and the action they are
 * being asked to take.
 *
 * The message is built here rather than in the UI because it is part of the safety claim,
 * not part of the layout: the same alert must read the same way wherever it is shown, and
 * it must be possible to test that a critical alert actually names the number that made
 * it critical.
 *
 * [spoken] exists alongside [message] because the two media want different things. On
 * screen `mV` and `V/cell` are dense and correct; read aloud they become "em vee" and
 * "vee slash cell". A voice alert that is hard to parse is worse than no alert, because
 * the operator is looking out of the window when it plays.
 */
data class BatteryAlert(
    val rule: AlertRule,
    val message: String,
    val action: String,
    /** The same alert phrased for text-to-speech. Defaults to the on-screen wording. */
    val spoken: String = message,
) {
    val level: AlertLevel get() = rule.level
}
