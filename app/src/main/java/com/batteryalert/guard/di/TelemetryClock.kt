package com.batteryalert.guard.di

import javax.inject.Qualifier

/**
 * The clock the telemetry link measures silence against.
 *
 * `SerialTelemetryDataSource` decides that a link has gone quiet by comparing two readings of
 * the current time, and that decision is the only thing standing between a dropped cable and
 * a dashboard frozen on the last good reading. Testing it against the real clock means a test
 * that sleeps, which is a test that is slow when it passes and flaky when the machine is busy.
 *
 * Injecting the clock makes "the link has been silent for three seconds" an input rather than
 * a wait. See [TelemetryDispatcher] for the same argument about the thread it runs on.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TelemetryClock
