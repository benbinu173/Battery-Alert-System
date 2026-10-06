package com.batteryalert.guard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.batteryalert.guard.presentation.dashboard.DashboardScreen
import com.batteryalert.guard.presentation.theme.BatteryAlertTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app has exactly one screen. Keeping the Activity this thin is the point: it hosts
 * Compose and nothing else, so no telemetry or safety logic can accumulate here.
 *
 * [AndroidEntryPoint] is not optional. `hiltViewModel()` inside `DashboardScreen` builds
 * its factory from this Activity, and without the annotation Hilt's generated component
 * does not exist, so the very first composition throws.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BatteryAlertTheme {
                DashboardScreen()
            }
        }
    }
}
