package com.batteryalert.guard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.batteryalert.guard.presentation.dashboard.DashboardScreen
import com.batteryalert.guard.presentation.theme.BatteryAlertTheme

/**
 * The app has exactly one screen. Keeping the Activity this thin is the point: it hosts
 * Compose and nothing else, so no telemetry or safety logic can accumulate here.
 */
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
