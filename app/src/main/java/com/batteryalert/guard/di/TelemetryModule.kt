package com.batteryalert.guard.di

import com.batteryalert.guard.data.telemetry.DemoTelemetryController
import com.batteryalert.guard.data.telemetry.LinkHealthSource
import com.batteryalert.guard.data.telemetry.MockTelemetryDataSource
import com.batteryalert.guard.data.telemetry.TelemetryDataSource
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
 * Nothing in the domain or presentation layers knows the difference between the simulator and a
 * real aircraft, and that is the property this file exists to hold. Every class that cares —
 * the alert engine, the flight-time estimate, the blackbox sampler — is handed a
 * [TelemetryDataSource] and reads the same flows either way.
 *
 * What is bound below is the simulator, and that is a schedule decision rather than an
 * unfinished one: Module 27 asks not to spend the time on hardware before the core works, and
 * every part of the core is exercised by tests against synthetic frames. The hardware path
 * exists in full — `UsbSerialTransport` opens the adapter, `SerialTelemetryDataSource` frames
 * and decodes what comes off it — and is unverified, because verifying it needs the aircraft.
 *
 * Switching to it is four edits, all in this file:
 *
 * 1. bind `SerialTelemetryDataSource` to [TelemetryDataSource];
 * 2. bind `NoOpDemoController` to [DemoTelemetryController] — a real aircraft has no scenarios,
 *    and the dashboard decides whether to draw the demo controls from `isAvailable`;
 * 3. bind `UsbSerialTransport` to `TelemetryTransport`, replacing nothing, since the simulator
 *    has no transport at all;
 * 4. bind `SerialTelemetryDataSource` as the [LinkHealthSource], replacing the stub below, so
 *    the diagnostics screen reports a real link instead of "not measured".
 *
 * It is worth being clear about what those four edits do *not* buy. They make the app talk to
 * an adapter; they do not make it work on the aircraft. Nothing below has been run against a
 * real G20, so the honest expectation is that the first attempt needs debugging, and the
 * README says so in as many words.
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

    companion object {

        /**
         * Where `SerialTelemetryDataSource` runs its read loop.
         *
         * IO rather than Default: the loop blocks on the transport and must not compete with
         * the alert engine for the thread that raises the warning. Provided here, rather than
         * hardcoded in the source, so a test can substitute a virtual clock for it — see
         * [TelemetryDispatcher].
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

        /**
         * What the diagnostics screen gets when it asks how the wire is doing.
         *
         * The stub, because the bound source is the simulator — it has no wire, and inventing
         * a report full of zeroes for it would read as a link that is healthy and quiet.
         * `SerialTelemetryDataSource` implements [LinkHealthSource] too, so on real hardware
         * this becomes a one-line swap and nothing downstream changes.
         */
        @Provides
        @Singleton
        fun provideLinkHealthSource(): LinkHealthSource = LinkHealthSource.NoLinkHealth
    }
}
