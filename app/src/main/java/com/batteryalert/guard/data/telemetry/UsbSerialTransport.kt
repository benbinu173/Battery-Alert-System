package com.batteryalert.guard.data.telemetry

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The USB-UART adapter on the other end of the telemetry link.
 *
 * ### The honest status of this file
 *
 * **It has never been run.** It is the only class in this project that cannot be tested without
 * the aircraft, and the fact that it compiles is the whole of the evidence for it. Everything
 * it feeds — the stream parser, the checksums, the decoders, the link's state machine — is
 * driven by unit tests against synthetic frames, so the failure mode this leaves is narrow and
 * named: if the adapter does not enumerate, or the baud rate is wrong for the G20's port, the
 * symptom is a dashboard that says "Link error" and a diagnostics screen with zero frames
 * decoded. Nothing downstream can be wrong in a way that looks plausible, because nothing
 * downstream is being fed.
 *
 * ### Why this and nothing more
 *
 * The brief's Module 27 asks not to spend the schedule on hardware before the core works. The
 * core works, so this exists — but it is deliberately the smallest thing that could be: find an
 * adapter, get permission, open one port at MAVLink's conventional rate, and hand bytes up. No
 * protocol knowledge lives here. It does not know what a MAVLink frame is, which is what keeps
 * it replaceable.
 *
 * ### What it does not do, and why that is the right shape
 *
 * It does not detect a *specific* adapter. The prober matches against the library's table of
 * known USB-UART bridges, so anything from a CP2102 to an FTDI to a CH340 works, and the GR01's
 * own interface is one of those. Naming a single VID/PID would mean the app stopped working on
 * an identical aircraft whose radio enumerated differently.
 *
 * It registers no `ACTION_USB_DEVICE_ATTACHED` receiver either. Plugging the radio in while the
 * app is running already recovers without one: [awaitDriver] waits for enumeration during
 * [open], and [open] runs on every trip round `SerialTelemetryDataSource`'s retry loop. A
 * receiver would be a second mechanism duplicating the first, and it would have to be
 * registered and unregistered against an activity lifecycle that has nothing to do with the
 * link — more places to leak a receiver than the polling it replaces. Detaching is covered from
 * the other side: the read fails, the flow ends, and the retry loop starts.
 *
 * It does not write. The link is receive-only. Most MAVLink links need the ground station to
 * request a data stream before the autopilot sends one, so if the dashboard connects and then
 * sits at zero frames, that request — not this class — is the first thing to look at. It is not
 * added here because the requirements describe an alert system reading a link that is already
 * carrying traffic, and a ground station that starts commanding an aircraft it cannot yet see
 * is a bigger claim than this repository is making.
 *
 * It also does not read the G20's internal `/dev/ttySx`. The requirements name that as an
 * alternative path, and it is not reachable from an unprivileged Android app without a vendor
 * kernel permission this app cannot request. See the README.
 */
@Singleton
class UsbSerialTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : TelemetryTransport {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null

    /**
     * Shown on the connection card.
     *
     * Includes the kernel device path because "which port did it actually pick" is the first
     * question when an adapter enumerates as something unexpected, and a name that only said
     * "usb-serial" would make the operator go and find that out somewhere else.
     */
    override val name: String
        get() = port?.let { "usb-serial ${it.driver.device.deviceName}" }
            ?: "usb-serial (no adapter)"

    /**
     * Finds an adapter, gets permission, and opens its first port.
     *
     * ### Why this releases before it acquires
     *
     * Nothing calls [close] between a link dropping and the retry loop reopening it — the retry
     * loop's job is to reopen, and it runs in the source's coroutine rather than here. So this
     * is where a port left over from the previous attempt is released. Reopening a
     * `UsbDeviceConnection` that was never closed does not fail cleanly: it either throws from
     * inside the driver or leaks the file descriptor, and a leaked descriptor survives the app
     * being backgrounded. Doing it here rather than asking the caller to remember is the same
     * argument as putting the close in `finally`.
     */
    override suspend fun open() {
        withContext(Dispatchers.IO) {
            release()

            val driver = awaitDriver()
            val device = driver.device

            if (!usbManager.hasPermission(device) && !requestPermission(device)) {
                error("Permission to use ${device.deviceName} was refused.")
            }

            val opened = usbManager.openDevice(device)
                ?: error("${device.deviceName} is attached but could not be opened.")

            val serialPort = driver.ports.firstOrNull()
            if (serialPort == null) {
                opened.close()
                error("${device.deviceName} reports no serial port.")
            }

            try {
                serialPort.open(opened)
                serialPort.setParameters(BAUD_RATE, DATA_BITS, STOP_BITS_1, PARITY_NONE)
            } catch (failure: Exception) {
                // A port that opened and then failed to configure has to be put back, or the
                // next attempt finds a device it cannot open. The original failure is what the
                // caller needs to see, so it is rethrown rather than replaced by a close error.
                runCatching { serialPort.close() }
                opened.close()
                throw failure
            }

            connection = opened
            port = serialPort
        }
    }

