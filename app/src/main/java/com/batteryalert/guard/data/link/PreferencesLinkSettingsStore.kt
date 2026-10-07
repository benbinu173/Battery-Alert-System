package com.batteryalert.guard.data.link

import android.content.Context
import android.content.SharedPreferences
import com.batteryalert.guard.domain.usecase.UdpPort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The link settings, kept in preferences so the operator sets them once rather than once per
 * launch.
 *
 * ### Why preferences and not the database
 *
 * Same argument as the aircraft profile's: this is two small values, written when somebody
 * changes their mind and read before anything else starts — the telemetry source has to know
 * which mode it is in before its first connect. The Room database next door is a rolling
 * diagnostic buffer that is deliberately wiped on schema changes, and a schema change silently
 * moving the app from UDP to the simulator would be a confusing way to spend an afternoon.
 *
 * ### Why the mode is stored by name
 *
 * `LinkMode.UDP.name`, not its ordinal. An ordinal is a position in a list, so inserting a mode
 * in the middle would silently turn every stored UDP into USB — the same reasoning `GuardApp`
 * gives for storing its destination by name. An unrecognised name reads back as [LinkMode.DEMO],
 * which is the safe direction to fail: the simulator needs no aircraft, and a wrong mode is a
 * visible empty dashboard rather than a socket aimed at nothing.
 */
@Singleton
class PreferencesLinkSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) : LinkSettingsStore {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readStoredSettings())

    override val settings: StateFlow<LinkSettings> = _settings.asStateFlow()

    override fun setMode(mode: LinkMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(mode = mode)
    }

    override fun setUdpPort(port: Int) {
        require(UdpPort.isBindable(port)) { "$port is not a port this app can bind." }

        preferences.edit().putInt(KEY_UDP_PORT, port).apply()
        _settings.value = _settings.value.copy(udpPort = port)
    }

    /**
     * Reads what was stored, falling back per field rather than wholesale.
     *
     * One bad value does not discard the other. An unreadable port with a valid USB mode set
     * should still come up on USB — resetting both because one was corrupt would be a second
     * failure invented by the recovery code.
     */
    private fun readStoredSettings(): LinkSettings {
        val mode = preferences.getString(KEY_MODE, null)
            ?.let { stored -> LinkMode.entries.firstOrNull { it.name == stored } }
            ?: LinkMode.DEMO

        val port = preferences.getInt(KEY_UDP_PORT, UdpPort.DEFAULT_PORT)
            .takeIf { UdpPort.isBindable(it) }
            ?: UdpPort.DEFAULT_PORT

        return LinkSettings(mode = mode, udpPort = port)
    }

    private companion object {
        const val PREFERENCES_NAME = "link_settings"
        const val KEY_MODE = "mode"
        const val KEY_UDP_PORT = "udp_port"
    }
}
