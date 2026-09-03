package ru.poporyadku.data.content

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import ru.poporyadku.core.model.StreakCache
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.DailySetDao
import ru.poporyadku.data.db.dao.PuzzleDao
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.PuzzleEntity
import ru.poporyadku.domain.repository.UserPreferencesRepository

/**
 * Двойники и помощники тестов импортёра (ITERATION_4_DESIGN.md, §17, группа `I4-I`).
 */

/**
 * Настройки в памяти. Хранит ровно то, что читает импортёр, и умеет один раз упасть
 * на записи — сценарий «база записана, отметки нет» (`I4-I17`).
 */
class FakeUserPreferencesRepository(
    contentVersion: Int = 0,
    fingerprint: String? = null,
) : UserPreferencesRepository {

    private val state = MutableStateFlow(defaults(contentVersion, fingerprint))

    /** Сколько раз отметка записывалась: «ни одной записи» — утверждение о вызовах. */
    var writes: Int = 0
        private set

    /** Следующая запись бросит и обнулит флаг: повтор обязан быть успешным. */
    var failNextWrite: Boolean = false

    override val preferences: Flow<UserPreferences> = state

    val current: UserPreferences get() = state.value

    override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) {
        if (failNextWrite) {
            failNextWrite = false
            throw IllegalStateException("DataStore недоступен")
        }
        writes++
        state.value = state.value.copy(
            storedContentVersion = contentVersion,
            storedContentFingerprint = fingerprint,
        )
    }

    override suspend fun setSoundEnabled(enabled: Boolean) = unsupported()
    override suspend fun setVibrationEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderTime(time: LocalTime) = unsupported()
    override suspend fun setThemeMode(mode: ThemeMode) = unsupported()
    override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
    override suspend fun setHasSeenScoringHint(seen: Boolean) = unsupported()
    override suspend fun setHasCompletedFirstDay(completed: Boolean) = unsupported()
    override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
    override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()
    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) = unsupported()

    private fun unsupported(): Nothing = error("не вызывается тестами импортёра")

    private companion object {
        fun defaults(contentVersion: Int, fingerprint: String?) = UserPreferences(
            soundEnabled = true,
            vibrationEnabled = true,
            reminderEnabled = false,
            reminderTime = LocalTime.of(9, 0),
            themeMode = ThemeMode.SYSTEM,
            storedContentVersion = contentVersion,
            storedContentFingerprint = fingerprint,
            hasSeenDragHint = false,
            hasSeenScoringHint = false,
            hasCompletedFirstDay = false,
            notificationPromptShown = false,
            lastSeenDate = null,
            streakCache = StreakCache.EMPTY,
        )
    }
}

/** Считает записи головоломок: «ни одной записи» иначе не проверить. */
class CountingPuzzleDao(private val delegate: PuzzleDao) : PuzzleDao by delegate {
    var upsertCalls = 0
        private set

    override suspend fun upsertAll(puzzles: List<PuzzleEntity>) {
        upsertCalls++
        delegate.upsertAll(puzzles)
    }
}

/** Считает записи и удаления наборов; умеет приостановить запись ради теста отмены. */
class CountingDailySetDao(
    private val delegate: DailySetDao,
    /** Заполняется, когда запись началась. */
    private val entered: CompletableDeferred<Unit>? = null,
    /** Пока не завершён — запись висит внутри транзакции. */
    private val release: CompletableDeferred<Unit>? = null,
) : DailySetDao by delegate {

    var upsertCalls = 0
        private set
    var deleteCalls = 0
        private set

    override suspend fun upsertAll(sets: List<DailySetEntity>) {
        upsertCalls++
        entered?.complete(Unit)
        release?.await()
        delegate.upsertAll(sets)
    }

    override suspend fun deleteOutsideRange(packId: String, setCount: Int): Int {
        deleteCalls++
        return delegate.deleteOutsideRange(packId, setCount)
    }
}

/**
 * Побайтовый снимок ВСЕХ ПЯТИ таблиц.
 *
 * Читается сырым курсором, а не через DAO: снимок обязан видеть таблицу целиком,
 * включая то, чего ни один продуктовый запрос не читает. «Ничего не изменено» —
 * утверждение обо всей базе, а не о тех строках, которые удобно достать.
 */
fun AppDatabase.snapshot(): Map<String, List<String>> = TABLES.associateWith { table ->
    dumpTable(this, table)
}

/** Только история: `day_assignments`, `puzzle_attempts`, `day_results`. */
fun AppDatabase.historySnapshot(): Map<String, List<String>> =
    HISTORY_TABLES.associateWith { table -> dumpTable(this, table) }

private val TABLES = listOf(
    "puzzles",
    "daily_sets",
    "day_assignments",
    "puzzle_attempts",
    "day_results",
)

private val HISTORY_TABLES = listOf("day_assignments", "puzzle_attempts", "day_results")

private fun dumpTable(db: AppDatabase, table: String): List<String> {
    val rows = mutableListOf<String>()
    db.query("SELECT * FROM $table", null).use { cursor ->
        val order = (0 until cursor.columnCount).sortedBy { cursor.getColumnName(it) }
        while (cursor.moveToNext()) {
            rows += order.joinToString("|") { index ->
                "${cursor.getColumnName(index)}=" +
                    if (cursor.isNull(index)) "null" else cursor.getString(index)
            }
        }
    }
    return rows.sorted()
}
