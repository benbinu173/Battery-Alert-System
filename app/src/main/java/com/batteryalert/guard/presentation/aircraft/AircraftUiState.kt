package com.batteryalert.guard.presentation.aircraft

import com.batteryalert.guard.domain.model.ConnectionState

/**
 * Everything the aircraft screen renders.
 *
 * The split between [packCapacityMah] and [capacityInput] is the design of this screen. One is
 * what the app is *currently using* — it came from storage, it survives a restart, and the
 * dashboard is computing flight times from it right now. The other is what is *typed in the
 * box*, which is nothing at all until it is saved. Collapsing them into one field would mean a
 * half-typed "16" was already the pack size, and the dashboard would spend the next keystrokes
 * estimating flight time on a 16 mAh battery.
 */
data class AircraftUiState(
    /** The capacity in force. Null when the operator has not configured one. */
    val packCapacityMah: Double? = null,

    /** What is in the entry box, whether or not it is valid and whether or not it is saved. */
    val capacityInput: String = "",

    /** Why the entry box cannot be saved. Null when there is nothing wrong with it. */
    val capacityError: String? = null,

    // --- Live telemetry, read only to show what the configuration is doing ----------------

    /** Why the preview below may be showing dashes; the operator's next move depends on it. */
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val batteryPercentage: Int? = null,
    /** Charge left, derived from the configured capacity and the state of charge above. */
    val remainingCapacityMah: Double? = null,
    val dischargeRateMahPerMin: Double? = null,
    val estimatedFlightMinutes: Double? = null,
) {
    /** True when the app can compute a flight time at all — that is, when a capacity is set. */
    val isConfigured: Boolean get() = packCapacityMah != null

    /**
     * The box as it would read if it showed what is stored.
     *
     * Blank for "nothing configured", which is also what an operator clears the box to, so
     * emptying the box on an unconfigured aircraft is correctly not an edit.
     */
    private val storedInput: String get() = packCapacityMah?.let { formatCapacity(it) }.orEmpty()

    /** True when the box holds something the store does not. Drives the Save button. */
    val hasPendingEdit: Boolean get() = capacityError == null && capacityInput != storedInput

    /**
     * True when the box holds exactly what is stored and there is something stored.
     *
     * Derived rather than latched from the save action, so it cannot claim a value was saved
     * when a later write put a different one in — the confirmation is a statement about the
     * store, not a memory of a button press.
     */
    val showsSavedValue: Boolean
        get() = capacityError == null && packCapacityMah != null && capacityInput == storedInput

    companion object {
        /**
         * A capacity as text, the inverse of the parsing in [AircraftViewModel].
         *
         * The two have to round-trip or the screen would open offering to save the value it is
         * already showing: parsing "16000" gives 16000.0, and formatting that as "16000.0"
         * would make every visit to this screen look like an unsaved edit.
         */
        fun formatCapacity(capacityMah: Double): String =
            if (capacityMah % 1.0 == 0.0) capacityMah.toLong().toString() else capacityMah.toString()
    }
}
