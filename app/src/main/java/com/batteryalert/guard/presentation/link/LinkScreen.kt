package com.batteryalert.guard.presentation.link

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batteryalert.guard.data.link.LinkMode
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.domain.usecase.UdpPort
import com.batteryalert.guard.presentation.components.ChoiceChip
import com.batteryalert.guard.presentation.components.SectionCard
import com.batteryalert.guard.presentation.components.StatusPill
import com.batteryalert.guard.presentation.theme.GuardColors

/**
 * Where the app gets its telemetry from.
 *
 * ### Why this is its own screen rather than a row on the dashboard
 *
 * The dashboard is the safety display: everything on it is either a measurement or a claim the
 * alert engine made. A mode selector and a port number are neither, and putting them there
 * would mean the one screen that has to be readable at a glance was also the screen where
 * someone could change what the aircraft is talking to. The Aircraft screen makes the same
 * argument about the pack capacity, and the two settings are the same kind of thing — bench
 * work, done before the props go on.
 *
 * ### What the operator actually does here
 *
 * Reads the drone's telemetry port off its ground station, types it into the box, and taps UDP.
 * Everything else on this screen exists to answer the two questions that follow: *is it
 * working*, and *if not, why not*.
 */
@Composable
fun LinkScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LinkViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GuardColors.Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(state, onBack)
        ModeCard(state, onModeSelected = viewModel::onModeSelected)
        PortCard(
            state = state,
            onPortChanged = viewModel::onPortInputChanged,
            onSave = viewModel::onSavePort,
        )
        LiveCard(state)
        LimitsCard()
    }
}

@Composable
private fun Header(state: LinkUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Link",
            color = GuardColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(12.dp))
        // The only element allowed to give way, and the only one whose giving way costs
        // nothing. The pill beside it is the one thing a glance at this screen has to return,
        // and a pill has no second line to fall back on.
        Text(
            text = "Where the numbers come from",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        // Which source is selected, not how the link is doing — the Live card below owns that,
        // and repeating it here would put the same fact in two places to disagree.
        //
        // Accent rather than a semantic colour, unlike the connection state a few rows down.
        // `GuardColors` reserves green, amber and red for claims about the aircraft, and which
        // radio the operator picked is not one. Same distinction the Aircraft screen draws for
        // "configured".
        StatusPill(
            text = state.modeLabel,
            dotColor = GuardColors.Accent,
            textColor = GuardColors.Accent,
        )
        Spacer(Modifier.width(8.dp))
        SmallButton(label = "Dashboard", onClick = onBack)
    }
}

/**
 * The source itself.
 *
 * Three chips rather than a dropdown: on a 7-inch screen a dropdown costs two taps, hides the
 * alternatives, and cannot show the operator that there are exactly three. All three states fit
 * on one line.
 */
