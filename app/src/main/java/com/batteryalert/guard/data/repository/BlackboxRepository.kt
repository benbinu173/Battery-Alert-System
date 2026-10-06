package com.batteryalert.guard.data.repository

import com.batteryalert.guard.data.database.BlackboxEntry
import com.batteryalert.guard.data.database.GuardDatabase
import com.batteryalert.guard.domain.model.AlertLevel
import com.batteryalert.guard.domain.model.AlertRule
import com.batteryalert.guard.domain.model.BlackboxRecord
import com.batteryalert.guard.domain.model.BlackboxSample
import com.batteryalert.guard.domain.model.BlackboxTrigger
import com.batteryalert.guard.domain.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The blackbox as the rest of the app sees it.
 *
 * An interface, not the DAO, for the same reason `TelemetryDataSource` is one: the
 * diagnostic view has to be testable without a database, and the ViewModel must not be
 * able to reach around the storage layer to a query it was not meant to run.
 */
interface BlackboxRepository {

    /** Persists one already-decided record and trims the log back to its cap. */
    suspend fun append(record: BlackboxRecord)

    /** The newest [limit] rows, newest first. */
    fun recent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<BlackboxRecord>>

    /** Every row of one flight, oldest first. */
    suspend fun session(sessionId: Long): List<BlackboxRecord>

    /** One flight as a live stream, oldest first. */
    fun sessionFlow(sessionId: Long): Flow<List<BlackboxRecord>>

    /** Session ids, newest first. */
    fun sessionIds(): Flow<List<Long>>

    /** How many rows the log is currently holding. */
    fun rowCount(): Flow<Int>

    suspend fun clear()

    companion object {
        /**
         * Enough rows to scroll through a recent incident without paging.
         *
         * The view is a diagnostic aid on a 7-inch controller, not a log browser; past a
         * few hundred rows the summary above the table is what is actually being read.
         */
        const val DEFAULT_RECENT_LIMIT = 400

        /**
         * The rolling cap, a little over five hours at the sampler's 1 Hz cadence.
         *
         * Sized so that a normal flight never truncates and a device left running for a
         * weekend cannot exhaust its storage.
         */
        const val MAX_ROWS = 20_000
    }
}

/**
 * The Room-backed implementation.
 *
 * The only interesting thing here is that it trims on every write. Doing the trim on
 * write rather than on a timer means the cap is enforced by the same code path that
 * creates the problem, so there is no window in which the database is over its limit and
 * nothing is scheduled to notice.
 */
@Singleton
class RoomBlackboxRepository @Inject constructor(
    private val database: GuardDatabase,
) : BlackboxRepository {

    private val dao = database.blackboxDao()

    override suspend fun append(record: BlackboxRecord) {
        dao.insert(record.toEntry())
        dao.trimTo(BlackboxRepository.MAX_ROWS)
    }

    override fun recent(limit: Int): Flow<List<BlackboxRecord>> =
        dao.recent(limit).map { entries -> entries.map { it.toRecord() } }

    override suspend fun session(sessionId: Long): List<BlackboxRecord> =
        dao.session(sessionId).map { it.toRecord() }

    override fun sessionFlow(sessionId: Long): Flow<List<BlackboxRecord>> =
        dao.sessionFlow(sessionId).map { entries -> entries.map { it.toRecord() } }

    override fun sessionIds(): Flow<List<Long>> = dao.sessionIds()

    override fun rowCount(): Flow<Int> = dao.rowCount()

    override suspend fun clear() = dao.clear()
}

/**
 * Enum round-tripping.
 *
 * Names are stored, not ordinals, so reordering an enum cannot silently reinterpret history.
 * Reading is forgiving in the other direction: a row written by an older build whose enum
 * has since lost a constant degrades to a safe default instead of throwing inside the
 * diagnostic view. A log that cannot be opened is worse than a log with one unknown label.
 */
private fun BlackboxRecord.toEntry(): BlackboxEntry = BlackboxEntry(
    recordedAtMillis = timestampMillis,
    sessionId = sessionId,
    trigger = trigger.name,
    connectionState = sample.connectionState.name,
    gpsLocked = sample.gpsLocked,
    batteryPercentage = sample.batteryPercentage,
    packVolts = sample.packVolts,
    currentAmps = sample.currentAmps,
    temperatureCelsius = sample.temperatureCelsius,
    weakestCellVolts = sample.weakestCellVolts,
    cellDeltaVolts = sample.cellDeltaVolts,
    latitude = sample.latitude,
    longitude = sample.longitude,
    altitudeMeters = sample.altitudeMeters,
    distanceToHomeMeters = sample.distanceToHomeMeters,
    alertLevel = sample.alertLevel.name,
    alertRule = sample.alertRule?.name,
)

private fun BlackboxEntry.toRecord(): BlackboxRecord = BlackboxRecord(
    timestampMillis = recordedAtMillis,
    sessionId = sessionId,
    trigger = enumOrDefault(trigger, BlackboxTrigger.CADENCE),
    sample = BlackboxSample(
        connectionState = enumOrDefault(connectionState, ConnectionState.DISCONNECTED),
        gpsLocked = gpsLocked,
        batteryPercentage = batteryPercentage,
        packVolts = packVolts,
        currentAmps = currentAmps,
        temperatureCelsius = temperatureCelsius,
        weakestCellVolts = weakestCellVolts,
        cellDeltaVolts = cellDeltaVolts,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters,
        distanceToHomeMeters = distanceToHomeMeters,
        alertLevel = enumOrDefault(alertLevel, AlertLevel.NORMAL),
        alertRule = alertRule?.let { name ->
            AlertRule.entries.firstOrNull { it.name == name }
        },
    ),
)

private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback
