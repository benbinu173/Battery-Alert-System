package com.batteryalert.guard.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batteryalert.guard.domain.model.ConnectionState
import com.batteryalert.guard.presentation.components.ChoiceChip
import com.batteryalert.guard.presentation.components.LevelBar
import com.batteryalert.guard.presentation.components.Readout
import com.batteryalert.guard.presentation.components.SectionCard
import com.batteryalert.guard.presentation.components.StatusPill
import com.batteryalert.guard.presentation.distanceUnit
import com.batteryalert.guard.presentation.format
import com.batteryalert.guard.presentation.formatAmps
import com.batteryalert.guard.presentation.formatCelsius
import com.batteryalert.guard.presentation.formatCoordinate
import com.batteryalert.guard.presentation.formatDistance
import com.batteryalert.guard.presentation.formatPercent
import com.batteryalert.guard.presentation.formatVolts
import com.batteryalert.guard.presentation.theme.GuardColors

private const val CELL_BAR_MIN_VOLTS = 3.0
private const val CELL_BAR_MAX_VOLTS = 4.25
private const val SCENARIO_CHIPS_PER_ROW = 3

/**
 * The single operator screen.
 *
 * Everything shown here comes from [DashboardUiState]. The screen never inspects a
 * threshold, never parses anything, and never talks to a telemetry source.
 */
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DashboardContent(
        state = state,
        onScenarioSelected = viewModel::onScenarioSelected,
        onSpeedSelected = viewModel::onSpeedSelected,
    )
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onScenarioSelected: (String) -> Unit,
    onSpeedSelected: (Double) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardColors.Background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar(state)
        BatteryCard(state)
        CellHealthCard(state)
        FlightSafetyCard(state)
        PositionCard(state)
        if (state.demoMode) {
            DemoControlsCard(state, onScenarioSelected, onSpeedSelected)
        }
        DataSourceDisclaimer()
    }
}

@Composable
private fun TopBar(state: DashboardUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "BATTERY GUARD",
            color = GuardColors.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.5.sp,
        )
        Spacer(Modifier.width(10.dp))
        if (state.demoMode) {
            StatusPill(text = "Demo", dotColor = GuardColors.Accent)
        }
        Spacer(Modifier.weight(1f))
        ConnectionPill(state.connectionState)
    }
}

@Composable
private fun ConnectionPill(connectionState: ConnectionState) {
    val (label, color) = when (connectionState) {
        ConnectionState.CONNECTED -> "Link up" to GuardColors.Healthy
        ConnectionState.CONNECTING -> "Connecting" to GuardColors.NoticeYellow
        ConnectionState.DISCONNECTED -> "No link" to GuardColors.Idle
        ConnectionState.ERROR -> "Link error" to GuardColors.CriticalRed
    }
    StatusPill(text = label, dotColor = color)
}

