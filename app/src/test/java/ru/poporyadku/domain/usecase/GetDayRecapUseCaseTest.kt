package ru.poporyadku.domain.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.Category
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.DailySet
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.content.temporary.BundledPuzzles
import ru.poporyadku.data.content.temporary.TemporaryPuzzleRepository
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.scoring.StreakCalculator

/**
 * ITERATION_3_DESIGN.md, §19: `I3-U20`–`I3-U22`, `I3-U28`, `I3-U38`–`I3-U41`.
 *
 * Отдельного `GetStreaksUseCaseTest` нет: `I3-U22` проверяется здесь, потому что итог
 * дня — одна из двух точек, где кэш серии заполняется, и вторая (Home) уже покрыта.
 */
@RunWith(RobolectricTestRunner::class)
class GetDayRecapUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var assignments: DayAssignmentRepositoryImpl
    private lateinit var preferences: RecordingPreferences

    private val date = LocalDate.of(2026, 9, 1)
    private val zone = ZoneOffset.UTC
    private val packId = ContentPack.CORE_RU
    private val bundledSet: DailySet = BundledPuzzles.sets.first()

    /** Из всего контракта настроек здесь нужен только кэш серии. */
    private class RecordingPreferences : UserPreferencesRepository {
        var writes = 0
        var current: Int? = null
        var best: Int? = null
        var date: LocalDate? = null

        override val preferences: Flow<UserPreferences> get() = emptyFlow()
        override suspend fun setSoundEnabled(enabled: Boolean) = unsupported()
        override suspend fun setVibrationEnabled(enabled: Boolean) = unsupported()
        override suspend fun setReminderEnabled(enabled: Boolean) = unsupported()
        override suspend fun setReminderTime(time: LocalTime) = unsupported()
        override suspend fun setThemeMode(mode: ThemeMode) = unsupported()
        override suspend fun setStoredContentVersion(version: Int) = unsupported()
        override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
        override suspend fun setHasSeenScoringHint(seen: Boolean) = unsupported()
        override suspend fun setHasCompletedFirstDay(completed: Boolean) = unsupported()
        override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
        override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()

        override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) {
            writes++
            this.current = current
            this.best = best
            this.date = date
        }

        private fun unsupported(): Nothing = throw UnsupportedOperationException("не нужен в этом тесте")
    }

    /** Головоломок нет вовсе: итог дня обязан строиться и без них (I3-D37). */
    private object MissingPuzzles : PuzzleRepository {
        override suspend fun getPuzzle(puzzleId: String): Puzzle? = null
    }

    private fun correctOrderAt(slotIndex: Int): List<String> {
        val puzzleId = bundledSet.puzzleIdAt(slotIndex)
        return BundledPuzzles.puzzles.first { it.puzzleId == puzzleId }.correctOrder
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = FakeClockProvider(Clock.fixed(date.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone))
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        assignments = DayAssignmentRepositoryImpl(db, db.assignmentDao(), db.dailySetDao(), clock, packId)
        preferences = RecordingPreferences()
        runBlocking {
            db.dailySetDao().upsertAll(listOf(bundledSet.toEntity()))
            db.assignmentDao().insert(DayAssignmentEntity(date.toString(), packId, 0, assignedAt = 1L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun useCase(puzzles: PuzzleRepository = TemporaryPuzzleRepository()) = GetDayRecapUseCase(
        assignments = assignments,
        puzzles = puzzles,
        progress = progress,
        streaks = GetStreaksUseCase(progress, preferences),
    )

    private fun submitUseCase(puzzles: PuzzleRepository = TemporaryPuzzleRepository()) = SubmitAnswerUseCase(
        assignments = assignments,
        sets = DailySetRepositoryImpl(db.dailySetDao()),
        puzzles = puzzles,
        progress = progress,
    )

    /** Завершённый день прямо в базе: попыток у него нет, только строка итога и назначение. */
    private suspend fun seedCompletedDay(day: LocalDate, setIndex: Int, isComplete: Boolean = true) {
        db.assignmentDao().insert(DayAssignmentEntity(day.toString(), packId, setIndex, assignedAt = 1L))
        db.dayResultDao().upsert(
            DayResultEntity(
                localDate = day.toString(),
                totalScore = if (isComplete) 18 else 6,
                completedCount = if (isComplete) 3 else 1,
                isComplete = isComplete,
                completedAt = if (isComplete) 1L else null,
            )
        )
    }

    private suspend fun seedRange(from: LocalDate, days: Long, startSetIndex: Int) {
        for (i in 0 until days) seedCompletedDay(from.plusDays(i), startSetIndex + i.toInt())
    }

    @Test
    fun `I3-U20 - a full day is 18 of 18 with three played categories and a refreshed cache`() = runTest {
        repeat(3) { slot -> submitUseCase()(date, slot, Submission.Answer(correctOrderAt(slot))) }

        val recap = useCase()(date, today = date)

        assertTrue("ожидался Content, получен $recap", recap is DayRecapResult.Content)
        recap as DayRecapResult.Content
        assertEquals(date, recap.localDate)
        assertEquals(1, recap.dayNumber)
        assertEquals(18, recap.totalScore)
        assertTrue(recap.isComplete)
        assertEquals(
            listOf(
                SlotOutcome.Played(0, 6, Category.GEOGRAPHY),
                SlotOutcome.Played(1, 6, Category.HISTORY),
                SlotOutcome.Played(2, 6, Category.SCIENCE),
            ),
            recap.slots,
        )
        assertEquals(1, recap.currentStreak)
        assertEquals(1, recap.bestStreak)
        assertTrue(recap.isRecordUpdated)
        assertEquals(date, preferences.date)
    }

    @Test
    fun `I3-U21 - a date without a day_results row is NotFound`() = runTest {
        assertEquals(DayRecapResult.NotFound, useCase()(date, today = date))
    }

    @Test
    fun `I3-U22 - the streak cache is written in one operation with the computed values`() = runTest {
        seedRange(LocalDate.of(2026, 8, 20), days = 3, startSetIndex = 10)
        repeat(3) { slot -> submitUseCase()(date, slot, Submission.Answer(correctOrderAt(slot))) }
        val today = LocalDate.of(2026, 9, 2)

        val recap = useCase()(date, today = today) as DayRecapResult.Content

        val expected = StreakCalculator.streaks(progress.getCompletedDates(), today)
        assertEquals(1, preferences.writes)
        assertEquals(today, preferences.date)
        assertEquals(expected.current, preferences.current)
        assertEquals(expected.best, preferences.best)
        // Показывается посчитанное, а не прочитанное из кэша.
        assertEquals(expected.current, recap.currentStreak)
        assertEquals(expected.best, recap.bestStreak)
    }

    @Test
    fun `I3-U28 - a day made of three skips is complete, scores zero and shows three Unavailable`() = runTest {
        repeat(3) { slot -> submitUseCase(MissingPuzzles)(date, slot, Submission.Skip) }

        val recap = useCase(MissingPuzzles)(date, today = date) as DayRecapResult.Content

        assertTrue(recap.isComplete)
        assertEquals(0, recap.totalScore)
        assertEquals(
            listOf(
                SlotOutcome.Unavailable(0, 0),
                SlotOutcome.Unavailable(1, 0),
                SlotOutcome.Unavailable(2, 0),
            ),
            recap.slots,
        )
        // Серия «про присутствие, а не про качество»: день из трёх пропусков её продолжает.
        assertEquals(1, recap.currentStreak)
    }

    @Test
    fun `I3-U28 - an answered slot whose puzzle cannot be loaded keeps its actual score`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))

        val recap = useCase(MissingPuzzles)(date, today = date) as DayRecapResult.Content

        assertEquals(listOf(SlotOutcome.Unavailable(0, 6)), recap.slots)
        assertFalse(recap.isComplete)
    }

    @Test
    fun `I3-U38 - a day that beats the previous record sets isRecordUpdated`() = runTest {
        // Пять завершённых дней подряд, шестой — рассматриваемый.
        seedRange(LocalDate.of(2026, 6, 1), days = 5, startSetIndex = 20)
        val day = LocalDate.of(2026, 6, 6)
        seedCompletedDay(day, setIndex = 30)

        val recap = useCase()(day, today = LocalDate.of(2026, 6, 6)) as DayRecapResult.Content

        assertTrue(recap.isRecordUpdated)
    }

    @Test
    fun `I3-U39 - merely repeating the previous record does not set isRecordUpdated`() = runTest {
        seedRange(LocalDate.of(2026, 4, 1), days = 6, startSetIndex = 20) // прежний рекорд 6
        seedRange(LocalDate.of(2026, 6, 1), days = 5, startSetIndex = 40)
        val day = LocalDate.of(2026, 6, 6)
        seedCompletedDay(day, setIndex = 50)

        val recap = useCase()(day, today = day) as DayRecapResult.Content

        assertEquals(6, recap.bestStreak)
        assertFalse(recap.isRecordUpdated)
    }

    @Test
    fun `I3-U40 - an unfinished day and a broken streak give false, the first day gives true`() = runTest {
        val first = LocalDate.of(2026, 3, 1)
        seedCompletedDay(first, setIndex = 20)
        assertTrue((useCase()(first, today = first) as DayRecapResult.Content).isRecordUpdated)

        // День не завершён: рекорд он установить не может.
        val partial = LocalDate.of(2026, 3, 2)
        seedCompletedDay(partial, setIndex = 21, isComplete = false)
        val partialRecap = useCase()(partial, today = partial) as DayRecapResult.Content
        assertFalse(partialRecap.isComplete)
        assertFalse(partialRecap.isRecordUpdated)

        // Серия прервана: прежний рекорд 4, серия этого дня 1.
        seedRange(LocalDate.of(2026, 5, 1), days = 4, startSetIndex = 30)
        val isolated = LocalDate.of(2026, 5, 10)
        seedCompletedDay(isolated, setIndex = 40)
        assertFalse((useCase()(isolated, today = isolated) as DayRecapResult.Content).isRecordUpdated)
    }

    @Test
    fun `I3-U41 - an archive day keeps its own verdict regardless of later results and today`() = runTest {
        seedRange(LocalDate.of(2026, 1, 1), days = 6, startSetIndex = 20) // первый рекорд 6
        seedRange(LocalDate.of(2026, 4, 1), days = 6, startSetIndex = 40) // рекорд лишь повторён
        seedRange(LocalDate.of(2026, 7, 1), days = 10, startSetIndex = 60) // более поздние результаты

        val recordDay = LocalDate.of(2026, 1, 6)
        val repeatDay = LocalDate.of(2026, 4, 6)

        val record = useCase()(recordDay, today = LocalDate.of(2026, 9, 20)) as DayRecapResult.Content
        val repeat = useCase()(repeatDay, today = LocalDate.of(2026, 9, 20)) as DayRecapResult.Content

        assertTrue(record.isRecordUpdated)
        assertFalse(repeat.isRecordUpdated)

        // Тот же вердикт при другом «сегодня»: свойство дня, а не момента просмотра.
        val other = useCase()(recordDay, today = LocalDate.of(2026, 1, 6)) as DayRecapResult.Content
        assertTrue(other.isRecordUpdated)
        assertEquals(record.isRecordUpdated, other.isRecordUpdated)
        // А отображаемая серия от «сегодня» как раз зависит: 2026-09-20 не примыкает
        // ни к одному завершённому дню, 2026-01-06 закрывает первую серию из шести.
        assertEquals(0, record.currentStreak)
        assertEquals(10, record.bestStreak)
        assertEquals(6, other.currentStreak)
    }
}
