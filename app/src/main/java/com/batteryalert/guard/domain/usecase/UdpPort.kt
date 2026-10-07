package com.batteryalert.guard.domain.usecase

/**
 * Which UDP port the app is allowed to listen on.
 *
 * ### Why the port has a default when almost nothing else here does
 *
 * The rest of this app is strict about the difference between "unknown" and "zero" — see
 * [PackCapacity], where a null capacity withholds a flight-time estimate rather than inventing
 * one. A port is not that kind of quantity. It is a setting someone chooses, and there is a
 * published convention for it: 14550 is what MAVLink ground stations have used for years. A
 * default is a starting point the operator can see and change, not a measurement the app is
 * pretending to have taken.
 *
 * The distinction matters because of what happens if it is wrong. A guessed capacity produces a
 * plausible flight time that nobody would question. A wrong port produces a socket that receives
 * nothing, which the link watchdog turns into a visible failure within two seconds. One of those
 * failures announces itself and the other does not, and only the second needs the app to refuse
 * to guess.
 *
 * ### Why the floor is 1024
 *
 * Ports below 1024 are privileged, and an unprivileged Android app cannot bind them — the
 * attempt fails with a permission error rather than with anything to do with MAVLink. Rejecting
 * them here turns "no telemetry, and the error message is about sockets" into "that port is not
 * one the app can use", which is a sentence the operator can act on.
 */
object UdpPort {

    /** Below this, binding needs privileges an ordinary Android app does not have. */
    const val MIN_PORT = 1024

    /** The top of the range. Not a policy choice — this is where the field ends. */
    const val MAX_PORT = 65_535

    /**
     * The conventional MAVLink ground-station port.
     *
     * A default rather than a requirement: it is what most autopilots and datalinks are already
     * configured to send to, so the common case needs no entry at all, and the uncommon case is
     * one number in a box.
     */
    const val DEFAULT_PORT = 14_550

    /**
     * Whether a port is one this app could bind.
     *
     * Exposed so the screen can refuse an entry as it is made, for the same reason
     * [PackCapacity.isPlausible] is: a value accepted at the keyboard and rejected at the socket
     * is a failure with two places to look for it.
     *
     * Nullable because the entry box is briefly empty while someone is typing in it, and "not
     * typed yet" is not the same complaint as "that number is wrong".
     */
    fun isBindable(port: Int?): Boolean = port != null && port in PORT_RANGE

    val PORT_RANGE = MIN_PORT..MAX_PORT
}
