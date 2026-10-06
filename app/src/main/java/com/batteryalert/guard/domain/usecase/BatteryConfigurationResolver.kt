package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.BatteryChemistry

/** How a pack configuration was arrived at. */
enum class ConfigurationBasis {
    /**
     * Counted from per-cell telemetry. The cell count is measured, not inferred, so it is
     * correct regardless of state of charge.
     */
    CELL_TELEMETRY,

    /**
     * Inferred from one pack-voltage sample. A heuristic, and only valid on a pack that
     * was near full when sampled — see [BatteryConfigurationDetector].
     */
    BASELINE_VOLTAGE,
}

/**
 * The pack configuration the app is using, and how confident it is.
 *
 * [chemistry] is null when the chemistry cannot be *proven* from voltage. That is not a
 * gap in this code: Li-ion and LiPo share the same nominal (3.70 V) and full (4.20 V)
 * per-cell voltages, so no voltage reading can separate them. Reporting a guess here
 * would put an unearned claim on a safety display.
 */
data class ResolvedBatteryConfiguration(
    val cellCount: Int,
    val chemistry: BatteryChemistry?,
    val voltsPerCell: Double,
    val basis: ConfigurationBasis,
) {
    val chemistryIsCertain: Boolean get() = chemistry != null

    /** "12S LiHV", or just "12S" when the chemistry is not provable. */
    val label: String
        get() = chemistry?.let { "${cellCount}S ${it.label}" } ?: "${cellCount}S"
}

/**
 * Turns whatever the telemetry actually provides into one configuration (FR 1.3).
 *
 * Cell telemetry wins whenever it is present: counting twelve cells is a measurement,
 * whereas dividing a pack voltage by twelve is a guess. The baseline-voltage path exists
 * only for sources that report a pack total and no per-cell data.
 */
object BatteryConfigurationResolver {

    /**
     * Above this per-cell voltage the pack can only be LiHV. Li-ion and LiPo top out at
     * 4.20 V/cell, so anything meaningfully past that is proof rather than inference.
     */
    const val LI_HV_PROOF_VOLTS_PER_CELL = 4.25

    private const val MIN_USABLE_CELL_VOLTS = CellHealth.MIN_USABLE_CELL_VOLTS

    fun resolve(
        cellVoltages: List<Double> = emptyList(),
        baselinePackVolts: Double? = null,
    ): ResolvedBatteryConfiguration? {
        val usable = cellVoltages.filter { it.isFinite() && it > MIN_USABLE_CELL_VOLTS }

        if (usable.isNotEmpty()) {
            val voltsPerCell = usable.sum() / usable.size
            return ResolvedBatteryConfiguration(
                cellCount = usable.size,
                chemistry = proveChemistry(voltsPerCell),
                voltsPerCell = voltsPerCell,
                basis = ConfigurationBasis.CELL_TELEMETRY,
            )
        }

        val detection = BatteryConfigurationDetector.detect(baselinePackVolts) ?: return null
        return ResolvedBatteryConfiguration(
            cellCount = detection.configuration.cellCount,
            chemistry = proveChemistry(detection.voltsPerCell),
            voltsPerCell = detection.voltsPerCell,
            basis = ConfigurationBasis.BASELINE_VOLTAGE,
        )
    }

    private fun proveChemistry(voltsPerCell: Double): BatteryChemistry? =
        BatteryChemistry.LI_HV.takeIf { voltsPerCell > LI_HV_PROOF_VOLTS_PER_CELL }
}
