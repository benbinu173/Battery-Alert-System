package com.batteryalert.guard.domain.usecase

/**
 * Measured cell voltages with the load-induced sag added back.
 *
 * [restingCellVolts] is what the pack would read with no current flowing — the number
 * that actually corresponds to state of charge. [measuredCellVolts] is what the
 * telemetry reported under load.
 */
data class SagCompensation(
    val measuredCellVolts: List<Double>,
    val restingCellVolts: List<Double>,
    val sagPerCellVolts: Double,
    val measuredPackVolts: Double,
    val restingPackVolts: Double,
)

/**
 * Voltage-sag compensation (FR 2.1).
 *
 * Under motor load a pack reads low by `I x Ri` per cell. That sag is why a healthy
 * battery can show 3.55 V/cell at 25 A and then recover to 3.85 V/cell the moment the
 * motors stop. Alerting on the loaded figure alone produces false alarms during hard
 * climbs; alerting on the resting figure alone hides a genuinely weak pack. Both are
 * reported, and the caller decides which to threshold on.
 *
 * Pure and deterministic: no Android, no coroutines, no clock. Every branch that cannot
 * produce a meaningful answer returns null rather than a fabricated number.
 */
object VoltageSag {

    /**
     * Typical internal resistance for a large agricultural pack cell.
     *
     * The requirements do not specify Ri, so this is a documented default that callers
     * are expected to override with a per-airframe value. It is not presented as a
     * measured constant.
     */
    const val DEFAULT_INTERNAL_RESISTANCE_OHM_PER_CELL = 0.004

    /**
     * @param measuredCellVolts per-cell voltages as reported under load
     * @param currentAmps pack current; positive = discharging, negative = charging
     * @param internalResistanceOhmPerCell per-cell Ri
     * @return null when current or cells are unknown/unusable
     */
    fun compensate(
        measuredCellVolts: List<Double>,
        currentAmps: Double?,
        internalResistanceOhmPerCell: Double = DEFAULT_INTERNAL_RESISTANCE_OHM_PER_CELL,
    ): SagCompensation? {
        val current = currentAmps?.takeIf { it.isFinite() } ?: return null
        if (!internalResistanceOhmPerCell.isFinite() || internalResistanceOhmPerCell < 0.0) return null

        val cells = measuredCellVolts.filter { it.isFinite() && it > CellHealth.MIN_USABLE_CELL_VOLTS }
        if (cells.isEmpty()) return null

        // Sign is preserved deliberately: while charging, current is negative and the
        // resting voltage is genuinely below the measured one.
        val sagPerCell = current * internalResistanceOhmPerCell
        val resting = cells.map { it + sagPerCell }

        return SagCompensation(
            measuredCellVolts = cells,
            restingCellVolts = resting,
            sagPerCellVolts = sagPerCell,
            measuredPackVolts = cells.sum(),
            restingPackVolts = resting.sum(),
        )
    }
}
