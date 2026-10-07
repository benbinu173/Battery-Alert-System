package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.data.link.LinkMode
import com.batteryalert.guard.data.link.LinkSettingsStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The wire, whichever wire the operator chose.
 *
 * ### Why this exists at all
 *
 * `SerialTelemetryDataSource` is handed one [TelemetryTransport] when it is built, and it holds
 * it for its whole life. That was fine while there was one transport; now that there are two,
 * something has to sit behind the interface and decide. Putting the decision here rather than in
 * the data source is what keeps UDP support from turning into a change to the retry loop, the
 * watchdog and the link-health reporting — three things that have nothing to do with whether the
 * bytes arrived over a socket or a cable.
 *
 * ### What is deliberately not here
 *
 * No caching and no state. It reads the setting on every call and forwards, so a mode change
 * takes effect the next time the data source opens the link, and there is no second copy of the
 * current mode to fall out of step with the store.
 *
 * A mode of [LinkMode.DEMO] has **no wire at all**, and that is reported as null rather than
 * silently resolving to one of the two. It should be unreachable — `TelemetrySourceRouter` does
 * not drive this class in demo mode, because the simulator is a different `TelemetryDataSource`
 * and not a different transport — so an [open] that arrives here in demo mode is a wiring bug.
 * Failing loudly on the bug is better than binding a socket nobody asked for and having it look
 * like it worked.
 */
@Singleton
class SwitchableTelemetryTransport @Inject constructor(
    private val settings: LinkSettingsStore,
    private val udp: UdpTelemetryTransport,
    private val usb: UsbSerialTransport,
) : TelemetryTransport {

    private val active: TelemetryTransport?
        get() = when (settings.settings.value.mode) {
            LinkMode.UDP -> udp
            LinkMode.USB -> usb
            LinkMode.DEMO -> null
        }

    override val name: String
        get() = active?.name ?: "no wire (simulator)"

    override suspend fun open() {
        requireNotNull(active) {
            "The wire was opened while the source is the simulator. " +
                "TelemetrySourceRouter drives the simulator directly and should never reach here."
        }.open()
    }

    override fun incoming(): Flow<ByteArray> = requireNotNull(active) {
        "The wire was read while the source is the simulator."
    }.incoming()

    override suspend fun close() {
        // Closing is not an error when there is nothing to close: this is called on every
        // teardown, including teardowns of a link that was never opened, and raising here would
        // turn an ordinary disconnect into a crash.
        active?.close()
    }
}
