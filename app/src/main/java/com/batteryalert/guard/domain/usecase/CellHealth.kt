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

    /**
     * The single definition of "this reading is a cell" for the whole app.
     *
     * MAVLink `BATTERY_STATUS` packets carry a fixed-length cell array, so a 6S pack in a
     * 14-slot field arrives as six real voltages padded with zeros. Anything at or below
     * this is padding or a dead channel, and every caller that counts, averages or charts
     * cells filters on this one constant rather than inventing its own cutoff.
     */
    const val MIN_USABLE_CELL_VOLTS = 0.5

    fun stats(cellVoltages: List<Double>): CellStats? {
        val usable = cellVoltages.filter { it.isFinite() && it > MIN_USABLE_CELL_VOLTS }
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

    /**
     * 1-based position of the lowest usable cell, or null when no cells are reported.
     *
     * The alert engine needs this to name the failing cell, and the dashboard needs it to
     * highlight the same one in the balance chart. Defined once here so the cell the alert
     * names and the cell the chart marks can never disagree.
     */
    fun weakestCellNumber(cellVoltages: List<Double>): Int? =
        cellVoltages
            .withIndex()
            .filter { it.value.isFinite() && it.value > MIN_USABLE_CELL_VOLTS }
            .minByOrNull { it.value }
            ?.index
            ?.plus(1)
}
