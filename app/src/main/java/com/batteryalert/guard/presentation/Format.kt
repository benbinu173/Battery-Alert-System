package com.batteryalert.guard.presentation

import java.util.Locale

/**
 * Display formatting for telemetry readouts.
 *
 * Everything is formatted against [Locale.US] on purpose: a decimal comma in a voltage
 * reading would be a genuine misread, and telemetry is not locale-dependent data.
 *
 * Every helper takes a nullable input and renders a dash for it, so "unknown" is shown
 * as unknown rather than as a confident zero.
 */
private const val UNKNOWN = "—"

fun Double?.format(decimals: Int = 1): String =
    if (this == null || !isFinite()) UNKNOWN else String.format(Locale.US, "%.${decimals}f", this)

fun Double?.formatVolts(decimals: Int = 2): String = format(decimals)

fun Double?.formatAmps(decimals: Int = 1): String = format(decimals)

fun Double?.formatCelsius(decimals: Int = 0): String = format(decimals)

fun Int?.formatPercent(): String = this?.toString() ?: UNKNOWN

/** Metres below 1000, kilometres above it — a 2400 m distance reads better as 2.4 km. */
fun Double?.formatDistance(): String = when {
    this == null || !isFinite() -> UNKNOWN
    this < 1_000.0 -> String.format(Locale.US, "%.0f", this)
    else -> String.format(Locale.US, "%.2f", this / 1_000.0)
}

fun Double?.distanceUnit(): String = if (this != null && isFinite() && this >= 1_000.0) "km" else "m"

fun Double?.formatCoordinate(decimals: Int = 5): String = format(decimals)

/**
 * A duration in minutes, rendered the way an operator says it out loud: "48 min", or
 * "1 h 12 m" once it passes the hour. A decimal hour would be a mental conversion at
 * exactly the moment the operator has no time for one.
 */
fun Double?.formatDuration(): String {
    if (this == null || !isFinite() || this < 0.0) return UNKNOWN

    val wholeMinutes = this.toInt()
    val hours = wholeMinutes / 60
    val minutes = wholeMinutes % 60

    return if (hours == 0) "$wholeMinutes min" else "${hours}h ${minutes}m"
}

/**
 * A duration in seconds, switched to minutes once seconds stop being readable. A 38-second
 * trip home is a useful number; "0 min" is not.
 */
fun Double?.formatDurationSeconds(): String {
    if (this == null || !isFinite() || this < 0.0) return UNKNOWN
    return if (this < 90.0) {
        String.format(Locale.US, "%.0f s", this)
    } else {
        (this / 60.0).formatDuration()
    }
}

/** Signed millivolts, e.g. "+84 mV" — the unit ΔV is actually reasoned about in. */
fun Double?.formatSignedMillivolts(): String {
    if (this == null || !isFinite()) return UNKNOWN
    val millivolts = this * 1_000.0
    val sign = if (millivolts >= 0.0) "+" else "−"
    return String.format(Locale.US, "%s%.0f mV", sign, kotlin.math.abs(millivolts))
}
