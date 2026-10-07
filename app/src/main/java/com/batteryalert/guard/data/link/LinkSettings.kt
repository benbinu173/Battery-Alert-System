package com.batteryalert.guard.data.link

import com.batteryalert.guard.domain.usecase.UdpPort
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the app gets its telemetry from.
 *
 * ### Why this is a setting and not a build flag
 *
 * Every one of these is a real thing an operator might need on a given day. [DEMO] is the app
 * with no aircraft attached — the state a reviewer sees on a fresh install, and the only state
 * in which the five scenarios are reachable. [UDP] is a datalink or companion computer pushing
 * MAVLink to the tablet. [USB] is a UART adapter on the same tablet. Which one is correct is a
 * property of the setup in front of the operator, not of the build they installed, so choosing
 * it is a screen rather than a flavour.
 *
 * Being able to switch also removes a trap the earlier design had: the source was chosen at
 * compile time, which meant demonstrating the app and flying it were different builds, and the
 * one being demonstrated was never the one being flown.
 */
enum class LinkMode {

    /** The built-in simulator. No wire, no aircraft, no hardware. */
    DEMO,

    /** A UDP socket bound to [LinkSettings.udpPort], with the aircraft sending to it. */
    UDP,

    /** A USB-UART adapter on this device. */
    USB,
}

/**
 * Everything the link needs to know before it can be brought up.
 *
 * One object rather than two fields because everything that reacts to this reacts to all of it:
 * a consumer that restarted on the mode and forgot the port would leave the socket bound to the
 * old one, and the operator would be looking at a screen that says UDP while the app listens
 * somewhere else.
 */
data class LinkSettings(
    val mode: LinkMode = LinkMode.DEMO,
    val udpPort: Int = UdpPort.DEFAULT_PORT,
)

/**
 * What the operator chose about the wire, kept where it survives the app being closed.
 *
 * Same shape as `AircraftProfileStore` and for the same reasons: a `StateFlow` rather than a
 * getter, because it is written on one screen and read on several, and a plain field would leave
 * the others rendering whatever was true when they started.
 *
 * It deliberately does *not* expose `connect()` or `disconnect()`. Changing the setting is a
 * statement about what the operator wants; bringing the link up is a separate decision that
 * belongs to whoever owns the link's lifecycle. Keeping them apart is what lets a change made on
 * this screen take effect without the screen knowing anything about sockets.
 */
interface LinkSettingsStore {

    val settings: StateFlow<LinkSettings>

    fun setMode(mode: LinkMode)

    /**
     * Records the UDP port.
     *
     * Rejects a port outside [UdpPort.PORT_RANGE] rather than clamping or defaulting it. The
     * screen validates before calling, so a bad value arriving here is a bug rather than an
     * operator mistake, and quietly keeping the previous port would leave the screen showing one
     * number and the socket bound to another.
     */
    fun setUdpPort(port: Int)
}
