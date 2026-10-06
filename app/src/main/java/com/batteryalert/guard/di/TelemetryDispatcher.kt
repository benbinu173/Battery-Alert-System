package com.batteryalert.guard.di

import javax.inject.Qualifier

/**
 * The dispatcher the serial read loop runs on.
 *
 * Named rather than hardcoded to `Dispatchers.IO` inside `SerialTelemetryDataSource` for one
 * reason: the read loop and its watchdog are a state machine over *time* — connect, go silent,
 * go stale — and that state machine is the part of the app that must never quietly report a
 * healthy link when there is none. Testing it against real time means either a slow test or a
 * flaky one.
 *
 * With this seam a test injects `StandardTestDispatcher` and controls the clock, so "the link
 * went quiet two seconds ago" becomes an assertion rather than a sleep.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TelemetryDispatcher
