package com.batteryalert.guard.domain.model

/**
 * Position/telemetry derived from GLOBAL_POSITION_INT.
 *
 * [distanceToHomeMeters] is carried here rather than recomputed in the UI because
 * the vehicle already knows its home point; the app does not have to guess it.
 */
data class GpsData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val distanceToHomeMeters: Double? = null,
    val hasFix: Boolean = false,
) {
    companion object {
        val EMPTY = GpsData()
    }
}
