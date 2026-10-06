package com.batteryalert.guard.data.aircraft

import android.content.Context
import android.content.SharedPreferences
import com.batteryalert.guard.domain.usecase.PackCapacity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The aircraft profile, kept in preferences so it survives the app being closed.
 *
 * ### Why preferences and not the database
 *
 * It is one number, it is written once per airframe change, and it has to be readable before
 * anything else starts — the dashboard's first frame needs it. The Room database next door is a
 * rolling diagnostic buffer that is deliberately wiped on every schema change; hanging the
 * aircraft's identity off that would mean a schema change silently changes which pack the app
 * thinks it is flying.
 *
 * ### Why a string on disk
 *
 * Read back through [PackCapacity.isPlausible], exactly like a typed entry. A float would be
 * tidier and would also mean the rule that decides what counts as a real pack gets written
 * twice — once where the operator types it and once where the file is read — and those two
 * copies would eventually disagree. Keeping it a string makes the file a second *entry point*
 * into that one rule rather than a second copy of it, which matters because the file can also
 * be edited by hand on a rooted controller or left behind by an older build.
 */
@Singleton
class PreferencesAircraftProfileStore @Inject constructor(
    @ApplicationContext context: Context,
) : AircraftProfileStore {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _packCapacityMah = MutableStateFlow(readStoredCapacity())

    override val packCapacityMah: StateFlow<Double?> = _packCapacityMah.asStateFlow()

    override fun setPackCapacityMah(capacityMah: Double?) {
        // Rejected here for the same reason it is rejected when read back, so the file and the
        // flow can never disagree about what the operator configured.
        val accepted = capacityMah?.takeIf { PackCapacity.isPlausible(it) }

        val editor = preferences.edit()
        if (accepted == null) {
            editor.remove(KEY_PACK_CAPACITY_MAH)
        } else {
            // `Double.toString` round-trips exactly, and every value the UI produces is a whole
            // number of mAh, so there is no precision to lose in the text form.
            editor.putString(KEY_PACK_CAPACITY_MAH, accepted.toString())
        }
        // Applied rather than committed: the in-memory value is updated synchronously either
        // way, so the flow below is already correct, and the disk write is not something the
        // operator is waiting on.
        editor.apply()

        _packCapacityMah.value = accepted
    }

    /**
     * Null for absent, unparseable and implausible alike.
     *
     * Three different failures, one answer, because the caller can do the same thing about all
     * three — nothing — and the screen that lets them fix it says "not configured" either way.
     */
    private fun readStoredCapacity(): Double? =
        preferences.getString(KEY_PACK_CAPACITY_MAH, null)
            ?.toDoubleOrNull()
            ?.takeIf { PackCapacity.isPlausible(it) }

    private companion object {
        const val PREFERENCES_NAME = "aircraft_profile"
        const val KEY_PACK_CAPACITY_MAH = "pack_capacity_mah"
    }
}
