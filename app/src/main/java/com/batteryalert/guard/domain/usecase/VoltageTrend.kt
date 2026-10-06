package com.batteryalert.guard.domain.usecase

/**
 * How fast the pack is losing voltage, per cell per second.
 *
 * This is the input FR 5 needs for "rapid sag" but does not define. A sustained *rate* of
 * decline is a different failure from a low *level*: a pack can sit at 3.6 V/cell for
 * minutes, but one falling through that band in seconds is collapsing and will not
 * recover. Thresholding on level alone cannot see the difference.
 *
 * The rate is fitted by least squares across the whole window rather than measured
 * between its endpoints, so a single noisy sample cannot set the rate on its own.
 */
object VoltageTrend {

    /** Below this many samples any fit is noise. */
    const val MIN_SAMPLES = 4

    /** Below this many seconds the window is too short to call a trend a trend. */
    const val MIN_WINDOW_SECONDS = 3.0

    /**
     * Fits a decline rate to a window of pack-voltage samples.
     *
     * @param packVolts pack voltage, oldest first
     * @param timestampsMillis wall-clock milliseconds, one per sample, same order
     * @param cellCount cells in the pack, to express the rate per cell
     * @return volts per cell per second, **positive when the pack is falling** and
     *   negative when it is recovering. Null when the window is too short, the inputs do
     *   not line up, or any sample is unusable.
     */
    fun declineVoltsPerCellPerSecond(
        packVolts: List<Double>,
        timestampsMillis: List<Long>,
        cellCount: Int,
    ): Double? {
        if (cellCount <= 0) return null
        if (packVolts.size != timestampsMillis.size) return null
        if (packVolts.size < MIN_SAMPLES) return null
        if (packVolts.any { !it.isFinite() }) return null

        val firstMillis = timestampsMillis.first()
        val elapsedSeconds = (timestampsMillis.last() - firstMillis) / 1_000.0
        if (elapsedSeconds < MIN_WINDOW_SECONDS) return null

        val count = packVolts.size
        val seconds = timestampsMillis.map { (it - firstMillis) / 1_000.0 }
        val meanSeconds = seconds.sum() / count
        val meanVolts = packVolts.sum() / count

        var covariance = 0.0
        var variance = 0.0
        for (index in 0 until count) {
            val dt = seconds[index] - meanSeconds
            covariance += dt * (packVolts[index] - meanVolts)
            variance += dt * dt
        }

        // variance is > 0 for any window that passed the elapsed-time check above, so this
        // cannot divide by zero. It is left unguarded deliberately: a guard here would be
        // unreachable code pretending to be defensive.
        val slopeVoltsPerSecond = covariance / variance
        return -slopeVoltsPerSecond / cellCount
    }
}
