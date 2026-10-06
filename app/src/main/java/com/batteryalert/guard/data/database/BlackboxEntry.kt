package com.batteryalert.guard.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One blackbox row on disk.
 *
 * Flat, and every nullable column is genuinely nullable: "the link did not report a
 * temperature" must survive a round trip through SQLite as null, not as 0 °C. That is the
 * same convention the domain layer uses, and it is the reason none of these columns has a
 * `NOT NULL DEFAULT 0`.
 *
 * Every electrical column is `Double`/`REAL` and every position is `Double`, so nothing is
 * rounded on the way in. The log is evidence; the formatting belongs on the way out.
 *
 * The indices are chosen for the two queries the diagnostic view actually runs — "the most
 * recent rows" and "this session" — rather than for the write path, which is the one that
 * has to stay cheap.
 */
@Entity(
    tableName = "blackbox",
    indices = [
        Index(value = ["recorded_at_millis"]),
        Index(value = ["session_id"]),
    ],
)
data class BlackboxEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** Wall clock, milliseconds since the epoch. */
    @ColumnInfo(name = "recorded_at_millis")
    val recordedAtMillis: Long,

    /** The first frame's timestamp. Groups rows into one flight. */
    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    /** A [com.batteryalert.guard.domain.model.BlackboxTrigger] name. */
    @ColumnInfo(name = "trigger")
    val trigger: String,

    @ColumnInfo(name = "connection_state")
    val connectionState: String,

    @ColumnInfo(name = "gps_locked")
    val gpsLocked: Boolean,

    // --- Battery (FR 5.3) ----------------------------------------------------------
    @ColumnInfo(name = "battery_percentage")
    val batteryPercentage: Double?,

    @ColumnInfo(name = "pack_volts")
    val packVolts: Double?,

    @ColumnInfo(name = "current_amps")
    val currentAmps: Double?,

    @ColumnInfo(name = "temperature_celsius")
    val temperatureCelsius: Double?,

    @ColumnInfo(name = "weakest_cell_volts")
    val weakestCellVolts: Double?,

    @ColumnInfo(name = "cell_delta_volts")
    val cellDeltaVolts: Double?,

    // --- Position (FR 5.3's "mapped to GPS") ---------------------------------------
    @ColumnInfo(name = "latitude")
    val latitude: Double?,

    @ColumnInfo(name = "longitude")
    val longitude: Double?,

    @ColumnInfo(name = "altitude_meters")
    val altitudeMeters: Double?,

    @ColumnInfo(name = "distance_to_home_meters")
    val distanceToHomeMeters: Double?,

    // --- Why this row exists -------------------------------------------------------
    @ColumnInfo(name = "alert_level")
    val alertLevel: String,

    @ColumnInfo(name = "alert_rule")
    val alertRule: String?,
)