@Composable
private fun BatteryCard(state: DashboardUiState) {
    SectionCard("Battery") {
        Row(verticalAlignment = Alignment.Bottom) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = state.batteryPercentage.formatPercent(),
                    color = GuardColors.Accent,
                    fontSize = 58.sp,
                    lineHeight = 58.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "%",
                    color = GuardColors.Accent,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Readout("Voltage", state.totalVoltage.formatVolts(), "V")
                Readout("Current", state.current.formatAmps(), "A")
                Readout("Temp", state.temperature.formatCelsius(), "°C")
            }
        }

        LevelBar(
            fraction = (state.batteryPercentage ?: 0) / 100f,
            color = GuardColors.Accent,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Readout(
                label = "Remaining",
                value = state.remainingCapacityMah.format(0),
                unit = "mAh",
                valueFontSize = 18.sp,
            )
            Readout(
                label = "Discharge",
                value = state.dischargeRateMahPerMin.format(0),
                unit = "mAh/min",
                valueFontSize = 18.sp,
            )
            Readout(
                label = "Est. flight time",
                value = state.estimatedFlightMinutes.format(0),
                unit = "min",
                valueFontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun CellHealthCard(state: DashboardUiState) {
    val stats = state.cellStats
    SectionCard(title = "Cell Health · ${state.cellVoltages.size}S") {
        if (state.cellVoltages.isEmpty()) {
            Text(
                text = "This telemetry source is not reporting per-cell voltages.",
                color = GuardColors.TextMuted,
                fontSize = 13.sp,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(78.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.cellVoltages.forEachIndexed { index, volts ->
                    CellBar(
                        volts = volts,
                        isWeakest = stats != null && volts <= stats.minVolts,
                        cellNumber = index + 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Readout("Min", stats?.minVolts.formatVolts(), "V", valueFontSize = 18.sp)
                Readout("Max", stats?.maxVolts.formatVolts(), "V", valueFontSize = 18.sp)
                Readout("ΔV max-min", stats?.deltaVolts.formatVolts(3), "V", valueFontSize = 18.sp)
                Readout("Average", stats?.averageVolts.formatVolts(2), "V", valueFontSize = 18.sp)
            }
        }
    }
}

/**
 * One cell of the pack. The weakest cell is drawn at full opacity purely to draw the
 * eye to it — the emphasis carries no safety meaning, so it stays in the neutral
 * accent colour until the alert engine has something to say.
 */
@Composable
private fun CellBar(
    volts: Double,
    isWeakest: Boolean,
    cellNumber: Int,
    modifier: Modifier = Modifier,
) {
    val fraction = ((volts - CELL_BAR_MIN_VOLTS) / (CELL_BAR_MAX_VOLTS - CELL_BAR_MIN_VOLTS))
        .coerceIn(0.04, 1.0)
        .toFloat()

    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (isWeakest) GuardColors.Accent else GuardColors.Accent.copy(alpha = 0.5f),
                    ),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = cellNumber.toString(),
            color = GuardColors.TextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun FlightSafetyCard(state: DashboardUiState) {
    val percentage = state.batteryPercentage
    val required = state.requiredRtlBattery
    val margin = if (percentage != null && required != null) percentage - required else null

    SectionCard("Flight Safety") {
        Row(Modifier.fillMaxWidth()) {
            Readout(
                label = "Home distance",
                value = state.distanceToHomeMeters.formatDistance(),
                unit = state.distanceToHomeMeters.distanceUnit(),
                valueFontSize = 22.sp,
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "Required for RTL",
                value = required.format(0),
                unit = "%",
                valueFontSize = 22.sp,
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "Remaining",
                value = percentage.formatPercent(),
                unit = "%",
                valueFontSize = 22.sp,
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "Margin",
                value = margin.format(0),
                unit = "%",
                valueFontSize = 22.sp,
                modifier = Modifier.weight(1f),
            )
        }

        if (required == null) {
            Text(
                text = "Dynamic RTL is not yet wired up, so no required-battery figure is claimed here.",
                color = GuardColors.TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
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
                valueFontSize = 20.sp,
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "Longitude",
                value = state.longitude.formatCoordinate(),
                valueFontSize = 20.sp,
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "Altitude",
                value = state.altitudeMeters.format(0),
                unit = "m",
                valueFontSize = 20.sp,
                modifier = Modifier.weight(1f),
            )
            Readout(
                label = "Cruise speed",
                value = state.cruisingSpeedMps.format(1),
                unit = "m/s",
                valueFontSize = 20.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DemoControlsCard(
    state: DashboardUiState,
    onScenarioSelected: (String) -> Unit,
    onSpeedSelected: (Double) -> Unit,
) {
    SectionCard(
        title = "Demo Mode",
        trailing = { StatusPill(text = "Simulated", dotColor = GuardColors.Accent) },
    ) {
        state.demoScenarios.chunked(SCENARIO_CHIPS_PER_ROW).forEach { rowOptions ->
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
                repeat(SCENARIO_CHIPS_PER_ROW - rowOptions.size) {
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
            "and no aircraft is being monitored. Alert thresholds and the dynamic RTL " +
            "engine are not active yet.",
        color = GuardColors.TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}
