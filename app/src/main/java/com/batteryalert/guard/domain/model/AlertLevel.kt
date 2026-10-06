package com.batteryalert.guard.domain.model

/**
 * The five alert categories from the requirements, plus NORMAL.
 *
 * [severity] encodes the required priority order
 * (EMERGENCY > CRITICAL > WARNING > CELL_FAULT > NOTICE > NORMAL) so the alert
 * engine can collapse multiple simultaneous conditions into one authoritative
 * state with a simple max-by-severity.
 */
enum class AlertLevel(val severity: Int) {
    NORMAL(0),
    NOTICE(1),
    CELL_FAULT(2),
    WARNING(3),
    CRITICAL(4),
    EMERGENCY(5),
}
