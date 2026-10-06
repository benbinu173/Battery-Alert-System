package com.batteryalert.guard.domain.model

/**
 * A single snapshot of the flight battery as reported by telemetry.
 *
 * Values are nullable wherever the source may genuinely not supply them — a pack
 * with no current sensor, or a frame that omits temperature. Null means "unknown",
 * never "zero". Safety logic must treat unknown as unknown rather than guessing.
 */
data class BatteryTelemetry(
    val batteryPercentage: Int? = null,
    val totalVoltage: Double? = null,
    val current: Double? = null,
    val cellVoltages: List<Double> = emptyList(),
    val temperature: Double? = null,
    val remainingCapacityMah: Double? = null,
    val dischargeRateMahPerMin: Double? = null,
) {
    val cellCount: Int get() = cellVoltages.size

    /** True when a pack that reports per-cell data actually has usable cells in it. */
    val hasCellData: Boolean get() = cellVoltages.any { it > 0.0 }

    companion object {
        val EMPTY = BatteryTelemetry()
    }
}
