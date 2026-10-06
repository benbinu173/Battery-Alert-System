package com.batteryalert.guard.data.telemetry

/**
 * Canned flight situations used by demo mode (and by the final demonstration
 * sequence). Each one is chosen to land the safety system in a distinct state.
 */
enum class MockScenario(val label: String, val description: String) {
    NORMAL_FLIGHT(
        label = "Normal Flight",
        description = "Healthy 12S pack, moderate load, drone out over the field.",
    ),
    LOW_BATTERY(
        label = "Low Battery",
        description = "Pack down around the 20% warning band.",
    ),
    CELL_IMBALANCE(
        label = "Cell Imbalance",
        description = "One weak cell pushing cell delta past 0.08 V.",
    ),
    RTL_REQUIRED(
        label = "RTL Required",
        description = "Far from home on a marginal pack — required RTL exceeds remaining.",
    ),
    EMERGENCY(
        label = "Emergency",
        description = "Cells at the 3.40 V floor and falling fast.",
    ),
}
