package com.batteryalert.guard.di

import com.batteryalert.guard.data.link.LinkSettingsStore
import com.batteryalert.guard.data.link.PreferencesLinkSettingsStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Which source the app reads telemetry from, and where that choice is kept.
 *
 * Its own module rather than a corner of [TelemetryModule] because it is a different kind of
 * statement. That one binds *how* telemetry flows — a source, a transport, a dispatcher — and
 * every binding in it is about mechanism. This one binds a preference: something the operator
 * set, that survives the app being closed, and that the rest of the graph reads as an input. It
 * is the same split as `AircraftProfileStore` living outside the telemetry graph, and it is what
 * keeps `TelemetryModule` from growing a settings dependency the day there is a second setting.
 *
 * `@Singleton` because the store is read on one screen and watched by the source router, and two
 * instances would mean the screen writing to a `StateFlow` nobody is collecting — the link would
 * keep running on the old settings and the screen would happily show the new ones.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LinkModule {

    @Binds
    @Singleton
    abstract fun bindLinkSettingsStore(impl: PreferencesLinkSettingsStore): LinkSettingsStore
}
