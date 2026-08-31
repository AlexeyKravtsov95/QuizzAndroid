package ru.poporyadku.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AssignmentDao
import ru.poporyadku.data.db.dao.AttemptDao
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.dao.DayResultDao
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository

// ITERATION_2_DESIGN.md, PR 2C. Состояние экрана. Ошибки отражаются в uiState.error,
// а не теряются; диагностика (DebugDiagnostics) читается отдельно от Decision и не
// выдаётся за внутренний снимок политики (D-21).
data class DebugUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val lastDecision: Decision? = null,
    val diagnostics: DebugAssignmentView? = null,
    val error: String? = null,
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val sessionController: DebugSessionController,
    private val diagnostics: DebugDiagnostics,
    private val fixture: DebugContentFixture,
    private val database: AppDatabase,
    assignmentDao: AssignmentDao,
    attemptDao: AttemptDao,
    dayResultDao: DayResultDao,
    dailySetDao: DailySetDao,
    private val progress: ProgressRepository,
    private val preferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState

    // Четыре потока сырых дампов таблиц — §8 задания.
    val dayAssignments: StateFlow<List<DayAssignmentEntity>> =
        assignmentDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val puzzleAttempts: StateFlow<List<PuzzleAttemptEntity>> =
        attemptDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dayResults: StateFlow<List<DayResultEntity>> =
        dayResultDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dailySets: StateFlow<List<DailySetEntity>> =
        dailySetDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val preferences: StateFlow<UserPreferences?> =
        preferencesRepository.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onDateSelected(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = date) }
    }

    fun peek() = launchTracked {
        val decision = sessionController.peekAt(_uiState.value.selectedDate)
        _uiState.update { it.copy(lastDecision = decision) }
        refreshDiagnostics()
    }

    fun startSession() = launchTracked {
        val decision = sessionController.startSessionAt(_uiState.value.selectedDate)
        _uiState.update { it.copy(lastDecision = decision) }
        refreshDiagnostics()
    }

    fun resetClock() = launchTracked {
        sessionController.resetClock()
        refreshDiagnostics()
    }

    fun installFixture(setCount: Int = 5) = launchTracked {
        fixture.install(setCount)
    }

    fun clearDatabase() = launchTracked {
        // clearAllTables() блокирующий, не suspend — Room запрещает вызывать его
        // на главном потоке (viewModelScope по умолчанию на Dispatchers.Main).
        withContext(Dispatchers.IO) { database.clearAllTables() }
        _uiState.update { it.copy(lastDecision = null, diagnostics = null) }
    }

    fun recordAttempt(slotIndex: Int, score: Int) = launchTracked {
        progress.recordAttempt(
            PuzzleAttempt(
                id = 0,
                localDate = _uiState.value.selectedDate,
                slotIndex = slotIndex,
                puzzleId = "debug-attempt-$slotIndex",
                submittedOrder = listOf("a", "b", "c", "d"),
                score = score,
                submittedAt = 0L,
            )
        )
    }

    fun setSoundEnabled(enabled: Boolean) = launchTracked { preferencesRepository.setSoundEnabled(enabled) }
    fun setVibrationEnabled(enabled: Boolean) = launchTracked { preferencesRepository.setVibrationEnabled(enabled) }
    fun setReminderEnabled(enabled: Boolean) = launchTracked { preferencesRepository.setReminderEnabled(enabled) }
    fun setReminderTime(time: LocalTime) = launchTracked { preferencesRepository.setReminderTime(time) }
    fun setThemeMode(mode: ThemeMode) = launchTracked { preferencesRepository.setThemeMode(mode) }
    fun setStoredContentVersion(version: Int) = launchTracked {
        preferencesRepository.setStoredContentVersion(version)
    }
    fun setHasSeenDragHint(seen: Boolean) = launchTracked { preferencesRepository.setHasSeenDragHint(seen) }
    fun setHasSeenScoringHint(seen: Boolean) = launchTracked { preferencesRepository.setHasSeenScoringHint(seen) }
    fun setHasCompletedFirstDay(completed: Boolean) = launchTracked {
        preferencesRepository.setHasCompletedFirstDay(completed)
    }
    fun setNotificationPromptShown(shown: Boolean) = launchTracked {
        preferencesRepository.setNotificationPromptShown(shown)
    }
    fun setLastSeenDate(date: LocalDate?) = launchTracked { preferencesRepository.setLastSeenDate(date) }

    /** Единственная операция записи кэша серии — раздельных кнопок для трёх ключей нет (D-18). */
    fun updateStreakCache(current: Int, best: Int, date: LocalDate) = launchTracked {
        preferencesRepository.updateStreakCache(current, best, date)
    }

    private suspend fun refreshDiagnostics() {
        val view = diagnostics.read()
        _uiState.update { it.copy(diagnostics = view) }
    }

    /** Общий каркас: ошибка отражается в состоянии, а не теряется молча. */
    private fun launchTracked(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onSuccess { _uiState.update { it.copy(error = null) } }
            .onFailure { e -> _uiState.update { it.copy(error = e.message ?: e.toString()) } }
    }
}
