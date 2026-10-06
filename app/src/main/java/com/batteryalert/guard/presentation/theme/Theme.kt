package com.batteryalert.guard.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GuardColorScheme = darkColorScheme(
    primary = GuardColors.Accent,
    onPrimary = GuardColors.Background,
    background = GuardColors.Background,
    onBackground = GuardColors.TextPrimary,
    surface = GuardColors.Card,
    onSurface = GuardColors.TextPrimary,
    surfaceVariant = GuardColors.CardRaised,
    onSurfaceVariant = GuardColors.TextSecondary,
    outline = GuardColors.Outline,
)

/**
 * The app is dark-only by design: it is read on a controller in daylight, and a light
 * theme would wash out the alert colours that matter most.
 */
@Composable
fun BatteryAlertTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GuardColorScheme,
        content = content,
    )
}
