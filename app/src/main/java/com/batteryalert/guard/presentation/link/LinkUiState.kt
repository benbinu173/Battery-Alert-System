package com.batteryalert.guard.presentation.link

import com.batteryalert.guard.data.link.LinkMode
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.usecase.UdpPort

/**
 * Everything the Link screen renders.
 *
 * ### Why the port has the same two-field split as the pack capacity
 *
 * [udpPort] is the port the socket is bound to — it came from storage and the app is listening
 * on it right now. [portInput] is what is in the box, which is nothing of the sort until it is
 * saved. Sharing one field would mean a half-typed "145" was already the port, and the link
 * would drop and rebind to a privileged port while the operator was still typing the third
 * digit. Same reasoning as `AircraftUiState`, because it is the same problem.
 *
 * There is no "empty means unset" case here, unlike the capacity: a socket cannot listen on no
 * port, so an empty box is an error rather than a request to remove the setting.
 */
data class LinkUiState(
    /** The source in force. Changing this is immediate — a mode is a choice, not a value. */
    val mode: LinkMode = LinkMode.DEMO,

    /** The port the app is listening on, or would listen on if the mode were UDP. */
    val udpPort: Int = UdpPort.DEFAULT_PORT,

    /** What is in the box, whether or not it is valid and whether or not it is saved. */
    val portInput: String = "",

    /** Why the box cannot be saved. Null when there is nothing wrong with it. */
    val portError: String? = null,

    // --- What the link is actually doing right now ---------------------------------------

    /**
     * The source's own view of the link.
     *
     * Read, never set, from this screen. The router owns the lifecycle: this screen changing a
     * setting is a statement about what the operator wants, and whether that means a reconnect
     * is the router's business.
     */
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,

    /**
     * Which wire is open, named by the transport itself.
     *
     * A string rather than an enum because it carries the bound port or the kernel device path,
     * which is the first thing anyone asks when nothing arrives.
     */
    val transportName: String = "",
) {

    /** The box as it would read if it showed what is stored. */
    private val storedInput: String get() = udpPort.toString()

    /** True when the box holds a port the store does not. Drives the Save button. */
    val hasPendingEdit: Boolean get() = portError == null && portInput != storedInput

    /**
     * True when the box holds exactly what is bound.
     *
     * Derived rather than latched from the save action, so it cannot claim a port was saved when
     * a later write put a different one in — the confirmation is a statement about the socket,
     * not a memory of a button press.
     */
    val showsSavedValue: Boolean
        get() = portError == null && portInput.isNotEmpty() && portInput == storedInput

    /** Whether the port above is the setting currently in use, or one waiting for its mode. */
    val portApplies: Boolean get() = mode == LinkMode.UDP

    /**
     * What the selected mode means, in one sentence.
     *
     * Spelled out on the screen rather than left to the chip labels, because "UDP" and "Serial"
     * are names of protocols and the operator's actual question is "what is the app going to do
     * when I tap this".
     */
    val modeSummary: String
        get() = when (mode) {
            LinkMode.DEMO ->
                "The app generates its own telemetry. No wire, no aircraft, no hardware — this " +
                    "is the mode a fresh install starts in, and the only one where the five " +
                    "scenarios are reachable."

            LinkMode.UDP ->
                "The app binds UDP port $udpPort and waits. Whatever is sending MAVLink — a " +
                    "datalink, a companion computer, or the autopilot itself — has to be " +
                    "pointed at this device's address on that port."

            LinkMode.USB ->
                "The app opens a USB-UART adapter attached to this device and reads MAVLink " +
                    "off it. Nothing needs configuring here: it picks the first adapter it " +
                    "finds."
        }

    /** The connection state in words. Wording matches the dashboard's pill exactly. */
    val connectionLabel: String
        get() = when (connectionState) {
            ConnectionState.CONNECTED -> "Link up"
            ConnectionState.CONNECTING -> "Connecting"
            ConnectionState.RECONNECTING -> "Reconnecting"
            ConnectionState.DISCONNECTED -> "No link"
            ConnectionState.ERROR -> "Link error"
        }

    /** The mode in words, for the header pill. */
    val modeLabel: String
        get() = when (mode) {
            LinkMode.DEMO -> "Simulator"
            LinkMode.UDP -> "UDP"
            LinkMode.USB -> "Serial"
        }
}
