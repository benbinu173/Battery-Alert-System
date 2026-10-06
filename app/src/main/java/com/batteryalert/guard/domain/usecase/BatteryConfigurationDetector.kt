package com.batteryalert.guard.domain.usecase

import com.batteryalert.guard.domain.model.BatteryChemistry
import com.batteryalert.guard.domain.model.BatteryConfiguration
import kotlin.math.abs

/**
 * The configuration the detector settled on, plus the evidence for it.
 *
 * [errorVoltsPerCell] is how far the sample sat from the reference voltage for that
 * configuration. A large error means the guess is weak and the caller should say so
 * rather than presenting it as fact.
 */
data class Detection(
    val configuration: BatteryConfiguration,
    val voltsPerCell: Double,
    val errorVoltsPerCell: Double,
)

/**
 * Infers pack chemistry and cell count from a baseline voltage reading (FR 1.3).
 *
 * ### Why this is configurable rather than hardcoded
 *
 * The requirement specifies detection from baseline voltage but supplies no threshold
 * table, and the maths is genuinely ambiguous on its own: 25.2 V is exactly a full 6S
 * Li-ion pack, a 7S pack at 3.60 V/cell, or a 14S pack at 1.80 V/cell. Two decisions
 * resolve it, and both are surfaced here instead of being buried:
 *
 *  1. **Reference** — the detector compares against full-charge or nominal per-cell
 *     voltage. [Reference.FULL_CHARGE] is the default because a pack is normally
 *     sampled on power-up, when it is near full. Sampling a partially discharged pack
 *     under the wrong reference will mis-detect, which is inherent to the requirement
 *     and not something more code can fix.
 *  2. **Tolerance** — a match further than [Thresholds.matchToleranceVoltsPerCell] from
 *     the reference is rejected outright rather than reported as a confident answer.
 *
 * Callers that get null should fall back to asking the operator, not to guessing.
 */
object BatteryConfigurationDetector {

    /** Which per-cell voltage the baseline sample is assumed to sit near. */
    enum class Reference {
        /** Assumes the pack was sampled near full charge. Default. */
        FULL_CHARGE,

        /** Assumes the pack was sampled at nominal/resting voltage. */
        NOMINAL,
    }

    data class Thresholds(
        /** Cell counts considered. Covers 2S up to 16S, comfortably past the 6S–14S range required. */
        val candidateCellCounts: IntRange = 2..16,

        /** A per-cell voltage outside this range cannot belong to any supported chemistry. */
        val plausibleVoltsPerCell: ClosedFloatingPointRange<Double> = 2.90..4.40,

        /** A candidate worse than this is rejected rather than guessed. */
        val matchToleranceVoltsPerCell: Double = 0.25,
    )

    val DEFAULT_THRESHOLDS = Thresholds()

    fun detect(
        baselinePackVolts: Double?,
        reference: Reference = Reference.FULL_CHARGE,
        thresholds: Thresholds = DEFAULT_THRESHOLDS,
    ): Detection? {
        val packVolts = baselinePackVolts?.takeIf { it.isFinite() && it > 0.0 } ?: return null

        var best: Detection? = null

        for (cellCount in thresholds.candidateCellCounts) {
            val voltsPerCell = packVolts / cellCount
            if (voltsPerCell !in thresholds.plausibleVoltsPerCell) continue

            for (chemistry in BatteryChemistry.entries) {
                val target = when (reference) {
                    Reference.FULL_CHARGE -> chemistry.fullVoltsPerCell
                    Reference.NOMINAL -> chemistry.nominalVoltsPerCell
                }
                val error = abs(voltsPerCell - target)

                if (error <= thresholds.matchToleranceVoltsPerCell &&
                    (best == null || error < best.errorVoltsPerCell)
                ) {
                    best = Detection(
                        configuration = BatteryConfiguration(cellCount, chemistry),
                        voltsPerCell = voltsPerCell,
                        errorVoltsPerCell = error,
                    )
                }
            }
        }

        return best
    }
}
