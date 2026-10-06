package com.batteryalert.guard.domain.usecase

/**
 * Summary statistics for a pack's individual cell voltages.
 *
 * [deltaVolts] is Vmax - Vmin, the quantity the requirements threshold at 0.08 V.
 */
data class CellStats(
    val count: Int,
    val minVolts: Double,
    val maxVolts: Double,
    val averageVolts: Double,
    val deltaVolts: Double,
)

/**
 * Pure, deterministic cell-health maths. Kept out of the UI and out of the parser so
 * it can be unit-tested against fixed inputs.
 *
 * Returns null rather than a fabricated value when there is nothing meaningful to
 * measure — an empty or all-zero cell list is "unknown", not "0 V delta".
 */
object CellHealth {

    private const val MIN_PLAUSIBLE_CELL_VOLTS = 0.5

    fun stats(cellVoltages: List<Double>): CellStats? {
        val usable = cellVoltages.filter { it.isFinite() && it > MIN_PLAUSIBLE_CELL_VOLTS }
        if (usable.isEmpty()) return null

        val min = usable.min()
        val max = usable.max()
        return CellStats(
            count = usable.size,
            minVolts = min,
            maxVolts = max,
            averageVolts = usable.sum() / usable.size,
            deltaVolts = max - min,
        )
    }

    /** Cell delta in volts, or null when the pack does not report usable cells. */
    fun deltaVolts(cellVoltages: List<Double>): Double? = stats(cellVoltages)?.deltaVolts
}
