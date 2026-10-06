package com.batteryalert.guard.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The on-device blackbox store.
 *
 * **Schema export is off, deliberately.** The export is a contract that a future version
 * must migrate across, and the blackbox has no such contract: it is a bounded rolling
 * buffer of diagnostic telemetry, not user data. If the shape of a row changes, recreating
 * the table loses at most the last few hours of the log, and that is a better trade than
 * carrying migration code for a schema nobody reads. `fallbackToDestructiveMigration` is
 * the same decision stated in one line.
 *
 * It is a separate database from anything a later phase adds, so a future blackbox schema
 * change cannot take unrelated state down with it.
 */
@Database(
    entities = [BlackboxEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class GuardDatabase : RoomDatabase() {

    abstract fun blackboxDao(): BlackboxDao

    companion object {
        const val NAME = "guard-blackbox.db"
    }
}
