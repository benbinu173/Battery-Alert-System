package com.batteryalert.guard.domain.model

/**
 * Battery chemistries the app can recognise from baseline voltage.
 *
 * Per-cell figures are the standard published limits for each chemistry, not invented
 * values. They are the only place in the app where absolute cell-voltage limits are
 * hardcoded; everything downstream reads them from here.
 */
enum class BatteryChemistry(
    val label: String,
    val nominalVoltsPerCell: Double,
    val fullVoltsPerCell: Double,
    val emptyVoltsPerCell: Double,
) {
    LI_ION("Li-ion", nominalVoltsPerCell = 3.70, fullVoltsPerCell = 4.20, emptyVoltsPerCell = 2.75),
    LI_PO("LiPo", nominalVoltsPerCell = 3.70, fullVoltsPerCell = 4.20, emptyVoltsPerCell = 3.00),
    LI_HV("LiHV", nominalVoltsPerCell = 3.80, fullVoltsPerCell = 4.35, emptyVoltsPerCell = 3.00),
}

/**
 * A detected pack configuration (FR 1.3).
 *
 * [cellCount] and [chemistry] come from a baseline voltage sample; the derived pack
 * voltages are computed from the chemistry's published per-cell limits.
 */
data class BatteryConfiguration(
    val cellCount: Int,
    val chemistry: BatteryChemistry,
) {
    val nominalPackVolts: Double get() = cellCount * chemistry.nominalVoltsPerCell
    val fullPackVolts: Double get() = cellCount * chemistry.fullVoltsPerCell
    val emptyPackVolts: Double get() = cellCount * chemistry.emptyVoltsPerCell

    /** e.g. "12S Li-ion" */
    val label: String get() = "${cellCount}S ${chemistry.label}"
}
