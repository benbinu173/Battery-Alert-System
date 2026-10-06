package com.batteryalert.guard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.batteryalert.guard.presentation.GuardApp
import com.batteryalert.guard.presentation.theme.BatteryAlertTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The Activity hosts Compose and nothing else. Keeping it this thin is the point: no
 * telemetry, safety or storage logic can accumulate here, and the two screens are chosen in
 * [GuardApp] rather than in the manifest.
 *
 * [AndroidEntryPoint] is not optional. `hiltViewModel()` inside [GuardApp] builds its
 * factory from this Activity, and without the annotation Hilt's generated component does
 * not exist, so the very first composition throws.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BatteryAlertTheme {
                GuardApp()
            }
        }
    }
}
