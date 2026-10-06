package com.batteryalert.guard.presentation.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batteryalert.guard.data.repository.BlackboxRecorder
import com.batteryalert.guard.data.repository.BlackboxRepository
import com.batteryalert.guard.data.telemetry.LinkHealthSource
import com.batteryalert.guard.domain.model.BlackboxRecord
import com.batteryalert.guard.domain.usecase.BlackboxAnalysis
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads the flight recorder back.
 *
 * The screen has two jobs that need different data, and they are kept apart deliberately:
 *
 * - the **summary** is over a whole flight, loaded as a live stream for exactly one session
 *   so its cost does not grow with the size of the log;
 * - the **table** is the newest rows of the log overall, which is what you want when you
 *   open the screen mid-flight and something has just happened.
 *
 * Reading the summary out of the table instead would be cheaper to write and would silently
 * under-report: the table is a window, and a flight longer than the window would summarise
 * as a shorter, gentler one.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: BlackboxRepository,
    recorder: BlackboxRecorder,
    linkHealthSource: LinkHealthSource,
) : ViewModel() {

    /** Null means "follow the newest flight", which is what the screen opens on. */
    private val requestedSession = MutableStateFlow<Long?>(null)

    /**
     * The flight the summary describes.
     *
     * `flatMapLatest` because switching flights must abandon the previous flight's query —
     * otherwise scrolling quickly through the picker leaves several full-session reads
     * running against a device that is meant to be flying an aircraft.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedSession: Flow<List<BlackboxRecord>> = combine(
        repository.sessionIds().map { ids -> ids.firstOrNull() },
        requestedSession,
    ) { newest, requested -> requested ?: newest }
        .distinctUntilChanged()
        .flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(emptyList<BlackboxRecord>())
            } else {
                repository.sessionFlow(sessionId)
            }
        }

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        repository.recent(TABLE_ROWS),
        repository.sessionIds(),
        repository.rowCount(),
        selectedSession,
        linkHealthSource.linkHealthFlow(),
    ) { recent, sessions, rowCount, sessionRecords, linkHealth ->
        DiagnosticsUiState(
            rowCount = rowCount,
            droppedRecords = recorder.recordsDropped,
            sessions = sessions,
            selectedSessionId = sessionRecords.firstOrNull()?.sessionId
                ?: sessions.firstOrNull(),
            summary = BlackboxAnalysis.summarise(sessionRecords),
            recentRecords = recent,
            linkHealth = linkHealth,
        )
    }
        // The diagnostic view is where you go when something is wrong, so a storage failure
        // has to render as a message on this screen rather than as a crash of the screen
        // that explains it.
        .catch { throwable ->
            emit(
                DiagnosticsUiState(
                    error = throwable.message ?: throwable::class.java.simpleName,
                ),
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = DiagnosticsUiState(),
        )

    fun onSessionSelected(sessionId: Long) {
        requestedSession.value = sessionId
    }

    fun onClearLog() {
        viewModelScope.launch {
            repository.clear()
            // Back to following the newest flight: after a wipe there is no session left to
            // have selected, and holding the old id would leave the summary permanently
            // empty with a stale flight highlighted in the picker.
            requestedSession.value = null
        }
    }

    private companion object {
        /**
         * Rows shown in the table.
         *
         * Smaller than the log's cap on purpose — this is a scrollable list on a 7-inch
         * screen, and past a few hundred rows the summary above it is what is actually
         * being read while the aircraft is in the air.
         */
        const val TABLE_ROWS = BlackboxRepository.DEFAULT_RECENT_LIMIT
    }
}
