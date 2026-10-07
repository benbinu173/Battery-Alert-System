package com.batteryalert.guard.di

import com.batteryalert.guard.data.telemetry.DemoTelemetryController
import com.batteryalert.guard.data.telemetry.LinkHealthSource
import com.batteryalert.guard.data.telemetry.SwitchableTelemetryTransport
import com.batteryalert.guard.data.telemetry.TelemetryDataSource
import com.batteryalert.guard.data.telemetry.TelemetrySourceRouter
import com.batteryalert.guard.data.telemetry.TelemetryTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/**
 * Single place where the app decides what telemetry it is talking to.
 *
 * Nothing in the domain or presentation layers knows the difference between the simulator, a UDP
 * socket and a USB adapter, and that is the property this file exists to hold. Every class that
 * cares — the alert engine, the flight-time estimate, the blackbox sampler — is handed a
 * [TelemetryDataSource] and reads the same flows whichever is in use.
 *
 * ### What changed, and what did not
 *
 * This file used to bind the simulator and carry a list of "four edits" to make for hardware:
 * swap in `SerialTelemetryDataSource`, swap in `NoOpDemoController`, bind a transport, bind the
 * link-health source. All four were compile-time decisions, which meant demonstrating the app and
 * flying it were two different builds, and the one being demonstrated was never the one being
 * flown.
 *
 * There is now one edit to make, and nobody has to make it. [TelemetrySourceRouter] is bound to
 * all three interfaces; it holds the simulator and the wire and forwards to whichever the
 * operator selected on the Link screen. [SwitchableTelemetryTransport] does the same one layer
 * down, between UDP and USB. So the four items above are all still *bindings* — they are just
 * resolved at runtime by the two routers rather than at build time by this file.
 *
 * ### What that does not buy
 *
 * The USB transport is now reachable in a release build rather than a debug-only one, and it is
 * still the one artifact here that has never executed: it needs the adapter and the aircraft.
 * UDP is a different story and deliberately so — `UdpTelemetryTransport` has no Android
 * dependency and is exercised over loopback in `UdpTelemetryTransportTest`. Being bound is not
 * the same as being tested, and only one of these two claims that.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {

    @Binds
    @Singleton
    abstract fun bindTelemetryDataSource(impl: TelemetrySourceRouter): TelemetryDataSource

    // The same singleton as above, seen through three narrower views. `DemoTelemetryController`
    // no longer needs a no-op implementation to answer for a real aircraft: the router answers
    // `isAvailable` from the current mode, so the dashboard's demo controls appear and disappear
    // with it rather than being compiled out.
    @Binds
    @Singleton
    abstract fun bindDemoTelemetryController(impl: TelemetrySourceRouter): DemoTelemetryController

    // Routed rather than stubbed. In demo mode the router forwards the simulator's honest
    // "I have no wire" (LinkHealthSource.NoLinkHealth) instead of this module pre-deciding it,
    // because which of the two answers is correct is now a runtime property.
    @Binds
    @Singleton
    abstract fun bindLinkHealthSource(impl: TelemetrySourceRouter): LinkHealthSource

    // `SerialTelemetryDataSource` takes one transport and holds it for its whole life, so the
    // choice between a socket and a cable has to be made behind the interface. Binding this is
    // what lets a UDP port be configured without the retry loop, the watchdog or the link-health
    // reporting knowing that anything changed.
    @Binds
    @Singleton
    abstract fun bindTelemetryTransport(impl: SwitchableTelemetryTransport): TelemetryTransport

    companion object {

        /**
         * Where `SerialTelemetryDataSource` runs its read loop.
         *
         * IO rather than Default: the loop blocks on the transport and must not compete with
         * the alert engine for the thread that raises the warning. Provided here, rather than
         * hardcoded in the source, so a test can substitute a virtual clock for it — see
         * [TelemetryDispatcher].
         *
         * The UDP transport and the source router share it, which is deliberate: all three are
         * the same kind of work — waiting on a thing outside the app — and giving the socket its
         * own thread would be a fourth place to reason about ordering for no gain.
         */
        @Provides
        @Singleton
        @TelemetryDispatcher
        fun provideTelemetryDispatcher(): CoroutineDispatcher = Dispatchers.IO

        /**
         * The link's view of the current time.
         *
         * Wall-clock milliseconds, because "has anything arrived in the last two seconds" is
         * a question about elapsed real time and a monotonic source would have to be converted
         * to one anyway to be compared with timestamps taken elsewhere.
         */
        @Provides
        @Singleton
        @TelemetryClock
        fun provideTelemetryClock(): () -> Long = System::currentTimeMillis
    }
}
