package ru.poporyadku.domain.reminder

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import ru.poporyadku.core.model.DayAssignment
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.StreakCache
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.core.time.TimeSnapshot
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.assignment.DecisionContext
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository

/*
 * Общие фейки напоминания (ITERATION_6_DESIGN.md, §14.3).
 *
 * Главное свойство: **любой записывающий метод бросает**. Так утверждение «worker ничего
 * не мутирует» (I6-D32, `I6-W2`) проверяется исполнением, а не чтением кода: если путь
 * напоминания когда-нибудь вызовет `startSession`, `recordAttempt`, `ensureInstalled` или
 * сеттер настроек, тест упадёт сам.
 */

/**
 * Настройки только для чтения; каждая запись — ошибка теста.
 *
 * @param failFromRead с какого по счёту чтения отдавать [readFailure]; `null` — никогда.
 *  Нужен, чтобы отделить отказ **оценки** от отказа **запасного расчёта**: первый читает
 *  настройки раньше, и без счётчика оба пути падали бы одинаково.
 */
internal class ReadOnlyPreferences(
    initial: UserPreferences = preferencesOf(),
    private val failFromRead: Int? = null,
    private val readFailure: Exception = IllegalStateException("чтение настроек упало"),
) : UserPreferencesRepository {

    private val state = MutableStateFlow(initial)

    var reads: Int = 0
        private set

    override val preferences: Flow<UserPreferences> = kotlinx.coroutines.flow.flow {
        reads++
        if (failFromRead != null && reads >= failFromRead) throw readFailure
        emit(state.value)
    }

    /** Эмиссия «извне» — пользователь изменил настройку между вызовами. */
    fun set(value: UserPreferences) {
        state.value = value
    }

    override suspend fun setSoundEnabled(enabled: Boolean) = write()
    override suspend fun setVibrationEnabled(enabled: Boolean) = write()
    override suspend fun setReminderEnabled(enabled: Boolean) = write()
    override suspend fun setReminderTime(time: LocalTime) = write()
    override suspend fun setThemeMode(mode: ThemeMode) = write()
    override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) = write()
    override suspend fun acceptReminderPrompt(time: LocalTime) = write()
    override suspend fun setHasSeenDragHint(seen: Boolean) = write()
    override suspend fun setHasSeenScoringHint(seen: Boolean) = write()
    override suspend fun setHasCompletedFirstDay(completed: Boolean) = write()
    override suspend fun setNotificationPromptShown(shown: Boolean) = write()
    override suspend fun setLastSeenDate(date: LocalDate?) = write()
    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) = write()

    private fun write(): Nothing =
        throw AssertionError("путь напоминания не имеет права писать настройки (I6-D32)")
}

/** Настройки, чьё чтение отказывает, — «отказ DataStore». */
internal class FailingPreferences(private val cause: Exception) : UserPreferencesRepository {
    override val preferences: Flow<UserPreferences> = flow()
    private fun flow(): Flow<UserPreferences> = kotlinx.coroutines.flow.flow { throw cause }

    override suspend fun setSoundEnabled(enabled: Boolean) = Unit
    override suspend fun setVibrationEnabled(enabled: Boolean) = Unit
    override suspend fun setReminderEnabled(enabled: Boolean) = Unit
    override suspend fun setReminderTime(time: LocalTime) = Unit
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) = Unit
    override suspend fun acceptReminderPrompt(time: LocalTime) = Unit
    override suspend fun setHasSeenDragHint(seen: Boolean) = Unit
    override suspend fun setHasSeenScoringHint(seen: Boolean) = Unit
    override suspend fun setHasCompletedFirstDay(completed: Boolean) = Unit
    override suspend fun setNotificationPromptShown(shown: Boolean) = Unit
    override suspend fun setLastSeenDate(date: LocalDate?) = Unit
    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) = Unit
}

/** Назначение дня: только `peek()`; `startSession()` — ошибка теста. */
internal class ReadOnlyAssignments(
    private val decision: Decision,
    private val time: TimeSnapshot,
    private val peekFailure: Exception? = null,
) : DayAssignmentRepository {

    var peeks: Int = 0
        private set

    override suspend fun peek(): DecisionContext {
        peeks++
        peekFailure?.let { throw it }
        return DecisionContext(decision, time)
    }

    override suspend fun startSession(): DecisionContext =
        throw AssertionError("worker не назначает набор (I6-D32)")

    override suspend fun getAssignment(localDate: LocalDate): DayAssignment? = null
}

