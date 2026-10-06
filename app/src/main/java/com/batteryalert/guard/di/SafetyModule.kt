package com.batteryalert.guard.di

import com.batteryalert.guard.safety.AlertAnnouncer
import com.batteryalert.guard.safety.AndroidAlertAnnouncer
import com.batteryalert.guard.safety.AndroidHapticChannel
import com.batteryalert.guard.safety.HapticChannel
import com.batteryalert.guard.safety.NoOpSprayController
import com.batteryalert.guard.safety.SprayController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The second and last place the app touches hardware.
 *
 * [TelemetryModule] chooses what the app is listening to; this chooses what it acts
 * through. Both are one-line swaps, and nothing in the domain or presentation layers knows
 * which implementation is bound.
 *
 * The spray binding is the honest one: [NoOpSprayController] drives nothing, because the
 * requirements specify the interlock behaviour without ever defining a pump protocol to
 * drive. The interface is the part that is real.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SafetyModule {

    @Binds
    @Singleton
    abstract fun bindAlertAnnouncer(impl: AndroidAlertAnnouncer): AlertAnnouncer

    @Binds
    @Singleton
    abstract fun bindHapticChannel(impl: AndroidHapticChannel): HapticChannel

    @Binds
    @Singleton
    abstract fun bindSprayController(impl: NoOpSprayController): SprayController
}
