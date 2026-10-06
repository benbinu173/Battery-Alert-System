package com.batteryalert.guard.domain.model

/**
 * Lifecycle of the link to the telemetry source.
 *
 * ERROR exists so a broken link can never be mistaken for a healthy-but-quiet one.
 *
 * [RECONNECTING] splits that "not connected" answer in two, because the operator has two
 * different jobs depending on which one they are looking at. RECONNECTING means the app is
 * already trying — the cable was knocked loose and will most likely come back on its own, so
 * there is nothing to do but keep flying. ERROR means nothing is being tried and nobody is
 * going to fix it but them.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}
