package com.batteryalert.guard.domain.model

/**
 * Lifecycle of the link to the telemetry source.
 *
 * ERROR exists so a broken link can never be mistaken for a healthy-but-quiet one.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
