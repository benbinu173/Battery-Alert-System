package com.batteryalert.guard.presentation.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batteryalert.guard.data.repository.BlackboxRepository
import com.batteryalert.guard.data.telemetry.mavlink.CellDataTrust
import com.batteryalert.guard.data.telemetry.mavlink.LinkHealth
import com.batteryalert.guard.domain.model.BlackboxRecord
import com.batteryalert.guard.domain.model.BlackboxTrigger
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.presentation.components.Readout
import com.batteryalert.guard.presentation.components.SectionCard
import com.batteryalert.guard.presentation.components.StatusPill
import com.batteryalert.guard.presentation.distanceUnit
import com.batteryalert.guard.presentation.format
import com.batteryalert.guard.presentation.formatAmps
import com.batteryalert.guard.presentation.formatCelsius
import com.batteryalert.guard.presentation.formatClockTime
import com.batteryalert.guard.presentation.formatCoordinate
import com.batteryalert.guard.presentation.formatDistance
import com.batteryalert.guard.presentation.formatDurationMillis
import com.batteryalert.guard.presentation.formatVolts
import com.batteryalert.guard.presentation.indicatorColor
import com.batteryalert.guard.presentation.label
import com.batteryalert.guard.presentation.shortLabel
import com.batteryalert.guard.presentation.theme.GuardColors

/**
 * FR 5.3's log, made readable.
 *
 * The requirements ask for the blackbox to exist; this is the screen that answers the
 * question it was kept for — *what happened, and how bad did it get*. It shows a summary of
 * one flight above the raw rows, because a table of a thousand samples is evidence, not an
 * answer.
 *
 * Nothing here computes a safety number, but it does read one back. The level on each row,
 * and the colour on each row, were decided by the alert engine before the row was written
 * and are rendered here through the same `indicatorColor()` table the dashboard uses. A log
 * that re-derived its own severity would be a second opinion, and a second opinion about a
 * battery is a liability.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(GuardColors.Background)
            .safeDrawingPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state, onBack) }

        state.error?.let { message ->
            item { ErrorCard(message) }
        }

        item { SummaryCard(state) }

        item { LinkCard(state.linkHealth) }

        if (state.recentRecords.isEmpty() && state.error == null) {
            item { EmptyCard() }
        } else {
            item { TableHeaderRow() }
            // No item keys: this is a fixed-length window on a rolling buffer whose oldest
            // row disappears as the newest arrives, so identity is positional. Giving the
            // rows a key derived from their contents would re-key the whole list on every
            // tick and, worse, collide whenever two rows shared a millisecond.
            items(items = state.recentRecords) { record -> LogRow(record) }
        }

        item { StorageCard(state = state, onClearLog = viewModel::onClearLog) }
    }
}

@Composable
private fun Header(state: DiagnosticsUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Flight recorder",
            color = GuardColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(12.dp))
        // The tagline yields before anything else does. A row over-subscribed by a few
        // characters should lose part of a sentence nobody needs, not the row count the
        // operator came here to read — which is what happened when this spacer carried the
        // weight instead and collapsed to nothing.
        Text(
            text = "What the blackbox kept, and why",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        StatusPill(
            text = if (state.hasLog) "${state.rowCount} rows" else "empty",
            dotColor = if (state.hasLog) GuardColors.Healthy else GuardColors.Idle,
            textColor = if (state.hasLog) GuardColors.Healthy else GuardColors.TextSecondary,
        )
        Spacer(Modifier.width(8.dp))
        SmallButton(label = "Dashboard", onClick = onBack)
    }
}

/**
 * The flight summary.
 *
 * Every figure is a reduction `BlackboxAnalysis` performed over the stored rows. The card's
 * pill is the worst level the flight reached — taken from the engine's own verdict on each
 * row, not recomputed from the voltages here.
 */
