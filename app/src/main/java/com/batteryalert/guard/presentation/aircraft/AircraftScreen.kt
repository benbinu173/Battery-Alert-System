package com.batteryalert.guard.presentation.aircraft

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
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.presentation.components.Readout
import com.batteryalert.guard.presentation.components.SectionCard
import com.batteryalert.guard.presentation.components.StatusPill
import com.batteryalert.guard.presentation.format
import com.batteryalert.guard.presentation.formatDuration
import com.batteryalert.guard.presentation.formatPercent
import com.batteryalert.guard.presentation.theme.GuardColors

/**
 * The one thing the operator has to tell the app before it can be useful on a real aircraft.
 *
 * ### Why this is its own screen
 *
 * It is not diagnostics and it is not flight data. The flight recorder answers "what happened";
 * the dashboard answers "how is it now"; this answers "which aircraft am I flying", which is
 * the question that has to be settled *first*, at the bench, before the props go on. Putting it
 * on the dashboard would put a settings control on the safety display; putting it in the
 * recorder would hide a pre-flight step inside a post-flight one.
 *
 * ### What the screen is for, in one line
 *
 * The autopilot reports a percentage of the pack but never the pack's size, so the app cannot
 * turn "12% per minute" into a flight time until someone says what 100% is. That number is
 * what this screen collects, and [ConfiguredCard] is what shows it doing something.
 */
@Composable
fun AircraftScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AircraftViewModel = hiltViewModel(),
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
        CapacityCard(
            state = state,
            onInputChanged = viewModel::onCapacityInputChanged,
            onSave = viewModel::onSaveCapacity,
        )
        ConfiguredCard(state)
        ProvenanceCard()
    }
}

@Composable
private fun Header(state: AircraftUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Aircraft",
            color = GuardColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(12.dp))
        // The tagline is the only element here that may give up space, so it is the only one
        // that is allowed to. Everything else — the titled heading, the state pill, the way
        // back — is pinned to one line at its natural size, because those are the three things
        // this header has to say. `fill = false` keeps it at its own width on a wide screen
        // rather than spreading it across the gap; it only shrinks when there is nothing left.
        Text(
            text = "What the app knows that the link does not",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        // The state of the configuration as a whole, in one glance: the operator should not
        // have to read a sentence to find out whether the app can compute a flight time.
        //
        // Deliberately not green. `GuardColors` reserves green, amber and red for claims the
        // alert engine made, and this screen raises no alerts at all — using the same green
        // here would teach the operator that the safety palette also means "a setting is
        // fine", which is the one thing it must never mean.
        StatusPill(
            text = if (state.isConfigured) "configured" else "not set",
            dotColor = if (state.isConfigured) GuardColors.Accent else GuardColors.Idle,
            textColor = if (state.isConfigured) GuardColors.Accent else GuardColors.TextSecondary,
        )
        Spacer(Modifier.width(8.dp))
        SmallButton(label = "Dashboard", onClick = onBack)
    }
}

