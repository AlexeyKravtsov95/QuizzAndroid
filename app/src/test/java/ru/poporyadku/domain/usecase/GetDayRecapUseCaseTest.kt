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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.Category
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.DailySet
import ru.poporyadku.core.model.InMemoryPuzzleRepository
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.TestContent
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.content.FakeUserPreferencesRepository
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
 * ITERATION_3_DESIGN.md, §19: `I3-U20`–`I3-U22`, `I3-U28`, `I3-U38`–`I3-U41`;
 * ITERATION_5_DESIGN.md, §10.1: `I5-R3`, `I5-R4` (часть итога).
 *
 * С PR 5B загрузка итога только читает Room (I5-D31): `GetDayRecapUseCase` не зависит ни
 * от `GetStreaksUseCase`, ни от настроек, поэтому записать `StreakCache` ему нечем по
 * построению. `I3-U22` проверяет единственного оставшегося писателя кэша —
 * `GetStreaksUseCase` (его вызывает расчёт Home) — напрямую.
 */
@RunWith(RobolectricTestRunner::class)
class GetDayRecapUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var assignments: DayAssignmentRepositoryImpl

    private val date = LocalDate.of(2026, 9, 1)
    private val zone = ZoneOffset.UTC
    private val packId = ContentPack.CORE_RU
    /** Независимая фикстура (I4-D22): временный источник исчезает в PR 4D. */
    private val fixtureSet: DailySet = TestContent.set

    /** Из всего контракта настроек `GetStreaksUseCase` пишет только кэш серии. */
    private class RecordingStreakCache : UserPreferencesRepository {
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
        override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) = unsupported()
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

    /** Настройки записи попыток: флаг первого дня здесь предметом проверки не является. */
    private class SubmitPreferences(
        delegate: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    ) : UserPreferencesRepository by delegate {
        override suspend fun setHasCompletedFirstDay(completed: Boolean) = Unit
    }

    /** Головоломок нет вовсе: итог дня обязан строиться и без них (I3-D37). */
    private object MissingPuzzles : PuzzleRepository {
        override suspend fun getPuzzle(puzzleId: String): Puzzle? = null
    }

    private fun correctOrderAt(slotIndex: Int): List<String> =
        TestContent.correctOrderOf(fixtureSet.puzzleIdAt(slotIndex))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = FakeClockProvider(Clock.fixed(date.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone))
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        assignments = DayAssignmentRepositoryImpl(db, db.assignmentDao(), db.dailySetDao(), clock, packId)
        runBlocking {
            db.dailySetDao().upsertAll(listOf(fixtureSet.toEntity()))
            db.assignmentDao().insert(DayAssignmentEntity(date.toString(), packId, 0, assignedAt = 1L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Ровно три зависимости: назначения, головоломки, прогресс. Настроек нет. */
    private fun useCase(puzzles: PuzzleRepository = InMemoryPuzzleRepository()) = GetDayRecapUseCase(
        assignments = assignments,
        puzzles = puzzles,
        progress = progress,
    )

    private fun submitUseCase(puzzles: PuzzleRepository = InMemoryPuzzleRepository()) = SubmitAnswerUseCase(
        assignments = assignments,
        sets = DailySetRepositoryImpl(db.dailySetDao()),
        puzzles = puzzles,
        progress = progress,
        preferences = SubmitPreferences(),
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
    fun `I3-U20 - a full day is 18 of 18 with three played categories`() = runTest {
        repeat(3) { slot -> submitUseCase()(date, slot, Submission.Answer(correctOrderAt(slot))) }

        val recap = useCase()(date)

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
        // Серия этого дня посчитана из day_results, а не прочитана из кэша.
        assertEquals(1, recap.streakAtDay)
        assertTrue(recap.isRecordUpdated)
    }

    @Test
    fun `I3-U21 - a date without a day_results row is NotFound`() = runTest {
        assertEquals(DayRecapResult.NotFound, useCase()(date))
    }

    @Test
    fun `I3-U22 - GetStreaksUseCase writes the streak cache in one operation with the computed values`() = runTest {
        seedRange(LocalDate.of(2026, 8, 20), days = 3, startSetIndex = 10)
        repeat(3) { slot -> submitUseCase()(date, slot, Submission.Answer(correctOrderAt(slot))) }
        val today = LocalDate.of(2026, 9, 2)
        val cache = RecordingStreakCache()

        val streaks = GetStreaksUseCase(progress, cache)(today)

        val expected = StreakCalculator.streaks(progress.getCompletedDates(), today)
        assertEquals(expected, streaks)
        assertEquals(1, cache.writes)
        assertEquals(today, cache.date)
        assertEquals(expected.current, cache.current)
        assertEquals(expected.best, cache.best)
    }

    @Test
    fun `I3-U28 - a day made of three skips is complete, scores zero and shows three Unavailable`() = runTest {
        repeat(3) { slot -> submitUseCase(MissingPuzzles)(date, slot, Submission.Skip) }

        val recap = useCase(MissingPuzzles)(date) as DayRecapResult.Content

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
        assertEquals(1, recap.streakAtDay)
    }

    @Test
    fun `I3-U28 - an answered slot whose puzzle cannot be loaded keeps its actual score`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))

        val recap = useCase(MissingPuzzles)(date) as DayRecapResult.Content

        assertEquals(
            listOf(SlotOutcome.Unavailable(0, 6), SlotOutcome.NotPlayed(1), SlotOutcome.NotPlayed(2)),
            recap.slots,
        )
        assertFalse(recap.isComplete)
    }

    @Test
    fun `I3-U38 - a day that beats the previous record sets isRecordUpdated`() = runTest {
        // Пять завершённых дней подряд, шестой — рассматриваемый.
        seedRange(LocalDate.of(2026, 6, 1), days = 5, startSetIndex = 20)
        val day = LocalDate.of(2026, 6, 6)
        seedCompletedDay(day, setIndex = 30)

        val recap = useCase()(day) as DayRecapResult.Content

        assertTrue(recap.isRecordUpdated)
        assertEquals(6, recap.streakAtDay)
    }

    @Test
    fun `I3-U39 - merely repeating the previous record does not set isRecordUpdated`() = runTest {
        seedRange(LocalDate.of(2026, 4, 1), days = 6, startSetIndex = 20) // прежний рекорд 6
        seedRange(LocalDate.of(2026, 6, 1), days = 5, startSetIndex = 40)
        val day = LocalDate.of(2026, 6, 6)
        seedCompletedDay(day, setIndex = 50)

        val recap = useCase()(day) as DayRecapResult.Content

        assertEquals(6, recap.streakAtDay)
        assertFalse(recap.isRecordUpdated)
    }

    @Test
    fun `I3-U40 - an unfinished day and a broken streak give false, the first day gives true`() = runTest {
        val first = LocalDate.of(2026, 3, 1)
        seedCompletedDay(first, setIndex = 20)
        assertTrue((useCase()(first) as DayRecapResult.Content).isRecordUpdated)

        // День не завершён: рекорд он установить не может.
        val partial = LocalDate.of(2026, 3, 2)
        seedCompletedDay(partial, setIndex = 21, isComplete = false)
        val partialRecap = useCase()(partial) as DayRecapResult.Content
        assertFalse(partialRecap.isComplete)
        assertFalse(partialRecap.isRecordUpdated)

        // Серия прервана: прежний рекорд 4, серия этого дня 1.
        seedRange(LocalDate.of(2026, 5, 1), days = 4, startSetIndex = 30)
        val isolated = LocalDate.of(2026, 5, 10)
        seedCompletedDay(isolated, setIndex = 40)
        val isolatedRecap = useCase()(isolated) as DayRecapResult.Content
        assertFalse(isolatedRecap.isRecordUpdated)
        assertEquals(1, isolatedRecap.streakAtDay)
    }

    @Test
    fun `I3-U41 - an archive day keeps its own verdict and its own streak regardless of later results`() = runTest {
        seedRange(LocalDate.of(2026, 1, 1), days = 6, startSetIndex = 20) // первый рекорд 6
        seedRange(LocalDate.of(2026, 4, 1), days = 6, startSetIndex = 40) // рекорд лишь повторён
        seedRange(LocalDate.of(2026, 7, 1), days = 10, startSetIndex = 60) // более поздние результаты

        val recordDay = LocalDate.of(2026, 1, 6)
        val repeatDay = LocalDate.of(2026, 4, 6)

        val record = useCase()(recordDay) as DayRecapResult.Content
        val repeat = useCase()(repeatDay) as DayRecapResult.Content

        assertTrue(record.isRecordUpdated)
        assertFalse(repeat.isRecordUpdated)
        // Серия — серия ЭТОГО дня (O5-5): более поздняя серия из десяти дней её не меняет,
        // а «сегодня» в use case больше не входит вовсе.
        assertEquals(6, record.streakAtDay)
        assertEquals(6, repeat.streakAtDay)
    }

    // --- I5-R3: три слота, NotPlayed, серия дня --------------------------------------

    @Test
    fun `I5-R3 - a complete day has three slots and no NotPlayed`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))
        submitUseCase(MissingPuzzles)(date, 1, Submission.Skip)
        submitUseCase()(date, 2, Submission.Answer(correctOrderAt(2).reversed()))

        val recap = useCase()(date) as DayRecapResult.Content

        assertTrue(recap.isComplete)
        assertEquals(listOf(0, 1, 2), recap.slots.map { it.slotIndex })
        assertTrue(recap.slots.none { it is SlotOutcome.NotPlayed })
        assertEquals(
            listOf(
                SlotOutcome.Played(0, 6, Category.GEOGRAPHY),
                SlotOutcome.Unavailable(1, 0),
                SlotOutcome.Played(2, 0, Category.SCIENCE),
            ),
            recap.slots,
        )
    }

    @Test
    fun `I5-R3 - an incomplete day has exactly three minus completed_count NotPlayed slots`() = runTest {
        // Одна попытка: два «не сыграно».
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))
        val one = useCase()(date) as DayRecapResult.Content
        assertEquals(listOf(0, 1, 2), one.slots.map { it.slotIndex })
        assertEquals(3 - 1, one.slots.count { it is SlotOutcome.NotPlayed })
        assertEquals(listOf(SlotOutcome.NotPlayed(1), SlotOutcome.NotPlayed(2)), one.slots.drop(1))

        // Две попытки не подряд: пропущенный слот в середине — единственный «не сыграно».
        submitUseCase()(date, 2, Submission.Answer(correctOrderAt(2)))
        val two = useCase()(date) as DayRecapResult.Content
        assertFalse(two.isComplete)
        assertEquals(3 - 2, two.slots.count { it is SlotOutcome.NotPlayed })
        assertEquals(SlotOutcome.NotPlayed(1), two.slots[1])
        assertEquals(SlotOutcome.Played(2, 6, Category.SCIENCE), two.slots[2])

        // Незавершённый день серию не продолжал и рекорда не ставит.
        assertNull(two.streakAtDay)
        assertFalse(two.isRecordUpdated)
    }

    @Test
    fun `I5-R3 - streakAtDay is the streak that ended on that day, across a gap`() = runTest {
        // 06-01…06-03, разрыв 06-04, 06-05…06-06, затем поздний 06-10.
        seedRange(LocalDate.of(2026, 6, 1), days = 3, startSetIndex = 20)
        seedRange(LocalDate.of(2026, 6, 5), days = 2, startSetIndex = 30)
        seedCompletedDay(LocalDate.of(2026, 6, 10), setIndex = 40)

        assertEquals(3, (useCase()(LocalDate.of(2026, 6, 3)) as DayRecapResult.Content).streakAtDay)
        assertEquals(1, (useCase()(LocalDate.of(2026, 6, 5)) as DayRecapResult.Content).streakAtDay)
        assertEquals(2, (useCase()(LocalDate.of(2026, 6, 6)) as DayRecapResult.Content).streakAtDay)
        assertEquals(1, (useCase()(LocalDate.of(2026, 6, 10)) as DayRecapResult.Content).streakAtDay)

        // Рекорд ставит только день, чья серия строго длиннее прежней лучшей.
        assertTrue((useCase()(LocalDate.of(2026, 6, 3)) as DayRecapResult.Content).isRecordUpdated)
        assertFalse((useCase()(LocalDate.of(2026, 6, 6)) as DayRecapResult.Content).isRecordUpdated)
    }

    // --- I5-R4: исторический puzzleId ------------------------------------------------

    @Test
    fun `I5-R4 - replacing the slot in daily_sets does not change the recap of a played day`() = runTest {
        repeat(3) { slot -> submitUseCase()(date, slot, Submission.Answer(correctOrderAt(slot))) }

        // Следующая поставка отозвала головоломку слота 0 и поставила в слот замену другой
        // категории (I4-D4). Отозванная строка в puzzles остаётся.
        val replacement = TestContent.puzzle(REPLACEMENT_ID, category = Category.CULTURE, contentVersion = 2)
        db.dailySetDao().upsertAll(listOf(fixtureSet.copy(puzzleId1 = REPLACEMENT_ID).toEntity()))
        val pool = InMemoryPuzzleRepository(
            TestContent.puzzles.map {
                if (it.puzzleId == TestContent.FIRST_PUZZLE_ID) it.copy(retiredIn = 2, contentVersion = 2) else it
            } + replacement,
        )

        val recap = useCase(pool)(date) as DayRecapResult.Content

        // Категория — отозванной головоломки из попытки, а не замены из набора; отозванная
        // остаётся Played (I5-D11).
        assertEquals(SlotOutcome.Played(0, 6, Category.GEOGRAPHY), recap.slots[0])
    }

    private companion object {
        const val REPLACEMENT_ID = "fix-zamena-900"
    }
}
