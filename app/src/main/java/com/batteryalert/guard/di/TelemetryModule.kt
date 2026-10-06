package com.batteryalert.guard.di

import com.batteryalert.guard.data.telemetry.DemoTelemetryController
import com.batteryalert.guard.data.telemetry.MockTelemetryDataSource
import com.batteryalert.guard.data.telemetry.TelemetryDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Single place where the app decides what telemetry it is talking to.
 *
 * Moving to real hardware is a one-line change here: swap the implementation bound to
 * [TelemetryDataSource]. Nothing in the domain or presentation layers has to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {

    @Binds
    @Singleton
    abstract fun bindTelemetryDataSource(impl: MockTelemetryDataSource): TelemetryDataSource

    // Same singleton instance as above, exposed through the narrower demo-only view.
    // When a serial source replaces the mock, this binding is replaced with a no-op
    // implementation reporting isAvailable = false.
    @Binds
    @Singleton
    abstract fun bindDemoTelemetryController(impl: MockTelemetryDataSource): DemoTelemetryController
}
