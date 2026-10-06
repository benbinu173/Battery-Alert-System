package com.batteryalert.guard.presentation.aircraft

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batteryalert.guard.data.aircraft.AircraftProfileStore
import com.batteryalert.guard.data.telemetry.TelemetryDataSource
import com.batteryalert.guard.domain.usecase.FlightTime
import com.batteryalert.guard.domain.usecase.PackCapacity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The pack-capacity configuration point, and the arithmetic it unlocks.
 *
 * ### Why this screen exists
 *
 * FR 2.3's remaining flight time and FR 3.1's dynamic RTL both need the pack's charge in mAh.
 * The autopilot reports a percentage, a current, and a counter of charge used since boot — none
 * of which is that number, and the third of which is a trap: it resets to zero at power-up, so
 * a pack charged to full and flown again would read as nearly empty. The capacity has to be
 * configured, so there has to be a place to configure it.
 *
 * ### Why the entry box and the stored value are separate
 *
 * Editing writes to a local flow and only the save action touches the store. The alternative —
 * reconfiguring on every keystroke — would put "1", then "16", then "160" in front of the
 * dashboard's flight-time estimate while the operator was still typing. See [AircraftUiState].
 */
@HiltViewModel
class AircraftViewModel @Inject constructor(
    private val aircraftProfileStore: AircraftProfileStore,
    telemetryDataSource: TelemetryDataSource,
) : ViewModel() {

    /**
     * What the operator has typed, or null while the box is merely showing what is stored.
     *
     * The null case is what makes the screen open on the configured value without a seeding
     * effect, and what makes the box fall back into line the moment a save is accepted.
     */
    private val typedInput = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AircraftUiState> = combine(
        aircraftProfileStore.packCapacityMah,
        telemetryDataSource.telemetryFlow(),
        telemetryDataSource.connectionStateFlow(),
        typedInput,
        // The connection state is a direct input so the preview can say *why* it is showing
        // dashes — no link, versus a link with no capacity configured. Those are two different
        // jobs for the operator: plug something in, versus type a number.
    ) { configured, battery, connection, typed ->
        val stored = configured?.let { AircraftUiState.formatCapacity(it) }.orEmpty()
        val input = typed ?: stored
        val error = typed?.let { validate(it) }

        // Deliberately recomputed through the same pure use cases the dashboard uses rather
        // than read from DashboardUiState. A second reader of the dashboard's state would mean
        // the two screens could disagree about the same pack, and this screen's whole job is
        // to be the one the operator checks the other one against.
        val remaining = PackCapacity.remainingCapacityMah(
            reportedRemainingMah = battery.remainingCapacityMah,
            configuredCapacityMah = configured,
            batteryPercentage = battery.batteryPercentage,
        )
        val rate = FlightTime.dischargeRateMahPerMin(battery.current)

        AircraftUiState(
            packCapacityMah = configured,
            capacityInput = input,
            capacityError = error,
            connectionState = connection,
            batteryPercentage = battery.batteryPercentage,
            remainingCapacityMah = remaining,
            dischargeRateMahPerMin = rate,
            estimatedFlightMinutes = FlightTime.estimatedMinutes(
                remainingCapacityMah = remaining,
                dischargeRateMahPerMin = rate,
            ),
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = AircraftUiState(),
        )

    fun onCapacityInputChanged(text: String) {
        typedInput.value = text
    }

    /**
     * Writes what is in the box, or clears the configuration when the box is empty.
     *
     * Refuses rather than clamps an implausible entry. Clamping would be the friendlier-looking
     * behaviour and the more dangerous one: an operator who types 160000 and is silently given
     * 60000 would fly on a flight-time estimate derived from a pack that is not in the aircraft.
     */
    fun onSaveCapacity() {
        val input = uiState.value.capacityInput
        if (validate(input) != null) return

        val cleaned = clean(input)
        aircraftProfileStore.setPackCapacityMah(cleaned.toDoubleOrNull())

        // Back to following the store, so the box now shows exactly what was written — which
        // is also what turns the confirmation line on, via AircraftUiState.showsSavedValue.
        typedInput.value = null
    }

    private fun validate(input: String): String? {
        val cleaned = clean(input)
        // An empty box is not an error; it is the operator asking for the configuration to be
        // removed, and the confirmation is that the dashboard's flight time goes back to a dash.
        if (cleaned.isEmpty()) return null

        val value = cleaned.toDoubleOrNull() ?: return "Enter a capacity in mAh."
        if (!PackCapacity.isPlausible(value)) {
            return "Between ${PackCapacity.MIN_PLAUSIBLE_CAPACITY_MAH.toLong()} and " +
                "${PackCapacity.MAX_PLAUSIBLE_CAPACITY_MAH.toLong()} mAh."
        }
        return null
    }

    /**
     * Strips what a person types and a number does not need.
     *
     * Separators only. A stricter filter that kept digits alone would turn a stray "16o00" into
     * a perfectly valid 1600 and save it, which is precisely the silent-correction behaviour
     * [onSaveCapacity] refuses to do.
     */
    private fun clean(input: String): String =
        input.trim().replace(",", "").replace(" ", "")
}