    /**
     * Bytes as the driver delivers them.
     *
     * The library's own reader rather than a `read()` loop here: it owns the blocking read, the
     * timeout handling and the read buffer, and reimplementing that around `UsbSerialPort.read`
     * means reimplementing the one part of a USB driver that is easy to get subtly wrong.
     *
     * Each chunk is copied before it is emitted. The reader reuses its buffer, and a parser
     * holding a reference to it would be reading bytes that the next read has already
     * overwritten — the kind of fault that shows up as one corrupt frame an hour and cannot be
     * reproduced on a bench.
     */
    override fun incoming(): Flow<ByteArray> = callbackFlow {
        val activePort = port ?: error("The transport was read before it was opened.")

        val manager = SerialInputOutputManager(
            activePort,
            object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    trySend(data.copyOf())
                }

                /**
                 * The read failed, which for a serial port means the device went away — a
                 * yanked cable, or the adapter resetting. Closing with the cause ends the flow,
                 * which is the signal `SerialTelemetryDataSource` treats as "the link is gone,
                 * start trying again".
                 */
                override fun onRunError(e: Exception) {
                    close(e)
                }
            },
        )

        manager.start()
        awaitClose { manager.stop() }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { release() }
    }

    /**
     * Waits a moment for an adapter to enumerate, then gives up.
     *
     * `open()` is supposed to fail fast, and this is the one case where failing fast is
     * wrong. USB enumeration is not instant, and the ordinary order of events is the operator
     * plugging the radio in and then launching the app — so a probe that ran once, immediately,
     * would find nothing and report a link error about a cable that is already in. The wait is
     * short and bounded because the alternative failure is real too: a transport that waited
     * indefinitely would turn "there is no adapter" into a dashboard that says CONNECTING
     * forever, which is a worse lie than "Link error".
     */
    private suspend fun awaitDriver(): UsbSerialDriver {
        var waited = 0L
        while (true) {
            findDriver()?.let { return it }
            if (waited >= ATTACH_TIMEOUT_MILLIS) {
                error("No USB serial adapter is attached.")
            }
            delay(ATTACH_POLL_MILLIS)
            waited += ATTACH_POLL_MILLIS
        }
    }

    /** The first attached adapter the library recognises, or null. */
    private fun findDriver(): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull { it.ports.isNotEmpty() }

    /**
     * Asks the system to grant access to [device], and suspends until the operator answers.
     *
     * Returns false on refusal *and* on timeout. The two are not distinguished because they
     * call for the same thing — the retry loop's next attempt is the only sensible response
     * either way, and a dialog nobody answered is going to be replaced by another one.
     */
    private suspend fun requestPermission(device: UsbDevice): Boolean {
        val action = "$PERMISSION_ACTION_PREFIX.${context.packageName}"

        return withTimeoutOrNull(PERMISSION_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val settled = AtomicBoolean(false)
                var registered: BroadcastReceiver? = null

                fun finish(granted: Boolean) {
                    // The dialog and a timeout can both arrive. Only the first one counts, and
                    // only the first one may unregister — a second unregister throws.
                    if (!settled.compareAndSet(false, true)) return
                    registered?.let { runCatching { context.unregisterReceiver(it) } }
                    registered = null
                    if (continuation.isActive) continuation.resume(granted)
                }

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        if (intent.action == action) {
                            finish(
                                intent.getBooleanExtra(
                                    UsbManager.EXTRA_PERMISSION_GRANTED,
                                    false,
                                ),
                            )
                        }
                    }
                }

                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(action),
                    // Not exported: the grant is this app's alone to hear, and an exported
                    // receiver would let any other app on the device answer for it.
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = receiver
                continuation.invokeOnCancellation { finish(false) }

                val pendingIntentFlags =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Mutable because the system writes the grant into this intent's
                        // extras before sending it back. Safe to be mutable because the
                        // intent is already explicit — `setPackage` pins it to this app — so
                        // there is no other receiver for it to be redirected to.
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }

                usbManager.requestPermission(
                    device,
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(action).setPackage(context.packageName),
                        pendingIntentFlags,
                    ),
                )

                // The dialog is up and the answer arrives on the receiver above, or the
                // timeout above fires. Either way this block has already done its part, so it
                // hands back the optimistic answer and lets `finish` correct it.
                true
            }
        } ?: false
    }

    /**
     * Lets go of the port and the connection, in that order.
     *
     * Safe to call twice and safe to call when nothing was ever opened, because every path out
     * of [open] can end up here — including the paths that only got as far as asking for
     * permission.
     */
    private fun release() {
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null
        connection = null
    }

    private companion object {

        /**
         * MAVLink's conventional rate, and the one every telemetry radio in this class ships
         * set to.
         *
         * Not configurable, and that is a deliberate omission rather than a missing feature:
         * the requirements name no rate, and a setting for it would be the app inventing a
         * failure mode — an operator who changed it and forgot would get a link that opens,
         * stays up, and decodes nothing. If the G20's radio turns out to run at something else,
         * this constant is the one line to change.
         */
        const val BAUD_RATE = 57_600

        const val DATA_BITS = 8

        /**
         * The library's `UsbSerialPort.STOPBITS_1` and `UsbSerialPort.PARITY_NONE`, written as
         * the values they are.
         *
         * 8N1 is what MAVLink telemetry uses everywhere; the library's own constants would read
         * better here and would also tie this file to one version of its API for no benefit,
         * since these two numbers have not changed since RS-232 was written down.
         */
        const val STOP_BITS_1 = 1
        const val PARITY_NONE = 0

        const val PERMISSION_ACTION_PREFIX = "com.batteryalert.guard.USB_PERMISSION"

        /**
         * Long enough for an operator to read the system dialog and answer it, short enough
         * that a dialog they never saw does not stall the retry loop.
         */
        const val PERMISSION_TIMEOUT_MILLIS = 30_000L

        /** How long [awaitDriver] waits for an adapter to enumerate before giving up. */
        const val ATTACH_TIMEOUT_MILLIS = 5_000L

        const val ATTACH_POLL_MILLIS = 250L
    }
}