@Composable
private fun SummaryCard(state: DiagnosticsUiState) {
    val summary = state.summary

    SectionCard(
        title = "Flight summary",
        trailing = {
            if (summary != null) {
                StatusPill(
                    text = summary.worstLevel.label(),
                    dotColor = summary.worstLevel.indicatorColor(),
                    textColor = summary.worstLevel.indicatorColor(),
                )
            }
        },
    ) {
        if (summary == null) {
            Text(
                text = "No flight has been logged yet. The recorder writes its first row when " +
                    "telemetry connects.",
                color = GuardColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            return@SectionCard
        }

        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Started",
                value = summary.startedAtMillis.formatClockTime(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Duration",
                value = summary.durationMillis.formatDurationMillis(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Samples kept",
                value = summary.sampleCount.toString(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Link",
                value = if (summary.linkHeldThroughout) "held" else "dropped",
                modifier = Modifier.weight(1f),
                valueColor = if (summary.linkHeldThroughout) {
                    GuardColors.Healthy
                } else {
                    GuardColors.WarningAmber
                },
                valueFontSize = 20.sp,
            )
        }

        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Lowest charge",
                value = summary.lowestPercentage.format(0),
                unit = "%",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Lowest pack",
                value = summary.lowestPackVolts.formatVolts(),
                unit = "V",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Weakest cell",
                value = summary.lowestCellVolts.formatVolts(),
                unit = "V",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Peak current",
                value = summary.peakAmps.formatAmps(),
                unit = "A",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
        }

        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Peak temperature",
                value = summary.peakTemperatureCelsius.formatCelsius(),
                unit = "°C",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Furthest from home",
                value = summary.maxDistanceFromHomeMeters.formatDistance(),
                unit = summary.maxDistanceFromHomeMeters.distanceUnit(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
        }

        // The rules that fired, in the order the flight met them. Spelled out rather than
        // coloured, so the summary can be read without the table underneath it.
        if (summary.rulesRaised.isEmpty()) {
            Text(
                text = "No alert rule fired during this flight.",
                color = GuardColors.TextMuted,
                fontSize = 11.sp,
            )
        } else {
            Text(
                text = "Rules raised, in order: " +
                    summary.rulesRaised.joinToString(" then ") { it.shortLabel() },
                color = GuardColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

/**
 * What the *wire* is doing, as opposed to what the log says happened.
 *
 * These are the numbers that tell apart four problems which all look identical from the
 * dashboard — nothing plugged in, bytes arriving that are not MAVLink, MAVLink failing its
 * checksums against this app's constants, and MAVLink this app does not decode. The rows are
 * ordered by that question rather than by size: what got through, what was rejected, and what
 * was thrown away before it could even be tried.
 *
 * Nothing here is a judgement. Every counter and every confidence level was decided in the
 * decoder; this is where they are read back.
 */
@Composable
private fun LinkCard(health: LinkHealth?) {
    SectionCard(
        title = "Telemetry link",
        trailing = {
            if (health != null) {
                StatusPill(
                    text = if (health.bytesArriving) "Bytes arriving" else "Silent",
                    dotColor = if (health.bytesArriving) GuardColors.Healthy else GuardColors.Idle,
                    textColor = if (health.bytesArriving) {
                        GuardColors.Healthy
                    } else {
                        GuardColors.TextSecondary
                    },
                )
            }
        },
    ) {
        if (health == null) {
            Text(
                text = "No wire to report on — the telemetry source in use is the simulator, " +
                    "which has no link to measure. Shown as nothing rather than as a row of " +
                    "zeroes, because zeroes are a measurement and would read as a healthy, " +
                    "quiet link.",
                color = GuardColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            return@SectionCard
        }

        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Frames decoded",
                value = health.framesDecoded.toString(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Checksum failures",
                value = health.checksumFailures.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (health.checksumFailures > 0) {
                    GuardColors.WarningAmber
                } else {
                    GuardColors.TextPrimary
                },
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Bytes discarded",
                value = health.bytesDiscarded.toString(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
        }

        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Unread messages",
                value = health.unsupportedMessages.toString(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Cell figures",
                value = health.cellDataTrust.trustLabel(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Reopen attempts",
                value = health.reconnectAttempts.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (health.reconnectAttempts > 0) {
                    GuardColors.WarningAmber
                } else {
                    GuardColors.TextPrimary
                },
                valueFontSize = 20.sp,
            )
        }

        if (health.dialectCorrections.isNotEmpty()) {
            // The parser has already corrected itself, so nothing is broken — but a wrong
            // constant means the *shipped* table is wrong for this airframe, and the values
            // the link proved are worth carrying back into the source.
            Text(
                text = "Dialect corrected from the link: " + health.dialectCorrections.entries
                    .joinToString(", ") { (id, extra) -> "msg $id -> CRC extra $extra" },
                color = GuardColors.NoticeYellow,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

/**
 * The corroboration verdict in words.
 *
 * Spelled out rather than coloured: "uncorroborated" is not a warning level, it is the
 * absence of a measurement, and an operator who reads it as an alarm would be chasing a cell
 * problem that may not exist.
 */
private fun CellDataTrust.trustLabel(): String = when (this) {
    CellDataTrust.NOT_REPORTED -> "not reported"
    CellDataTrust.TRUSTED -> "trusted"
    CellDataTrust.UNCORROBORATED -> "withheld"
}

@Composable
private fun ErrorCard(message: String) {
    SectionCard(title = "Log unavailable") {
        Text(
            text = "The flight recorder could not be read: $message",
            color = GuardColors.WarningAmber,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Text(
            text = "Flying is unaffected — the recorder keeps writing. Only this view is " +
                "affected, and it is the one screen that must still open when something is " +
                "wrong.",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun EmptyCard() {
    SectionCard(title = "Log") {
        Text(
            text = "Nothing recorded yet — the table fills as telemetry arrives.",
            color = GuardColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

/**
 * The raw log.
 *
 * The `Why` column is the point of the table. A dense cluster of rows says that something
 * happened; without the trigger it cannot say *what*, and the reader is left guessing
 * whether the pack sagged or the link hiccuped.
 */
@Composable
private fun TableHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Time", COLUMN_TIME)
        HeaderCell("Why", COLUMN_WHY)
        HeaderCell("Level", COLUMN_LEVEL)
        HeaderCell("Pack V", COLUMN_NUMBER)
        HeaderCell("Amps", COLUMN_NUMBER)
        HeaderCell("Temp", COLUMN_NUMBER)
        HeaderCell("ΔV", COLUMN_NUMBER)
        HeaderCell("Position", COLUMN_POSITION)
    }
}

@Composable
private fun LogRow(record: BlackboxRecord) {
    val sample = record.sample
    val level = sample.alertLevel
    val cadence = record.trigger == BlackboxTrigger.CADENCE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                // Only rows that exist for a reason get a background. A cadence row is the
                // log ticking, and tinting those would tint almost everything.
                if (cadence) Color.Transparent else level.indicatorColor().copy(alpha = 0.10f),
            )
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberCell(
            text = record.timestampMillis.formatClockTime(),
            weight = COLUMN_TIME,
            color = GuardColors.TextSecondary,
        )
        NumberCell(
            text = record.trigger.triggerLabel(),
            weight = COLUMN_WHY,
            color = if (cadence) GuardColors.TextMuted else GuardColors.TextPrimary,
        )
        NumberCell(level.label(), COLUMN_LEVEL, level.indicatorColor())
        NumberCell(sample.packVolts.formatVolts(), COLUMN_NUMBER, GuardColors.TextPrimary)
        NumberCell(sample.currentAmps.formatAmps(), COLUMN_NUMBER, GuardColors.TextPrimary)
        NumberCell(sample.temperatureCelsius.formatCelsius(), COLUMN_NUMBER, GuardColors.TextPrimary)
        NumberCell(sample.cellDeltaVolts.formatVolts(3), COLUMN_NUMBER, GuardColors.TextPrimary)
        NumberCell(positionOf(record), COLUMN_POSITION, GuardColors.TextSecondary)
    }
}

@Composable
private fun StorageCard(state: DiagnosticsUiState, onClearLog: () -> Unit) {
    // Two-step, because this is the only destructive control in the app and the operator is
    // wearing gloves on a moving vehicle.
    var confirming by remember { mutableStateOf(false) }

    SectionCard(title = "Storage") {
        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Rows held",
                value = state.rowCount.toString(),
                modifier = Modifier.weight(1f),
                valueFontSize = 18.sp,
            )
            Readout(
                label = "Rolling cap",
                value = state.rowCap.toString(),
                modifier = Modifier.weight(1f),
                valueFontSize = 18.sp,
            )
            Readout(
                label = "Dropped",
                value = state.droppedRecords.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (state.droppedRecords == 0) {
                    GuardColors.TextPrimary
                } else {
                    GuardColors.WarningAmber
                },
                valueFontSize = 18.sp,
            )
            Readout(
                label = "Flights",
                value = state.sessions.size.toString(),
                modifier = Modifier.weight(1f),
                valueFontSize = 18.sp,
            )
        }

        Text(
            text = "The log is a rolling buffer: past " +
                "${BlackboxRepository.MAX_ROWS} rows the oldest are discarded. It is " +
                "diagnostic data, not an archive — anything that has to survive a flight " +
                "belongs somewhere else.",
            color = GuardColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        if (state.droppedRecords > 0) {
            Text(
                text = "${state.droppedRecords} rows were lost because the recorder's queue " +
                    "filled. That is a real gap in this log, not a quiet moment in the flight.",
                color = GuardColors.WarningAmber,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }

        SmallButton(
            label = if (confirming) "Tap again to erase the whole log" else "Erase log",
            onClick = {
                if (confirming) {
                    onClearLog()
                    confirming = false
                } else {
                    confirming = true
                }
            },
            accent = if (confirming) GuardColors.CriticalRed else GuardColors.TextSecondary,
        )
    }
}

// --- Tiny layout helpers -------------------------------------------------------------
// Column weights as constants, so the header and the rows cannot drift apart.

private const val COLUMN_TIME = 0.9f
private const val COLUMN_WHY = 1.3f
private const val COLUMN_LEVEL = 1.0f
private const val COLUMN_NUMBER = 0.85f
private const val COLUMN_POSITION = 1.8f

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text.uppercase(),
        color = GuardColors.TextMuted,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun RowScope.NumberCell(text: String, weight: Float, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit, accent: Color = GuardColors.Accent) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** The pack's position at that instant, or why there isn't one. */
private fun positionOf(record: BlackboxRecord): String {
    val sample = record.sample
    if (sample.connectionState != ConnectionState.CONNECTED) return "no link"
    if (!sample.gpsLocked) return "no fix"

    val latitude = sample.latitude
    val longitude = sample.longitude
    if (latitude == null || longitude == null) return "no fix"

    return "${latitude.formatCoordinate(4)}, ${longitude.formatCoordinate(4)}"
}

/**
 * The `Why` column's wording.
 *
 * Named `triggerLabel` rather than `shortLabel` so it cannot be confused with — or
 * accidentally shadow — the alert-rule `shortLabel` this screen also imports.
 */
private fun BlackboxTrigger.triggerLabel(): String = when (this) {
    BlackboxTrigger.SESSION_START -> "session"
    BlackboxTrigger.ALERT_CHANGE -> "alert"
    BlackboxTrigger.LINK_CHANGE -> "link"
    BlackboxTrigger.HIGH_RESOLUTION -> "detail"
    BlackboxTrigger.CADENCE -> "tick"
}
