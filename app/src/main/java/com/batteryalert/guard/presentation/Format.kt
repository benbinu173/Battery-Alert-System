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
