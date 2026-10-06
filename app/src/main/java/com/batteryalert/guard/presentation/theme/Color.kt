package com.batteryalert.guard.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Operator-dashboard palette: near-black background, high contrast, large numerals.
 *
 * Note the split between [Accent] and the semantic colours below it. The accent is
 * deliberately non-semantic — it means "this is a readout", not "this is fine". Green
 * / amber / red are reserved for the alert engine, so that a colour on screen is
 * always a safety claim the alert engine actually made.
 */
object GuardColors {
    val Background = Color(0xFF0A0E13)
    val Card = Color(0xFF131A22)
    val CardRaised = Color(0xFF1A2430)
    val Outline = Color(0xFF243241)
    val Track = Color(0xFF1B2531)

    val TextPrimary = Color(0xFFE8EEF5)
    val TextSecondary = Color(0xFF9FB0C0)
    val TextMuted = Color(0xFF6B7C8C)

    /** Neutral readout accent. Carries no safety meaning. */
    val Accent = Color(0xFF35C8D0)

    // --- Semantic colours: reserved for the alert engine and safety services -------

    val NoticeYellow = Color(0xFFFFD23D)
    val WarningAmber = Color(0xFFFFB020)
    val CellFaultOrange = Color(0xFFFF8A3D)
    val CriticalRed = Color(0xFFFF4D4D)
    val EmergencyRed = Color(0xFFFF2D2D)
    val Healthy = Color(0xFF35D07F)
    val Idle = Color(0xFF4A5A69)
}