/** Прогресс: только чтение результата дня; запись попытки — ошибка теста. */
internal class ReadOnlyProgress(
    private val today: DayResult? = null,
    private val readFailure: Exception? = null,
) : ProgressRepository {

    override suspend fun getDayResult(localDate: LocalDate): DayResult? {
        readFailure?.let { throw it }
        return today
    }

    override suspend fun recordAttempt(attempt: PuzzleAttempt): Unit =
        throw AssertionError("worker не пишет прогресс (I6-D32)")

    override suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult> = emptyList()
    override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int): PuzzleAttempt? = null
    override suspend fun getAttempts(localDate: LocalDate): List<PuzzleAttempt> = emptyList()
    override suspend fun getAllDayResults(): List<DayResult> = emptyList()
    override suspend fun getCompletedDates(): List<LocalDate> = emptyList()
    override fun observeDayResults(): Flow<List<DayResult>> = flowOf(emptyList())
}

/** Планировщик-протокол: что и в каком режиме поставили, сколько раз отменили. */
internal class RecordingScheduler(
    private val scheduleFailure: Exception? = null,
    private val cancelFailure: Exception? = null,
) : ReminderScheduler {

    val scheduled = mutableListOf<Pair<ReminderTrigger, ScheduleMode>>()
    var cancels: Int = 0
        private set

    /**
     * Попытки записи, считая неудачные. Отличаются от [scheduled], когда планировщик
     * отказывает: «сколько раз пытались» и «сколько раз получилось» — разные вопросы.
     */
    var attempts: Int = 0
        private set

    /** Задержать завершение операции — «WorkManager ещё не подтвердил запись». */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun schedule(trigger: ReminderTrigger, mode: ScheduleMode) {
        attempts++
        gate?.await()
        scheduleFailure?.let { throw it }
        scheduled += trigger to mode
    }

    override suspend fun cancel() {
        gate?.await()
        cancelFailure?.let { throw it }
        cancels++
    }
}

/** Уведомление-протокол: сколько раз показывали и снимали. */
internal class RecordingNotifier(private val showFailure: Exception? = null) : ReminderNotifier {

    var shows: Int = 0
        private set
    var cancels: Int = 0
        private set

    /** Действие перед показом — например, отменить текущую корутину. */
    var beforeShow: (suspend () -> Unit)? = null

    override suspend fun show() {
        beforeShow?.invoke()
        showFailure?.let { throw it }
        shows++
    }

    override fun cancelShown() {
        cancels++
    }
}

/** Часы с фиксированным моментом и зоной; считает обращения. */
internal class CountingClock(private var clock: Clock) : ClockProvider {

    var reads: Int = 0
        private set

    override fun clock(): Clock {
        reads++
        return clock
    }

    fun set(instant: Instant, zone: ZoneId = clock.zone) {
        clock = Clock.fixed(instant, zone)
    }
}

internal fun clockAt(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId = ZoneId.of("Europe/Moscow"),
): Clock = Clock.fixed(date.atTime(time).atZone(zone).toInstant(), zone)

internal fun snapshotAt(
    date: LocalDate,
    time: LocalTime,
    zone: ZoneId = ZoneId.of("Europe/Moscow"),
): TimeSnapshot = TimeSnapshot.of(clockAt(date, time, zone))

internal fun preferencesOf(
    reminderEnabled: Boolean = true,
    reminderTime: LocalTime = LocalTime.of(9, 0),
    notificationPromptShown: Boolean = false,
): UserPreferences = UserPreferences(
    soundEnabled = true,
    vibrationEnabled = true,
    reminderEnabled = reminderEnabled,
    reminderTime = reminderTime,
    themeMode = ThemeMode.SYSTEM,
    storedContentVersion = 1,
    storedContentFingerprint = "fingerprint-1",
    hasSeenDragHint = false,
    hasSeenScoringHint = false,
    hasCompletedFirstDay = true,
    notificationPromptShown = notificationPromptShown,
    lastSeenDate = null,
    streakCache = StreakCache.EMPTY,
)

internal fun dayResult(
    date: LocalDate,
    completedCount: Int,
    isComplete: Boolean = completedCount >= 3,
): DayResult = DayResult(
    localDate = date,
    totalScore = completedCount * 6,
    completedCount = completedCount,
    isComplete = isComplete,
    completedAt = null,
)

internal val TEST_WORK_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000600b")
