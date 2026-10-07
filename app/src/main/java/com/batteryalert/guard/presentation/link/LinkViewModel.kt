package com.batteryalert.guard.presentation.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batteryalert.guard.data.link.LinkMode
import com.batteryalert.guard.data.link.LinkSettingsStore
import com.batteryalert.guard.data.telemetry.TelemetryDataSource
import com.batteryalert.guard.data.telemetry.TelemetryTransport
import com.batteryalert.guard.domain.usecase.UdpPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Writes the link settings, and reports what the link is doing about them.
 *
 * ### What this does not do
 *
 * It never calls `connect()` or `disconnect()`. That is the whole design of the change: the
 * screen states what the operator wants, the store records it, and `TelemetrySourceRouter`
 * watches the store and restarts the link. A screen that also drove the lifecycle would be a
 * second owner of it, and the two would disagree the first time the link was restarted for a
 * reason this screen did not cause — a retry, a dropped socket, an adapter unplugged.
 *
 * ### Why the port has a separate typed field
 *
 * The store is what the socket is bound to. Writing to it on every keystroke would put a bound
 * port behind each of "1", "14", "145" — and each of those is a live socket rebind, one of them
 * to a privileged port the app cannot have. So editing writes to [typedInput] and only the save
 * action touches the store. See [LinkUiState].
 *
 * The mode is the exception and is written immediately. It is three discrete choices with no
 * intermediate states, so there is nothing to protect the operator from halfway through.
 */
@HiltViewModel
class LinkViewModel @Inject constructor(
    private val settingsStore: LinkSettingsStore,
    telemetryDataSource: TelemetryDataSource,
    telemetryTransport: TelemetryTransport,
) : ViewModel() {

    /**
     * What the operator has typed, or null while the box is merely showing what is stored.
     *
     * The null case is what makes the screen open on the configured port without a seeding
     * effect, and what makes the box fall back into line the moment a save is accepted.
     */
    private val typedInput = MutableStateFlow<String?>(null)

    val uiState: StateFlow<LinkUiState> = combine(
        settingsStore.settings,
        telemetryDataSource.connectionStateFlow(),
        typedInput,
    ) { settings, connection, typed ->
        val stored = settings.udpPort.toString()

        LinkUiState(
            mode = settings.mode,
            udpPort = settings.udpPort,
            portInput = typed ?: stored,
            // Only an edit can be wrong. The stored port came through the same rule on the way
            // in, so validating it again would be this screen reporting a fault in a value the
            // app chose — and a screen that shows an error before the operator has touched
            // anything teaches them to ignore errors.
            portError = typed?.let { validate(it) },
            connectionState = connection,
            // Re-read on every emission rather than captured once when the ViewModel was built,
            // which is what stops the card showing a stale name for the life of the screen.
            //
            // Note *when* the re-read actually happens, because it is not the instant the socket
            // binds. It is driven by `connectionStateFlow()` above, so the name refreshes when
            // the state moves — and between a successful bind and the first state change there
            // is a window of up to the watchdog's two seconds where the pill says "Connecting"
            // and the transport still reports "not bound". Both of those are pre-commitment
            // words, so the pair is never actually misleading: by the time the state reads
            // "Link up" or "Link error", the name beside it is accurate.
            //
            // The tidy fix is a name flow on `TelemetryTransport`. It is not worth it here —
            // it would add a member to the one class in this project that cannot be tested
            // without hardware, to shorten a window in which nothing displayed is wrong.
            transportName = telemetryTransport.name,
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = LinkUiState(),
        )

    /** Immediate, and deliberately not gated behind the Save button. */
    fun onModeSelected(mode: LinkMode) {
        settingsStore.setMode(mode)
    }

    fun onPortInputChanged(text: String) {
        typedInput.value = text
    }

    fun onSavePort() {
        val input = uiState.value.portInput
        if (validate(input) != null) return

        settingsStore.setUdpPort(clean(input).toInt())

        // Back to following the store, so the box now shows exactly what was written — which is
        // also what turns the confirmation line on, via LinkUiState.showsSavedValue.
        typedInput.value = null
    }

    /**
     * Refuses rather than clamps, for the same reason the capacity field does — and here it
     * matters more. A silently clamped 999 would leave the socket listening on a port the
     * operator did not choose, while the drone sends to the one they did.
     */
    private fun validate(input: String): String? {
        val cleaned = clean(input)
        if (cleaned.isEmpty()) return "Enter the port the telemetry is being sent to."

        val port = cleaned.toIntOrNull() ?: return "A port is a whole number."

        if (!UdpPort.isBindable(port)) {
            return "Between ${UdpPort.MIN_PORT} and ${UdpPort.MAX_PORT}. Lower ports need " +
                "root, which an app does not have."
        }
        return null
    }

    /**
     * Strips what a person types and a number does not need.
     *
     * Separators only, matching the capacity field. A stricter filter keeping digits alone
     * would turn a stray "145O0" into a perfectly valid 1450 and bind it, which is the silent
     * correction [onSavePort] exists to refuse.
     */
    private fun clean(input: String): String =
        input.trim().replace(",", "").replace(" ", "")
}
