package com.batteryalert.guard.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.presentation.CELL_RULES
import com.batteryalert.guard.presentation.PACK_VOLTAGE_RULES
import com.batteryalert.guard.presentation.components.BatteryArcGauge
import com.batteryalert.guard.presentation.components.CellBalanceChart
import com.batteryalert.guard.presentation.components.ChoiceChip
import com.batteryalert.guard.presentation.components.Readout
import com.batteryalert.guard.presentation.components.SectionCard
import com.batteryalert.guard.presentation.components.Sparkline
import com.batteryalert.guard.presentation.components.StatusPill
import com.batteryalert.guard.presentation.components.ThresholdBar
import com.batteryalert.guard.presentation.distanceUnit
import com.batteryalert.guard.presentation.format
import com.batteryalert.guard.presentation.formatAmps
import com.batteryalert.guard.presentation.formatCelsius
import com.batteryalert.guard.presentation.formatCoordinate
import com.batteryalert.guard.presentation.formatDistance
import com.batteryalert.guard.presentation.formatDuration
import com.batteryalert.guard.presentation.formatDurationSeconds
import com.batteryalert.guard.presentation.formatPercent
import com.batteryalert.guard.presentation.formatVolts
import com.batteryalert.guard.presentation.indicatorColor
import com.batteryalert.guard.presentation.interlockColor
import com.batteryalert.guard.presentation.label
import com.batteryalert.guard.presentation.shortLabel
import com.batteryalert.guard.presentation.theme.GuardColors

/**
 * FR 2.2 imbalance limit, drawn as the cell chart's limit line.
 *
 * Display only. The Phase 5 alert engine owns the actual evaluation of this threshold —
 * nothing here decides whether the pack is in fault.
 */
private const val CELL_IMBALANCE_LIMIT_VOLTS = 0.08

/** Tables wider than this get the two-column layout. */
private val WIDE_LAYOUT_MIN_WIDTH = 720.dp

/**
 * The single operator screen.
 *
 * Everything shown here comes from [DashboardUiState]. The screen never inspects a
 * threshold, never parses anything, and never talks to a telemetry source. Every colour
 * that could read as a safety claim comes from a level the alert engine chose — either
 * [DashboardUiState.alertLevel] or the per-card [DashboardUiState.levelFor] — via
 * `indicatorColor()`, so the display cannot claim a state the domain layer has not
 * declared.
 *
 * The layout is driven by width rather than by orientation, because the target device is
 * a 7" landscape controller where the two-column arrangement fits everything on one
 * screen without scrolling.
 */
/**
 * The dashboard, stateless.
 *
 * It takes the state and the callbacks rather than reaching for a ViewModel of its own, and
 * that is not just a preview convenience. [com.batteryalert.guard.presentation.GuardApp]
 * collects the state one level up so that navigating to the flight recorder does not stop
 * the telemetry pipeline — which would leave a hole in the blackbox exactly while the
 * operator was reading it, and would silence an alert raised while the log was on screen.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onScenarioSelected: (String) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onOpenRecorder: () -> Unit,
    onOpenAircraft: () -> Unit,
) {
    DashboardContent(
        state = state,
        onScenarioSelected = onScenarioSelected,
        onSpeedSelected = onSpeedSelected,
        onOpenRecorder = onOpenRecorder,
        onOpenAircraft = onOpenAircraft,
    )
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onScenarioSelected: (String) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onOpenRecorder: () -> Unit,
    onOpenAircraft: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardColors.Background)
            .safeDrawingPadding(),
    ) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar(
                state = state,
                onOpenRecorder = onOpenRecorder,
                onOpenAircraft = onOpenAircraft,
            )
            AlertBanner(state)

            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BatteryCard(state)
                        FlightSafetyCard(state)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CellHealthCard(state)
                        PowerTrendCard(state)
                        PositionCard(state)
                    }
                }
            } else {
                BatteryCard(state)
                CellHealthCard(state)
                PowerTrendCard(state)
                FlightSafetyCard(state)
                PositionCard(state)
            }

            if (state.demoMode) {
                DemoControlsCard(
                    state = state,
                    onScenarioSelected = onScenarioSelected,
                    onSpeedSelected = onSpeedSelected,
                    columns = if (wide) 5 else 3,
                )
            }

            DataSourceDisclaimer()
        }
    }
}

// --- Chrome -------------------------------------------------------------------------

@Composable
private fun TopBar(
    state: DashboardUiState,
    onOpenRecorder: () -> Unit,
    onOpenAircraft: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "BATTERY GUARD",
                color = GuardColors.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.6.sp,
            )
            Text(
                text = "Agricultural drone battery monitor",
                color = GuardColors.TextMuted,
                fontSize = 10.sp,
                letterSpacing = 0.4.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.demoMode) {
            StatusPill(text = "Demo", dotColor = GuardColors.Accent)
            Spacer(Modifier.width(8.dp))
        }
        ConnectionPill(state.connectionState)
        Spacer(Modifier.width(8.dp))
        // Neutral accent, not a semantic colour: opening a log is not a safety state, and
        // colouring it like one would put a fourth green/amber/red thing on a screen whose
        // whole point is that those three mean something.
        NavButton(label = "Flight recorder", onClick = onOpenRecorder)
        Spacer(Modifier.width(6.dp))
        // The aircraft screen carries the pack capacity the flight-time and RTL figures are
        // built from, so the operator can reach the assumption behind a number from the
        // number itself rather than having to remember where it was set.
        NavButton(label = "Aircraft", onClick = onOpenAircraft)
    }
}

/**
 * The only navigation control on the dashboard.
 *
 * Deliberately not a `ChoiceChip`: that primitive means "this option is selected", and this
 * is a door, not a setting.
 */
