package com.batteryalert.guard.domain.model

/**
 * What the aircraft is currently doing. The RTL engine needs cruise speed, and the
 * spray interlock needs to know whether spraying is actually running.
 */
data class FlightState(
    val cruisingSpeedMps: Double? = null,
    val payloadActive: Boolean = false,
    val sprayingActive: Boolean = false,
) {
    companion object {
        val EMPTY = FlightState()
    }
}
