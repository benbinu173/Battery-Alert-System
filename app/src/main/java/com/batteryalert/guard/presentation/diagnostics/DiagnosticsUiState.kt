package com.batteryalert.guard.presentation.diagnostics

import com.batteryalert.guard.data.repository.BlackboxRepository
import com.batteryalert.guard.data.telemetry.mavlink.LinkHealth
import com.batteryalert.guard.domain.model.BlackboxRecord
import com.batteryalert.guard.domain.usecase.BlackboxSessionSummary

/**
 * Everything the diagnostic view renders.
 *
 * Same rule as the dashboard: Compose formats and lays out, it never computes. The flight
 * summary in here was produced by `BlackboxAnalysis` from the stored rows; the screen only
 * decides where the numbers sit.
 */
data class DiagnosticsUiState(
    /** Rows the log is currently holding. */
    val rowCount: Int = 0,
    /** The rolling cap those rows are held within. */
    val rowCap: Int = BlackboxRepository.MAX_ROWS,
    /**
     * Rows lost because the recorder's queue overflowed.
     *
     * Shown, not hidden. A gap in a log that does not admit to being a gap reads as a
     * quiet moment in the flight, and that is the one thing a blackbox must never imply.
     */
    val droppedRecords: Int = 0,

    /** Every flight in the log, newest first. */
    val sessions: List<Long> = emptyList(),
    val selectedSessionId: Long? = null,
    val summary: BlackboxSessionSummary? = null,

    /** The newest rows, newest first, for the table. */
    val recentRecords: List<BlackboxRecord> = emptyList(),

    /**
     * What the wire is doing right now, or null when the source in use has no wire.
     *
     * Null is not "fine" and it is not "broken" — it is this app running on the simulator,
     * where there is no link to have an opinion about. The screen says so in words rather
     * than showing a set of zeroes that would read as a clean, quiet, healthy link.
     */
    val linkHealth: LinkHealth? = null,

    /**
     * Set when the log could not be read.
     *
     * The diagnostic view is the one screen that must still open when something is wrong —
     * it is where you go to find out what is wrong — so a storage failure renders as a
     * message here rather than as a crash.
     */
    val error: String? = null,
) {
    val hasLog: Boolean get() = rowCount > 0
}
