package ru.poporyadku.domain.usecase

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.DayAssignment
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.assignment.DecisionContext
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.model.Statistics
import ru.poporyadku.domain.model.TodayState
import ru.poporyadku.domain.model.TodayStats
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.scoring.StatisticsCalculator
import ru.poporyadku.domain.scoring.StreakCalculator
import ru.poporyadku.domain.scoring.Streaks

/**
 * Одна статистика на Home и в архиве — ITERATION_5_DESIGN.md, §3.5 (п. 8), §5.2, §10.1:
 * `I5-S6`, `I5-S7`, `I5-S9`.
 *
 * Обе стороны читают ОДНУ in-memory Room через настоящий `ProgressRepositoryImpl`, каждая
 * своим продуктовым путём: Home — `GetTodayStateUseCase` (`getAllDayResults()` и
 * `GetStreaksUseCase` поверх `getCompletedDates()`), архив — `GetStatisticsUseCase`
 * (`observeDayResults()`). Решение политики подменено на `ContentExhausted`: это
 * единственное состояние Home, которое несёт `TodayStats` при любой истории и ничего
 * не читает сверх статистики.
 *
 * Эталон — независимый наивный расчёт по самой истории (`BigDecimal`, `HALF_UP`), а не
 * вывод `StatisticsCalculator`. Среднего в `TodayStats` нет — Home его не показывает,
 * и форма `TodayStats` не меняется; среднее сверяется с эталоном.
 *
 * Псевдослучайные истории воспроизводимы: seed — литерал [RANDOM_SEED].
 */
@RunWith(RobolectricTestRunner::class)
class StatisticsConsistencyTest {

    private lateinit var db: AppDatabase
    private lateinit var clock: FakeClockProvider
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var cache: RecordingStreakCache
    private lateinit var home: GetTodayStateUseCase
    private lateinit var archive: GetStatisticsUseCase

    private class History(val name: String, val today: LocalDate, val days: List<DayResult>)

    /** Установщик контента не участвует: пакет считается установленным. */
    private class InstalledContent : ContentInstaller {
        override suspend fun ensureInstalled() = Unit
    }

    /** Наборы кончились: Home выдаёт `ContentExhausted(today, stats)` при любой истории. */
    private class ExhaustedAssignments(private val clock: FakeClockProvider) : DayAssignmentRepository {
        override suspend fun peek(): DecisionContext = DecisionContext(Decision.ContentExhausted, clock.now())
        override suspend fun startSession(): DecisionContext = throw AssertionError("сессия не начинается")
        override suspend fun getAssignment(localDate: LocalDate): DayAssignment? =
            throw AssertionError("ContentExhausted назначений не читает")
    }

    /** Из контракта настроек нужен только кэш серии — его пишет расчёт Home. */
    private class RecordingStreakCache : UserPreferencesRepository {
        var written: Triple<Int, Int, LocalDate>? = null

        override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) {
            written = Triple(current, best, date)
        }

