package com.batteryalert.guard.di

import com.batteryalert.guard.data.aircraft.AircraftProfileStore
import com.batteryalert.guard.data.aircraft.PreferencesAircraftProfileStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * What the app knows about the airframe, as opposed to what the airframe reports.
 *
 * Bound through the interface so a test can put a capacity in front of the dashboard without a
 * `Context`, and so the two screens that read it cannot reach past it to the preferences file.
 * The implementation is a singleton because it holds the only in-process copy of the value:
 * two instances over one file would each keep their own `StateFlow` and one of them would go
 * stale the moment the other was written to.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AircraftModule {

    @Binds
    @Singleton
    abstract fun bindAircraftProfileStore(
        impl: PreferencesAircraftProfileStore,
    ): AircraftProfileStore
}
