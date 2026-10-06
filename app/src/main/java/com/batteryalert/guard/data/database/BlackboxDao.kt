package com.batteryalert.guard.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The blackbox's storage contract.
 *
 * Reads return [Flow] so the diagnostic view updates while a flight is running, which is
 * the difference between a log you consult afterwards and a log you can watch.
 */
@Dao
interface BlackboxDao {

    /**
     * Writes one row.
     *
     * Suspending rather than `@Insert(onConflict = ...)` with a flow: the recorder lives on
     * the telemetry pipeline, and a blocking write there would delay the frames that drive
     * the alerts.
     */
    @Insert
    suspend fun insert(entry: BlackboxEntry): Long

    /** The most recent rows, newest first — the diagnostic view's opening table. */
    @Query("SELECT * FROM blackbox ORDER BY recorded_at_millis DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<BlackboxEntry>>

    /** Every row of one flight, oldest first. */
    @Query(
        "SELECT * FROM blackbox WHERE session_id = :sessionId " +
            "ORDER BY recorded_at_millis ASC",
    )
    suspend fun session(sessionId: Long): List<BlackboxEntry>

    /**
     * One flight as a live stream.
     *
     * The diagnostic view summarises a whole flight, and re-reading the flight on every
     * row would make the screen's cost grow with the log's size. Room invalidates this
     * query when the table changes and hands back the new list, so the summary tracks the
     * flight at the cost of one indexed scan.
     */
    @Query(
        "SELECT * FROM blackbox WHERE session_id = :sessionId " +
            "ORDER BY recorded_at_millis ASC",
    )
    fun sessionFlow(sessionId: Long): Flow<List<BlackboxEntry>>

    /** Session ids, newest first — the flight picker. */
    @Query("SELECT session_id FROM blackbox GROUP BY session_id ORDER BY session_id DESC")
    fun sessionIds(): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM blackbox")
    fun rowCount(): Flow<Int>

    /**
     * Drops the oldest rows until at most [maxRows] remain.
     *
     * Bounded on purpose. A device that fills its storage because a monitoring app never
     * stopped writing is a monitoring app that has become the fault. The cap is a rolling
     * buffer, not an archive — anything that has to survive the flight belongs somewhere
     * else, and FR 5.3 does not ask for that.
     */
    @Query(
        "DELETE FROM blackbox WHERE id NOT IN " +
            "(SELECT id FROM blackbox ORDER BY id DESC LIMIT :maxRows)",
    )
    suspend fun trimTo(maxRows: Int)

    @Query("DELETE FROM blackbox")
    suspend fun clear()
}
