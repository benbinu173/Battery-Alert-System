package com.batteryalert.guard.data.telemetry

import com.batteryalert.guard.data.telemetry.mavlink.LinkHealth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * How the diagnostics screen asks the wire how it is doing.
 *
 * Kept out of [TelemetryDataSource] on purpose. Only a source with a wire has anything to
 * report, and widening that interface would force the simulator to answer a question it has
 * no answer to — and a merely *empty* answer is worse than no question at all, because an
 * operator reading "0 dropped frames" cannot tell it apart from "nothing is being read".
 *
 * So this is its own seam. The simulator binds [NoLinkHealth], which says exactly that and
 * cannot be mistaken for a healthy report.
 */
interface LinkHealthSource {

    /**
     * The link's own account of itself, or null when the source in use has no wire.
     *
     * A flow rather than a getter: the diagnostics screen has to keep up with a link that
     * changes underneath it, and polling would be a second clock to keep in step with the
     * first — one that would keep ticking on a screen nobody is looking at.
     */
    fun linkHealthFlow(): Flow<LinkHealth?>

    companion object {
        /**
         * The answer from a source with no wire.
         *
         * Null rather than a [LinkHealth] full of zeroes. Zeroes are a *measurement* — "I
         * watched the link and nothing went wrong" — and this class has nothing to measure
         * with. The screen renders the two differently, and that is the whole point.
         */
        val NoLinkHealth: LinkHealthSource = object : LinkHealthSource {
            override fun linkHealthFlow(): Flow<LinkHealth?> = flowOf(null)
        }
    }
}