        override val preferences: Flow<UserPreferences> get() = emptyFlow()
        override suspend fun setSoundEnabled(enabled: Boolean) = unsupported()
        override suspend fun setVibrationEnabled(enabled: Boolean) = unsupported()
        override suspend fun setReminderEnabled(enabled: Boolean) = unsupported()
        override suspend fun setReminderTime(time: LocalTime) = unsupported()
        override suspend fun setThemeMode(mode: ThemeMode) = unsupported()
        override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) = unsupported()
        override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
        override suspend fun setHasSeenScoringHint(seen: Boolean) = unsupported()
        override suspend fun setHasCompletedFirstDay(completed: Boolean) = unsupported()
        override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
        override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("не нужен в этом тесте")
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClockProvider(Clock.fixed(date(7).atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        cache = RecordingStreakCache()
        home = GetTodayStateUseCase(
            content = InstalledContent(),
            assignments = ExhaustedAssignments(clock),
            progress = progress,
            streaks = GetStreaksUseCase(progress, cache),
        )
        archive = GetStatisticsUseCase(progress, dateProvider = clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- истории -----------------------------------------------------------------

    private fun date(day: Int): LocalDate = LocalDate.of(2026, 8, day)

    private fun complete(date: LocalDate, score: Int) = DayResult(date, score, 3, true, 1L)

    private fun partial(date: LocalDate, completedCount: Int, score: Int) =
        DayResult(date, score, completedCount, false, null)

    private val example1 = listOf(
        complete(date(1), 15),
        complete(date(2), 12),
        partial(date(3), completedCount = 2, score = 8),
        complete(date(5), 18),
        complete(date(6), 11),
        partial(date(7), completedCount = 1, score = 5),
    )

    private val fixedHistories = listOf(
        History("пример 1 (I5-S1)", date(7), example1),
        History("пустая (I5-S2)", date(7), emptyList()),
        History("только незавершённый (I5-S3)", date(7), listOf(partial(date(6), completedCount = 1, score = 6))),
        History(
            "будущая дата (I5-S4)",
            date(7),
            listOf(complete(date(5), 10), complete(date(6), 14), complete(date(9), 17)),
        ),
        History(
            "пропуски и серия через Новый год",
            LocalDate.of(2026, 1, 2),
            listOf(
                complete(LocalDate.of(2025, 12, 30), 0),
                complete(LocalDate.of(2025, 12, 31), 18),
                complete(LocalDate.of(2026, 1, 1), 9),
                partial(LocalDate.of(2026, 1, 2), completedCount = 1, score = 0),
            ),
        ),
        History("сегодня завершён", date(7), listOf(complete(date(6), 13), complete(date(7), 16))),
        History("серия оборвана", date(10), (1..5).map { complete(date(it), 12 + it) }),
    )

    /** [RANDOM_HISTORIES] воспроизводимых историй: разрывы, отрезки серий, будущие даты, пустые. */
    private fun randomHistories(): List<History> {
        val random = Random(RANDOM_SEED)
        return List(RANDOM_HISTORIES) { index ->
            val today = LocalDate.of(2026, 1, 1).plusDays(random.nextLong(0, 366))
            val candidates = (-60L..5L).map { today.plusDays(it) }
            val days = candidates.shuffled(random).take(random.nextInt(0, 41)).map { date ->
                val completedCount = if (random.nextInt(4) == 0) random.nextInt(1, 3) else 3
                val score = random.nextInt(0, completedCount * 6 + 1)
                DayResult(date, score, completedCount, completedCount == 3, if (completedCount == 3) 1L else null)
            }
            History("случайная №$index (seed $RANDOM_SEED)", today, days)
        }
    }

    private suspend fun load(history: History) {
        db.clearAllTables()
        db.withTransaction { history.days.forEach { db.dayResultDao().upsert(it.toEntity()) } }
        clock.setDate(history.today, ZoneOffset.UTC)
        cache.written = null
    }

    /** Независимый эталон: наивные формулы §3.5 и точная арифметика среднего. */
    private fun reference(history: History): Statistics {
        val completed = history.days.filter { it.isComplete }
        return Statistics(
            playedDays = history.days.size,
            completedDays = completed.size,
            averageTenths = if (completed.isEmpty()) {
                null
            } else {
                BigDecimal(completed.sumOf { it.totalScore })
                    .divide(BigDecimal(completed.size), 1, RoundingMode.HALF_UP)
                    .unscaledValue()
                    .intValueExact()
            },
            bestDayScore = history.days.maxOfOrNull { it.totalScore } ?: 0,
            streaks = StreakCalculator.streaks(completed.map { it.localDate }, history.today),
        )
    }

    private suspend fun homeStats(): TodayStats {
        val state = home(emptyFlow()).first()
        assertTrue("ожидался ContentExhausted, получен $state", state is TodayState.ContentExhausted)
        return (state as TodayState.ContentExhausted).stats
    }

    private suspend fun archiveStats(): Statistics = archive(emptyFlow()).first()

    // --- I5-S6 -------------------------------------------------------------------

    private suspend fun assertHomeMatchesArchive(history: History) {
        load(history)
        val expected = reference(history)

        val archived = archiveStats()
        val homeStats = homeStats()

        assertEquals(history.name, expected, archived)
        assertEquals(
            history.name,
            TodayStats(
                streaks = archived.streaks,
                bestDayScore = archived.bestDayScore,
                playedDayCount = archived.playedDays,
                completedDayCount = archived.completedDays,
            ),
            homeStats,
        )
    }

    @Test
    fun `I5-S6 - Home TodayStats equal archive Statistics on hand-written histories`() = runBlocking {
        fixedHistories.forEach { assertHomeMatchesArchive(it) }
    }

    @Test
    fun `I5-S6 - Home TodayStats equal archive Statistics on 200 seeded pseudo-random histories`() = runBlocking {
        val histories = randomHistories()
        assertEquals(RANDOM_HISTORIES, histories.size)
        // Генератор обязан покрывать то, ради чего он заведён.
        assertTrue(histories.any { it.days.isEmpty() })
        assertTrue(histories.any { h -> h.days.any { it.localDate > h.today } })
        assertTrue(histories.any { h -> h.days.isNotEmpty() && h.days.none { it.isComplete } })

        histories.forEach { assertHomeMatchesArchive(it) }
    }

    // --- I5-S7 -------------------------------------------------------------------

    private suspend fun assertStreaksAgree(history: History) {
        load(history)
        val today = history.today

        val fromStreaksUseCase = GetStreaksUseCase(progress, cache)(today)
        val fromCalculator = StatisticsCalculator.of(progress.getAllDayResults(), today).streaks

        assertEquals(history.name, fromCalculator, fromStreaksUseCase)
        assertEquals(history.name, Triple(fromCalculator.current, fromCalculator.best, today), cache.written)

        // Внутри расчёта Home: записанный в кэш результат равен показанной серии.
        cache.written = null
        val shown = homeStats().streaks
        assertEquals(history.name, fromCalculator, shown)
        assertEquals(history.name, Triple(shown.current, shown.best, today), cache.written)
    }

    @Test
    fun `I5-S7 - GetStreaksUseCase equals StatisticsCalculator streaks on the same histories`() = runBlocking {
        (fixedHistories + randomHistories()).forEach { assertStreaksAgree(it) }
    }

    // --- I5-S9 -------------------------------------------------------------------

    private class Oracle(val playedDays: Int, val completedDays: Int, val completedSum: Int, val bestDayScore: Int) {
        /** Утверждённая целочисленная формула §3.5, п. 3 — на стороне теста. */
        val averageTenths: Int? =
            if (completedDays == 0) null else (20 * completedSum + completedDays) / (2 * completedDays)
    }

    /** Независимый SQL-агрегат §5.2 — только в тесте, в продуктовом DAO его нет. */
    private fun oracle(): Oracle = db.query(
        """
        SELECT COUNT(*)                                                         AS playedDays,
               COALESCE(SUM(CASE WHEN is_complete = 1 THEN 1 END), 0)           AS completedDays,
               COALESCE(SUM(CASE WHEN is_complete = 1 THEN total_score END), 0) AS completedSum,
               COALESCE(MAX(total_score), 0)                                    AS bestDayScore
          FROM day_results
        """.trimIndent(),
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        Oracle(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3))
    }

    private fun assertOracleMatches(name: String, oracle: Oracle, statistics: Statistics) {
        assertEquals(name, oracle.playedDays, statistics.playedDays)
        assertEquals(name, oracle.completedDays, statistics.completedDays)
        assertEquals(name, oracle.averageTenths, statistics.averageTenths)
        assertEquals(name, oracle.bestDayScore, statistics.bestDayScore)
    }

    @Test
    fun `I5-S9 - the SQL oracle equals StatisticsCalculator on example 1 written through real attempts`() =
        runBlocking {
            // Пример 1 продуктовым писателем day_results: баллы попыток по слотам.
            val attempts = mapOf(
                date(1) to listOf(6, 5, 4),
                date(2) to listOf(4, 4, 4),
                date(3) to listOf(4, 4),
                date(5) to listOf(6, 6, 6),
                date(6) to listOf(5, 3, 3),
                date(7) to listOf(5),
            )
            attempts.forEach { (day, scores) ->
                scores.forEachIndexed { slotIndex, score ->
                    progress.recordAttempt(
                        PuzzleAttempt(0, day, slotIndex, "p-$day-$slotIndex", listOf("c1", "c2", "c3", "c4"), score, 0L)
                    )
                }
            }

            val oracle = oracle()
            val statistics = StatisticsCalculator.of(progress.getAllDayResults(), date(7))

            assertEquals(6, oracle.playedDays)
            assertEquals(4, oracle.completedDays)
            assertEquals(56, oracle.completedSum)
            assertEquals(18, oracle.bestDayScore)
            assertEquals(140, oracle.averageTenths)
            assertOracleMatches("пример 1", oracle, statistics)
            assertEquals(Streaks(current = 2, best = 2), statistics.streaks)
        }

    @Test
    fun `I5-S9 - the SQL oracle equals StatisticsCalculator on every hand-written history`() = runBlocking {
        for (history in fixedHistories) {
            load(history)
            assertOracleMatches(history.name, oracle(), StatisticsCalculator.of(progress.getAllDayResults(), history.today))
        }
    }

    private companion object {
        /** Литерал: любой провал воспроизводится тем же прогоном. */
        const val RANDOM_SEED = 20_260_911L
        const val RANDOM_HISTORIES = 200
    }
}