@Composable
private fun NavButton(label: String, onClick: () -> Unit) {
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

@Composable
private fun ConnectionPill(connectionState: ConnectionState) {
    val (label, color) = when (connectionState) {
        ConnectionState.CONNECTED -> "Link up" to GuardColors.Healthy
        ConnectionState.CONNECTING -> "Connecting" to GuardColors.NoticeYellow
        ConnectionState.RECONNECTING -> "Reconnecting" to GuardColors.NoticeYellow
        ConnectionState.DISCONNECTED -> "No link" to GuardColors.Idle
        ConnectionState.ERROR -> "Link error" to GuardColors.CriticalRed
    }
    StatusPill(text = label, dotColor = color)
}

/**
 * The headline alert, or nothing at all when the engine has raised none.
 *
 * There is deliberately no dismiss control. FR 3.2 requires the critical alert to be
 * un-dismissable, and a banner whose absence *is* the all-clear is the simplest way to
 * guarantee that: there is no state in which the operator has closed a live warning. It
 * leaves when the condition leaves and not before.
 *
 * The banner shows the worst active condition; the conditions underneath it get their own
 * chips. That matters more than it sounds — a pack can be Critical *because* it is
 * imbalanced, and an operator who only reads "Critical" may turn for home and keep flying
 * a pack whose real problem will not be fixed by landing sooner.
 */
@Composable
private fun AlertBanner(state: DashboardUiState) {
    val alert = state.primaryAlert ?: return

    val color = alert.level.indicatorColor()
    val shape = RoundedCornerShape(10.dp)
    val alsoActive = state.alerts.drop(1)

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(color.copy(alpha = 0.14f))
                .border(1.dp, color.copy(alpha = 0.6f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(
                text = alert.level.label().uppercase(),
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = alert.message,
                    color = GuardColors.TextPrimary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // The action prompt the requirements give every alert. It is part of
                    // the alert, not decoration: knowing you are Critical is not the same
                    // as knowing what to do about it.
                    text = alert.action,
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (alsoActive.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                alsoActive.forEach { other ->
                    val otherColor = other.level.indicatorColor()
                    Text(
                        text = other.rule.shortLabel(),
                        color = otherColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(otherColor.copy(alpha = 0.14f))
                            .border(
                                1.dp,
                                otherColor.copy(alpha = 0.4f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

// --- Battery ------------------------------------------------------------------------

@Composable
private fun BatteryCard(state: DashboardUiState) {
    // The one card that takes the global headline. This is the primary indicator — the
    // first thing the operator looks at — so it must not be quieter than the situation.
    val color = state.alertLevel.indicatorColor()

    SectionCard(
        title = "Battery pack",
        trailing = { ConfigurationPill(state) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryArcGauge(
                fraction = (state.batteryPercentage ?: 0) / 100f,
                color = color,
                modifier = Modifier.size(168.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = state.batteryPercentage.formatPercent(),
                            color = color,
                            fontSize = 44.sp,
                            lineHeight = 46.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "%",
                            color = color.copy(alpha = 0.7f),
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Text(
                        text = "STATE OF CHARGE",
                        color = GuardColors.TextMuted,
                        fontSize = 8.sp,
                        letterSpacing = 1.3.sp,
                    )
                }
            }

            Spacer(Modifier.width(18.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Readout(
                        label = "Voltage",
                        value = state.totalVoltage.formatVolts(),
                        unit = "V",
                        modifier = Modifier.weight(1f),
                        valueFontSize = 24.sp,
                    )
                    Readout(
                        label = "Current",
                        value = state.current.formatAmps(),
                        unit = "A",
                        modifier = Modifier.weight(1f),
                        valueFontSize = 24.sp,
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    Readout(
                        label = "Temperature",
                        value = state.temperature.formatCelsius(),
                        unit = "°C",
                        modifier = Modifier.weight(1f),
                        valueFontSize = 24.sp,
                    )
                    Readout(
                        label = "Capacity left",
                        value = state.remainingCapacityMah.format(0),
                        unit = "mAh",
                        modifier = Modifier.weight(1f),
                        valueFontSize = 24.sp,
                    )
                }

                // Only when a capacity was configured. The demo source reports its charge
                // left directly, so nothing is configured there and this line stays absent —
                // a caption claiming a pack size nobody entered would be the app inventing a
                // fact about the aircraft.
                val configuredCapacity = state.packCapacityMah
                if (configuredCapacity != null) {
                    Text(
                        text = "Computed from the ${configuredCapacity.format(0)} mAh pack " +
                            "set on the Aircraft screen.",
                        color = GuardColors.TextMuted,
                        fontSize = 10.sp,
                    )
                }
            }
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

        SagStrip(state)
    }
}

@Composable
private fun ConfigurationPill(state: DashboardUiState) {
    val configuration = state.batteryConfiguration ?: return
    StatusPill(text = configuration.label, dotColor = GuardColors.Accent)
}

/**
 * Shows FR 2.1 in the open: the loaded reading, the resting estimate derived from it, and
 * the resistance the estimate was run with. When the two diverge sharply the operator can
 * see *why*, which a single voltage readout would hide.
 */
@Composable
private fun SagStrip(state: DashboardUiState) {
    val measured = state.totalVoltage
    val resting = state.restingPackVolts
    val cellCount = state.cellCount
    val resistanceMilliohm = state.internalResistanceOhmPerCell * 1_000.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GuardColors.CardRaised)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "LOAD SAG COMPENSATION",
                color = GuardColors.TextMuted,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "Ri ${resistanceMilliohm.format(1)} mΩ/cell · default",
                color = GuardColors.TextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Loaded ${measured.formatVolts()} V",
                color = GuardColors.TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "  →  ",
                color = GuardColors.TextMuted,
                fontSize = 13.sp,
            )
            Text(
                text = "Resting ${resting.formatVolts()} V",
                color = GuardColors.Accent,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (cellCount != null) {
                Text(
                    text = "$cellCount cells",
                    color = GuardColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Text(
            text = "Resting voltage is the figure that corresponds to state of charge; " +
                "the loaded figure is what the pack is delivering right now.",
            color = GuardColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )

        // Says out loud why a chemistry is missing, rather than leaving the operator to
        // wonder whether the app failed to detect one.
        if (state.batteryConfiguration?.chemistryIsCertain == false) {
            Text(
                text = "Chemistry not claimed: Li-ion and LiPo share the same nominal and " +
                    "full-charge per-cell voltages, so no voltage reading can separate them.",
                color = GuardColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

// --- Cells --------------------------------------------------------------------------

@Composable
private fun CellHealthCard(state: DashboardUiState) {
    val stats = state.cellStats
    val cellCount = state.cellCount

    SectionCard(
        title = "Cell balance",
        trailing = {
            if (cellCount != null) {
                StatusPill(text = "${cellCount}S", dotColor = GuardColors.Accent)
            }
        },
    ) {
        if (state.cellVoltages.isEmpty()) {
            Text(
                text = "This telemetry source reports a pack total but no per-cell voltages, " +
                    "so no cell health can be claimed.",
                color = GuardColors.TextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            return@SectionCard
        }

        CellBalanceChart(
            cellVoltages = state.cellVoltages,
            limitVolts = CELL_IMBALANCE_LIMIT_VOLTS,
            modifier = Modifier
                .fillMaxWidth()
                .height(138.dp),
            // Cell-scoped: this chart is about the cells, so only a cell alert may colour
            // it. A low-charge or RTL alert must leave it neutral.
            barColor = state.levelFor(CELL_RULES).indicatorColor(),
        )

        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "ΔV max−min",
                value = stats?.deltaVolts.formatVolts(3),
                unit = "V",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Weakest cell",
                value = state.weakestCellNumber?.let { "#$it" } ?: "—",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Min",
                value = stats?.minVolts.formatVolts(2),
                unit = "V",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
            Readout(
                label = "Max",
                value = stats?.maxVolts.formatVolts(2),
                unit = "V",
                modifier = Modifier.weight(1f),
                valueFontSize = 20.sp,
            )
        }

        Text(
            text = "Each bar is a cell's offset above the weakest cell in the pack, so the " +
                "distance between the tallest and shortest bar is exactly ΔV. The dashed " +
                "line is the ${CELL_IMBALANCE_LIMIT_VOLTS} V imbalance limit (FR 2.2) — " +
                "bars crossing it means the pack is out of balance.",
            color = GuardColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

// --- Trend --------------------------------------------------------------------------

@Composable
private fun PowerTrendCard(state: DashboardUiState) {
    // These sparklines plot the pack's voltage and current, so they take the rules about
    // what the pack is delivering and nothing else.
    val color = state.levelFor(PACK_VOLTAGE_RULES).indicatorColor()
    val sampleCount = state.history.measuredPackVolts.size

    SectionCard(
        title = "Power trend",
        trailing = { StatusPill(text = "$sampleCount samples", dotColor = GuardColors.Idle) },
    ) {
        TrendRow(
            label = "Pack voltage",
            value = state.totalVoltage.formatVolts(),
            unit = "V",
            values = state.history.measuredPackVolts,
            secondaryValues = state.history.restingPackVolts,
            color = color,
            minimumSpan = 1f,
        )
        TrendRow(
            label = "Current",
            value = state.current.formatAmps(),
            unit = "A",
            values = state.history.amps,
            color = GuardColors.TextSecondary,
            minimumSpan = 6f,
        )
        Text(
            text = "Solid: loaded pack voltage. Dashed: sag-compensated resting estimate. " +
                "The window covers the most recent ${TelemetryHistory.WINDOW} samples.",
            color = GuardColors.TextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun TrendRow(
    label: String,
    value: String,
    unit: String,
    values: List<Float>,
    color: Color,
    minimumSpan: Float,
    secondaryValues: List<Float> = emptyList(),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(98.dp)) {
            Text(
                text = label.uppercase(),
                color = GuardColors.TextMuted,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = GuardColors.TextPrimary,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    color = GuardColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Sparkline(
            values = values,
            color = color,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            secondaryValues = secondaryValues,
            minimumSpan = minimumSpan,
        )
    }
}

// --- Flight safety / position -------------------------------------------------------

@Composable
private fun FlightSafetyCard(state: DashboardUiState) {
    val rtl = state.rtl
    val percentage = state.batteryPercentage
    val percentageValue = percentage?.toDouble()
    val margin = rtl?.marginPercent(percentageValue)

    SectionCard("Flight safety") {
        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Home distance",
                value = state.distanceToHomeMeters.formatDistance(),
                unit = state.distanceToHomeMeters.distanceUnit(),
                modifier = Modifier.weight(1f),
                valueFontSize = 22.sp,
            )
            Readout(
                label = "Time to home",
                value = rtl?.timeToHomeSeconds.formatDurationSeconds(),
                modifier = Modifier.weight(1f),
                valueFontSize = 22.sp,
            )
            Readout(
                label = "Required for RTL",
                value = rtl?.requiredPercent.format(1),
                unit = "%",
                modifier = Modifier.weight(1f),
                valueFontSize = 22.sp,
            )
        }

        ThresholdBar(
            fraction = (percentage ?: 0) / 100f,
            thresholdFraction = ((rtl?.requiredPercent ?: 0.0) / 100.0).toFloat(),
            // The bar plots remaining charge against the RTL requirement, so it answers to
            // the RTL rule alone. A cell-voltage alert colouring this bar would point at
            // the wrong problem: the aircraft would need to fly home sooner or later for a
            // reason this bar has nothing to say about.
            color = state.levelFor(AlertRule.RTL_REQUIRED).indicatorColor(),
        )

        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Charge remaining",
                value = percentage.formatPercent(),
                unit = "%",
                modifier = Modifier.weight(1f),
                valueFontSize = 22.sp,
            )
            Readout(
                label = "Margin above requirement",
                value = margin.format(1),
                unit = "%",
                modifier = Modifier.weight(1f),
                valueFontSize = 22.sp,
            )
        }

        if (rtl == null) {
            Text(
                text = "A dynamic RTL figure needs a distance to home, a return speed and a " +
                    "discharge rate. This source is not reporting all three, so no " +
                    "requirement is claimed rather than an assumed one.",
                color = GuardColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        } else {
            Text(
                text = "Trip ${rtl.tripPercent.format(1)}% at " +
                    "${rtl.percentPerMinute.format(2)}%/min for " +
                    "${rtl.timeToHomeSeconds.formatDurationSeconds()}, plus a " +
                    "${rtl.safetyMarginPercent.format(0)}% reserve.",
                color = GuardColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )

            // Stated in words, in a neutral colour. The coloured version of this is the
            // RTL rule's Critical alert, raised by the alert engine and rendered by the
            // banner — not by this layout.
            if (rtl.isRequired(percentageValue)) {
                Text(
                    text = "Remaining charge is below the level needed to fly home with the " +
                        "reserve intact.",
                    color = GuardColors.TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        SprayInterlockRow(state)
    }
}

/**
 * FR 5.1's interlock, shown as state rather than as a control.
 *
 * There is deliberately no button. The interlock is not something the operator can turn
 * off, and a disabled-looking control would imply it might be. This reports what the
 * system has already done to the pump.
 */
@Composable
private fun SprayInterlockRow(state: DashboardUiState) {
    val color = interlockColor(state.sprayInhibited)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "SPRAY",
                color = GuardColors.TextMuted,
                fontSize = 9.sp,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (state.sprayInhibited) "Inhibited" else "Permitted",
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.sprayingActive) "Pump running" else "Pump idle",
                color = GuardColors.TextMuted,
                fontSize = 10.sp,
            )
        }
        state.sprayInhibitReason?.let { reason ->
            Spacer(Modifier.height(2.dp))
            Text(text = reason, color = GuardColors.TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun PositionCard(state: DashboardUiState) {
    SectionCard(
        title = "Position",
        trailing = {
            StatusPill(
                text = if (state.gpsLocked) "GPS fix" else "No fix",
                dotColor = if (state.gpsLocked) GuardColors.Healthy else GuardColors.Idle,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Latitude",
                value = state.latitude.formatCoordinate(),
                modifier = Modifier.weight(1.2f),
                valueFontSize = 18.sp,
            )
            Readout(
                label = "Longitude",
                value = state.longitude.formatCoordinate(),
                modifier = Modifier.weight(1.2f),
                valueFontSize = 18.sp,
            )
            Readout(
                label = "Altitude",
                value = state.altitudeMeters.format(0),
                unit = "m",
                modifier = Modifier.weight(1f),
                valueFontSize = 18.sp,
            )
            Readout(
                label = "Cruise",
                value = state.cruisingSpeedMps.format(1),
                unit = "m/s",
                modifier = Modifier.weight(1f),
                valueFontSize = 18.sp,
            )
        }
    }
}

// --- Demo controls ------------------------------------------------------------------

@Composable
private fun DemoControlsCard(
    state: DashboardUiState,
    onScenarioSelected: (String) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    columns: Int,
) {
    SectionCard(
        title = "Demo mode",
        trailing = { StatusPill(text = "Simulated", dotColor = GuardColors.Accent) },
    ) {
        state.demoScenarios.chunked(columns).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOptions.forEach { option ->
                    ChoiceChip(
                        label = option.label,
                        selected = option.selected,
                        onClick = { onScenarioSelected(option.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keeps the last row's chips the same width as a full row's.
                repeat(columns - rowOptions.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        state.demoScenarios.firstOrNull { it.selected }?.let { selected ->
            Text(
                text = selected.description,
                color = GuardColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "SIM SPEED",
                color = GuardColors.TextMuted,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.width(12.dp))
            state.speedOptions.forEach { speed ->
                ChoiceChip(
                    label = "${speed.toInt()}×",
                    selected = speed == state.speedMultiplier,
                    onClick = { onSpeedSelected(speed) },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/**
 * States plainly what this build is and is not reading. A safety display that lets an
 * operator believe simulated numbers came off the aircraft would be worse than useless.
 */
@Composable
private fun DataSourceDisclaimer() {
    Text(
        text = "Simulated telemetry generated on-device. No Skydroid GR01 link is connected " +
            "and no aircraft is being monitored. Sag compensation, discharge rate, " +
            "flight-time estimation and the dynamic RTL calculation are live; the alert " +
            "engine and its thresholds are not.",
        color = GuardColors.TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}