@Composable
private fun ModeCard(state: LinkUiState, onModeSelected: (LinkMode) -> Unit) {
    SectionCard(title = "Source") {
        Text(
            text = "Which of the three the app reads telemetry from. Changing this takes effect " +
                "immediately — the running link is closed and the new one opened.",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkMode.entries.forEach { mode ->
                ChoiceChip(
                    label = mode.chipLabel(),
                    selected = mode == state.mode,
                    onClick = { onModeSelected(mode) },
                )
            }
        }

        Text(
            text = state.modeSummary,
            color = GuardColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

/**
 * The port.
 *
 * `mAh` becomes nothing here and the box stands alone, unlike the capacity field: a port needs
 * no unit, and the placeholder says what to type more usefully than a suffix would.
 */
@Composable
private fun PortCard(
    state: LinkUiState,
    onPortChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    SectionCard(
        title = "UDP port",
        trailing = {
            if (!state.portApplies) {
                // A quiet marker rather than a disabled field. The operator setting this up at
                // the bench may well want to type the port before switching the source to UDP,
                // and a control that is greyed out reads as broken rather than as not yet
                // relevant.
                StatusPill(
                    text = "not in use",
                    dotColor = GuardColors.Idle,
                    textColor = GuardColors.TextSecondary,
                )
            }
        },
    ) {
        Text(
            text = "The port the drone sends its telemetry to. The app listens on it; it never " +
                "transmits. ${UdpPort.DEFAULT_PORT} is the MAVLink convention, which is a " +
                "starting point and not a guess about your aircraft.",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PortField(
                value = state.portInput,
                hasError = state.portError != null,
                onValueChange = onPortChanged,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            SaveButton(enabled = state.hasPendingEdit, onClick = onSave)
        }

        val error = state.portError
        when {
            // Amber, though the palette reserves amber for the alert engine — because there is
            // no alert engine on this screen for it to be mistaken for. The reservation exists
            // so that a colour on the *dashboard* is always a safety claim; here the only thing
            // a colour can be about is the field above it.
            error != null -> FieldMessage(error, GuardColors.WarningAmber)

            state.showsSavedValue -> FieldMessage(
                text = if (state.portApplies) {
                    "Listening on ${state.udpPort}."
                } else {
                    "${state.udpPort} is stored. It is used once the source above is UDP."
                },
                color = GuardColors.Accent,
            )

            else -> FieldMessage(
                text = "Save to apply. The link restarts on the new port.",
                color = GuardColors.TextMuted,
            )
        }
    }
}

/**
 * What the link is doing at this moment.
 *
 * Deliberately **not** a second copy of the frame counters and checksum failures. Those live on
 * the Recorder screen's Telemetry link card, and a duplicate would be two screens holding two
 * chances to disagree about the same wire. What is here is the part those counters cannot say:
 * which transport is open, and what the state word actually means for the mode in force.
 */
@Composable
private fun LiveCard(state: LinkUiState) {
    SectionCard(
        title = "Right now",
        trailing = {
            StatusPill(
                text = state.connectionLabel,
                dotColor = state.connectionColor(),
                textColor = state.connectionColor(),
            )
        },
    ) {
        Text(
            text = "Transport",
            color = GuardColors.TextMuted,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
        )
        Text(
            text = state.transportName,
            color = GuardColors.TextPrimary,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = state.statusNote(),
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

/**
 * The one thing about this screen that is not obvious, and the first thing to check when
 * nothing arrives.
 *
 * Written down on the screen rather than left in a README because the failure it describes is
 * invisible: a socket that is bound and working looks exactly like a socket that is bound and
 * being ignored, and the operator would go looking at the aircraft instead of at their link.
 */
@Composable
private fun LimitsCard() {
    SectionCard(title = "What this does not do") {
        Text(
            text = "The app only listens. It sends no heartbeat and asks for no data stream, " +
                "which is correct for a datalink that is already broadcasting — and wrong for " +
                "one that stays quiet until it has heard from a ground station. Against those, " +
                "the port binds, the link reports silence, and the fault is that nothing has " +
                "been asked for.",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Text(
            text = "Two seconds of silence is reported as a link error. For a cable that has " +
                "been pulled that is exactly right; for a socket waiting on an aircraft that " +
                "is switched off it is a little blunt. Either way it means nothing is arriving, " +
                "and the Recorder screen says how much of it there has been.",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

// --- Pieces ---------------------------------------------------------------------------

/**
 * The numeric entry.
 *
 * `BasicTextField` rather than a Material `OutlinedTextField`, for the reason the capacity
 * field gives: the Material field brings a container, a label, an indicator and a set of colour
 * roles that would each have to be undone to stop it looking like a Material app inside a
 * dashboard that deliberately is not one.
 */
@Composable
private fun PortField(
    value: String,
    hasError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(GuardColors.CardRaised)
            .border(
                width = 1.dp,
                color = if (hasError) GuardColors.WarningAmber else GuardColors.Outline,
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = "Enter port",
                color = GuardColors.TextMuted,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = GuardColors.TextPrimary,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            // A numeric keypad: a port is a whole number, and on a 7-inch screen every
            // centimetre of keyboard is a centimetre of telemetry.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(GuardColors.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FieldMessage(text: String, color: Color) {
    Text(text = text, color = color, fontSize = 11.sp, lineHeight = 15.sp)
}

@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit) {
    val accent = if (enabled) GuardColors.Accent else GuardColors.Idle
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(accent.copy(alpha = if (enabled) 0.14f else 0.06f))
            .border(1.dp, accent.copy(alpha = if (enabled) 0.6f else 0.3f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Save",
            color = if (enabled) GuardColors.Accent else GuardColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(GuardColors.CardRaised)
            .border(1.dp, GuardColors.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = GuardColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// --- Wording --------------------------------------------------------------------------

private fun LinkMode.chipLabel(): String = when (this) {
    LinkMode.DEMO -> "Simulator"
    LinkMode.UDP -> "UDP"
    LinkMode.USB -> "Serial"
}

/**
 * Green, amber and red, matching the dashboard's link pill word for word.
 *
 * Unlike the mode pill in the header, this one *is* a claim about the link, and the palette's
 * reservation is about who is allowed to make such a claim — here, the link itself. Saying
 * "Link up" in one colour on one screen and another colour on the next would be worse than
 * either choice.
 */
private fun LinkUiState.connectionColor(): Color = when (connectionState) {
    ConnectionState.CONNECTED -> GuardColors.Healthy
    ConnectionState.CONNECTING -> GuardColors.NoticeYellow
    ConnectionState.RECONNECTING -> GuardColors.NoticeYellow
    ConnectionState.DISCONNECTED -> GuardColors.Idle
    ConnectionState.ERROR -> GuardColors.CriticalRed
}

/**
 * What the state word means for the mode in force.
 *
 * The state alone is not enough to act on. "Link error" against a UDP socket could be a
 * privileged port, a port already in use, or a socket that opened fine and has heard nothing —
 * and those have three different fixes.
 */
private fun LinkUiState.statusNote(): String = when {
    mode == LinkMode.DEMO ->
        "Simulated telemetry, generated on this device. There is no wire to be up or down, " +
            "and nothing here depends on an aircraft."

    connectionState == ConnectionState.ERROR && mode == LinkMode.UDP ->
        "Either the port could not be opened — something else is using it, or it needs root " +
            "— or it is open and nothing has arrived for two seconds. The Recorder screen's " +
            "Telemetry link card tells the two apart."

    connectionState == ConnectionState.ERROR ->
        "The adapter could not be opened. Check that it is attached and that permission was " +
            "granted when the app asked for it."

    connectionState == ConnectionState.CONNECTED && mode == LinkMode.UDP ->
        "Bound and receiving. The frame counters, checksum failures and reopen attempts are " +
            "on the Recorder screen."

    connectionState == ConnectionState.CONNECTED ->
        "The adapter is open and frames are arriving. The Recorder screen has the counters."

    else ->
        "Nothing is open yet. The link is brought up with the rest of the app; a source that " +
            "cannot be opened is retried on a backoff rather than given up on."
}