@Composable
private fun CapacityCard(
    state: AircraftUiState,
    onInputChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    SectionCard(title = "Pack capacity") {
        Text(
            text = "The total capacity of the pack this aircraft flies. The autopilot reports " +
                "the percentage left but never the size of the pack, so this is what turns an " +
                "estimate in mAh into a flight time.",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapacityField(
                value = state.capacityInput,
                hasError = state.capacityError != null,
                onValueChange = onInputChanged,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            SaveButton(
                // Disabled rather than hidden: a control that only appears once the entry is
                // valid leaves the operator looking for it. This one is visibly there and
                // visibly not available yet, which is a different and clearer message.
                enabled = state.hasPendingEdit,
                onClick = onSave,
            )
        }

        val error = state.capacityError
        when {
            // Amber, though the palette reserves amber for the alert engine — because there is
            // no alert engine on this screen for it to be mistaken for. The reservation exists
            // so that a colour on the *dashboard* is always a safety claim; here the only
            // thing a colour can be about is the field above it.
            error != null -> FieldMessage(error, GuardColors.WarningAmber)

            state.showsSavedValue -> FieldMessage(
                text = "${state.packCapacityMah!!.format(0)} mAh is in use.",
                color = GuardColors.Accent,
            )

            else -> FieldMessage(
                text = "Leave the box empty and save to remove the configuration.",
                color = GuardColors.TextMuted,
            )
        }
    }
}

/**
 * The numeric entry.
 *
 * `BasicTextField` rather than a Material `OutlinedTextField` for one concrete reason: the
 * Material field brings its own container, label, indicator and colour roles, and every one of
 * them would have to be overridden to stop it looking like a Material app inside a dashboard
 * that is deliberately not one. Drawing the box here is less code than undoing that one.
 */
@Composable
private fun CapacityField(
    value: String,
    hasError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(GuardColors.CardRaised)
            .border(
                width = 1.dp,
                color = if (hasError) GuardColors.WarningAmber else GuardColors.Outline,
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = "Enter capacity",
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
                // A numeric keypad rather than a full keyboard: the value is a whole number of
                // mAh, and on a 7-inch screen every centimetre of keyboard is a centimetre of
                // telemetry. The field still accepts typed separators, so a pasted "16,000"
                // does not need fixing by hand.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(GuardColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "mAh",
            color = GuardColors.TextMuted,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * What the configured capacity is currently producing, recomputed live.
 *
 * An operator setting this at the bench is typing a number they cannot verify until the
 * aircraft is switched on; this card is where they verify it. If the figures below are dashes
 * with a pack configured, the reason is the link and the message says so — the two causes look
 * identical on screen and need different fixes.
 */
@Composable
private fun ConfiguredCard(state: AircraftUiState) {
    SectionCard(title = "With this configured") {
        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "State of charge",
                value = state.batteryPercentage.formatPercent(),
                unit = "%",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Capacity left",
                value = state.remainingCapacityMah.format(0),
                unit = "mAh",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Discharge rate",
                value = state.dischargeRateMahPerMin.format(0),
                unit = "mAh/min",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Estimated flight time",
                value = state.estimatedFlightMinutes.formatDuration(),
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
        }

        // Five combinations, five different things for the operator to do about them, and the
        // screen has to tell them apart: the figures above are identical-looking dashes whether
        // the cause is a configuration that is missing, a link that is down, or a source that
        // is simply not reporting a state of charge yet.
        val note = when {
            state.isConfigured && state.remainingCapacityMah != null ->
                "Live, from the capacity above. Recomputed every frame by the same code the " +
                    "dashboard uses."

            state.remainingCapacityMah != null ->
                "Live. This source reports the charge left directly, so it does not need a " +
                    "capacity from here."

            state.connectionState != ConnectionState.CONNECTED ->
                "Waiting for telemetry. These figures need a state of charge and a current " +
                    "from the aircraft; nothing is wrong with the configuration."

            !state.isConfigured ->
                "No capacity is set and the source is not reporting the charge left either, " +
                    "so the app will not estimate a flight time. A guess here would be worse " +
                    "than a blank: a plausible wrong answer is one you would believe."

            else ->
                "The link is up but the autopilot is not reporting a state of charge yet."
        }

        Text(text = note, color = GuardColors.TextMuted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

/**
 * Where the number comes from, and where it does not.
 *
 * This card exists because the honest answer to "why do I have to type this?" is a MAVLink
 * detail, and an operator who does not get it will assume the app is unfinished. It is not:
 * the field the autopilot does send, `current_consumed`, counts charge used since power-up, so
 * reading it as "capacity left" would show a freshly charged pack as nearly empty on the
 * second flight of the day.
 */
@Composable
private fun ProvenanceCard() {
    SectionCard(title = "Where this number comes from") {
        Text(
            text = "No message in this app's dialect reports the pack's total capacity, and " +
                "the one that looks like it does not: `current_consumed` is charge used since " +
                "the flight controller was powered on, not charge remaining. Typing the " +
                "capacity is the only way to get a flight time that is true for this airframe " +
                "rather than for an assumed one.",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Text(
            text = "It is kept on the device and survives the app being closed, so it is set " +
                "once per aircraft rather than once per flight.",
            color = GuardColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
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
