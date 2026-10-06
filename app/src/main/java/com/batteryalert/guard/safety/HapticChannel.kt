package com.batteryalert.guard.safety

import com.batteryalert.guard.domain.model.AlertLevel

/**
 * The vibration boundary.
 *
 * Haptics are not a luxury here. The G20 is held in the hand while the operator watches
 * the aircraft, often with the volume down or the wind up; a pattern against the palm is
 * the one channel that survives both.
 */
interface HapticChannel {

    /** Play the pattern for [level]. [AlertLevel.NORMAL] plays nothing. */
    fun pulse(level: AlertLevel)

    fun cancel()
}
