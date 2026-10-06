package com.batteryalert.guard.data.telemetry

import kotlinx.coroutines.flow.Flow

/**
 * A byte pipe to the flight controller, and nothing more.
 *
 * This interface is the seam that keeps the app off the hardware. Everything above it —
 * framing, checksums, decoding, the alert engine — is plain Kotlin that runs in a JVM test
 * with a fake pipe pumping bytes. The one implementation that needs a real USB-UART adapter
 * is then the only part that cannot be tested without the aircraft, and it is small enough to
 * read in one sitting.
 *
 * That split is deliberate and it is where most of the value of this phase is. Module 27's
 * instruction is not to spend the schedule on hardware before the core works, and hardware is
 * exactly what is *unverifiable* here — a USB serial adapter either enumerates on a Skydroid
 * G20 or it does not, and no amount of care in this repository changes that.
 *
 * ### Contract
 *
 * - [open] is where failure is reported. A pipe that cannot be opened must throw rather than
 *   return a flow that quietly ends; "the cable is not plugged in" and "the aircraft went
 *   quiet mid-flight" need different words on the dashboard and they arrive through different
 *   paths.
 * - [incoming] emits whatever bytes have arrived, in whatever sizes the driver chose. Chunk
 *   boundaries mean nothing — a frame may be split across two emissions or share one with
 *   three others. `MavlinkFrameParser` exists because of this.
 * - [incoming] ending means the link is gone. Closing it cleanly is not a thing a serial port
 *   does on its own, so the source treats a completed flow as a failure.
 */
interface TelemetryTransport {

    /** Shown on the connection card, e.g. "usb-serial /dev/bus/usb/001/003". */
    val name: String

    /** Opens the pipe. Throws if it cannot be opened. */
    suspend fun open()

    /** Byte chunks as they arrive, until the link drops. */
    fun incoming(): Flow<ByteArray>

    /** Releases the pipe. Safe to call when it was never opened. */
    suspend fun close()
}
