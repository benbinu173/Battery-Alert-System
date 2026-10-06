package com.batteryalert.guard.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.batteryalert.guard.presentation.aircraft.AircraftScreen
import com.batteryalert.guard.presentation.dashboard.DashboardScreen
import com.batteryalert.guard.presentation.dashboard.DashboardViewModel
import com.batteryalert.guard.presentation.diagnostics.DiagnosticsScreen

/**
 * The app's three destinations, and the one place that decides which is showing.
 *
 * ### Why the dashboard's state is collected here rather than inside the dashboard
 *
 * This is the load-bearing line of the file. `collectAsStateWithLifecycle` suspends the
 * upstream flow when there is no collector, and the telemetry pipeline is
 * `SharingStarted.WhileSubscribed` — so a dashboard that collected its own state would stop
 * the whole pipeline the moment the operator opened the flight recorder or the aircraft
 * screen. That would do two wrong things at once: leave a hole in the blackbox for exactly as
 * long as someone was reading it, and silence an alert raised while another screen was up.
 *
 * Collecting above the switch keeps the pipeline running whenever the app is on screen, and
 * still stops it when the app is not — because `collectAsStateWithLifecycle` is lifecycle
 * aware, not merely composition aware. The behaviour that survives is the one that was
 * always meant: the app is silent when nobody is looking at it.
 *
 * An enum and a `when` rather than a navigation graph. No destination takes an argument, and a
 * graph would be a dependency and a back-stack abstraction bought for nothing. It is also the
 * seam where a real graph goes the day either of those stops being true.
 */
@Composable
fun GuardApp() {
    val dashboardViewModel: DashboardViewModel = hiltViewModel()
    val dashboardState by dashboardViewModel.uiState.collectAsStateWithLifecycle()

    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }

    when (destination) {
        Destination.RECORDER -> DiagnosticsScreen(
            onBack = { destination = Destination.DASHBOARD },
        )

        Destination.AIRCRAFT -> AircraftScreen(
            onBack = { destination = Destination.DASHBOARD },
        )

        Destination.DASHBOARD -> DashboardScreen(
            state = dashboardState,
            onScenarioSelected = dashboardViewModel::onScenarioSelected,
            onSpeedSelected = dashboardViewModel::onSpeedSelected,
            onOpenRecorder = { destination = Destination.RECORDER },
            onOpenAircraft = { destination = Destination.AIRCRAFT },
        )
    }
}

/**
 * Where the app is looking.
 *
 * An enum survives process death through its *name*, not its ordinal — which is the property
 * that matters here, because inserting a destination in the middle of this list is a change
 * with no meaning and must not reopen a different screen on restore.
 */
private enum class Destination { DASHBOARD, RECORDER, AIRCRAFT }
