package ru.poporyadku.domain.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
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
import ru.poporyadku.core.model.InMemoryPuzzleRepository
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.TestContent
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.content.FakeUserPreferencesRepository
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.scoring.PairwiseScoreCalculator

/**
 * ITERATION_3_DESIGN.md, §19: `I3-U14`, `I3-U15`, `I3-U36`;
 * ITERATION_5_DESIGN.md, §10.1: `I5-R2`, `I5-R4` (часть результата).
 *
 * Экран результата обязан восстанавливаться из базы: между записью и чтением здесь нет
 * ни общего состояния, ни общего экземпляра use case.
 */
@RunWith(RobolectricTestRunner::class)
class GetPuzzleResultUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var assignments: DayAssignmentRepositoryImpl

    private val date = LocalDate.of(2026, 9, 1)
    private val zone = ZoneOffset.UTC
    private val packId = ContentPack.CORE_RU
    /** Независимая фикстура (I4-D22): временный источник исчезает в PR 4D. */
    private val fixtureSet: DailySet = TestContent.set

    /** Считает обращения: «на пропуске головоломка не читается» иначе не проверить. */
    private class CountingPuzzles(private val delegate: PuzzleRepository) : PuzzleRepository {
        var calls = 0
        override suspend fun getPuzzle(puzzleId: String): Puzzle? {
            calls++
            return delegate.getPuzzle(puzzleId)
        }
    }

    /** Головоломок нет вовсе. */
    private object MissingPuzzles : PuzzleRepository {
        override suspend fun getPuzzle(puzzleId: String): Puzzle? = null
    }

    /**
     * Настройки записи: из всего контракта `SubmitAnswerUseCase` нужен только флаг первого
     * дня, и здесь он предметом проверки не является (он — в `SubmitAnswerUseCaseTest`).
     */
    private class SubmitPreferences(
        delegate: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    ) : UserPreferencesRepository by delegate {
        override suspend fun setHasCompletedFirstDay(completed: Boolean) = Unit
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

    private fun submitUseCase(puzzles: PuzzleRepository = InMemoryPuzzleRepository()) = SubmitAnswerUseCase(
        assignments = assignments,
        sets = DailySetRepositoryImpl(db.dailySetDao()),
        puzzles = puzzles,
        progress = progress,
        preferences = SubmitPreferences(),
    )

    /**
     * Каждый вызов даёт НОВЫЙ экземпляр: общего состояния между записью и чтением нет.
     * По умолчанию установлена версия 1 с отпечатком — отметка, которую пишет импортёр.
     */
    private fun loadUseCase(
        puzzles: PuzzleRepository = InMemoryPuzzleRepository(),
        installed: FakeUserPreferencesRepository = FakeUserPreferencesRepository(1, FINGERPRINT),
    ) = GetPuzzleResultUseCase(
        assignments = assignments,
        puzzles = puzzles,
        progress = progress,
        getInstalledContentVersion = GetInstalledContentVersionUseCase(installed),
    )

    /** Пул фикстуры, в котором головоломка слота 0 заменена [replacement] под тем же id. */
    private fun poolWithFirst(replacement: (Puzzle) -> Puzzle, extra: List<Puzzle> = emptyList()) =
        InMemoryPuzzleRepository(
            TestContent.puzzles.map { if (it.puzzleId == TestContent.FIRST_PUZZLE_ID) replacement(it) else it } + extra,
        )

    @Test
    fun `I3-U14 - a fresh use case instance restores the recorded result from the database`() = runTest {
        // Две инверсии относительно правильного порядка: 4 из 6.
        val submitted = correctOrderAt(0).let { listOf(it[1], it[0], it[3], it[2]) }
        val recorded = submitUseCase()(date, 0, Submission.Answer(submitted)) as SubmitResult.Recorded

        val load = loadUseCase()(date, 0)

        assertTrue("ожидался Content, получен $load", load is PuzzleResultLoad.Content)
        load as PuzzleResultLoad.Content
        assertEquals(0, load.slotIndex)
        assertEquals(fixtureSet.puzzleIdAt(0), load.puzzle.puzzleId)
        assertEquals(submitted, load.attempt.submittedOrder)
        assertEquals(recorded.score, load.attempt.score)
        assertEquals(recorded.score, load.scored.score)
        assertEquals(
            PairwiseScoreCalculator.evaluate(submitted, correctOrderAt(0)).invertedPairs,
            load.scored.invertedPairs,
        )
        assertFalse("активная головоломка не отозвана", load.isRetired)
    }

    @Test
    fun `I3-U15 - invertedPairs size is six minus the recorded score for every outcome`() = runTest {
        // Три слота одного дня — три разных исхода: 6, 5 и 0 из 6.
        val perturbations = listOf<(List<String>) -> List<String>>(
            { it },
            { listOf(it[1], it[0], it[2], it[3]) },
            { it.reversed() },
        )

        for (slot in 0..2) {
            val order = perturbations[slot](correctOrderAt(slot))
            submitUseCase()(date, slot, Submission.Answer(order))

            val load = loadUseCase()(date, slot) as PuzzleResultLoad.Content
            assertEquals(
                "слот $slot, порядок $order",
                PairwiseScoreCalculator.MAX_PER_PUZZLE - load.attempt.score,
                load.scored.invertedPairs.size,
            )
        }

        assertEquals(listOf(6, 5, 0), (0..2).map { requireNotNull(progress.getAttempt(date, it)).score })
    }

    @Test
    fun `I3-U36 - a skipped slot returns Skipped without ever calling the calculator`() = runTest {
        submitUseCase()(date, 1, Submission.Skip)
        val puzzles = CountingPuzzles(InMemoryPuzzleRepository())

        // Ни IllegalArgumentException из PairwiseScoreCalculator, ни обращения к контенту:
        // ветка Skipped стоит ДО них обоих.
        val load = loadUseCase(puzzles)(date, 1)

        assertEquals(PuzzleResultLoad.Skipped(1), load)
        assertEquals(0, puzzles.calls)
    }

    @Test
    fun `I3-U36 - a slot without an attempt is NoAttempt`() = runTest {
        val puzzles = CountingPuzzles(InMemoryPuzzleRepository())

        assertEquals(PuzzleResultLoad.NoAttempt(2), loadUseCase(puzzles)(date, 2))
        assertEquals(0, puzzles.calls)
    }

    // --- I5-R2: отзыв и четыре разных исхода ------------------------------------------

    @Test
    fun `I5-R2 - a retired puzzle is a full result marked as retired`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))
        val retired = poolWithFirst({ it.copy(retiredIn = 1, contentVersion = 1) })

        val load = loadUseCase(retired)(date, 0)

        assertTrue("отозванная — полный результат, получен $load", load is PuzzleResultLoad.Content)
        load as PuzzleResultLoad.Content
        assertTrue(load.isRetired)
        assertEquals(TestContent.FIRST_PUZZLE_ID, load.puzzle.puzzleId)
        assertEquals(PairwiseScoreCalculator.MAX_PER_PUZZLE, load.attempt.score)
        assertEquals(0, load.scored.invertedPairs.size)
    }

    @Test
    fun `I5-R2 - an active puzzle is not marked`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))

        val load = loadUseCase()(date, 0) as PuzzleResultLoad.Content

        assertFalse(load.isRetired)
    }

    @Test
    fun `I5-R2 - missing, invalid and skipped are three different outcomes`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))
        submitUseCase()(date, 1, Submission.Skip)
        val broken = poolWithFirst({ it.copy(correctOrder = emptyList()) })

        assertEquals(PuzzleResultLoad.Failure(PuzzleErrorKind.PuzzleNotFound), loadUseCase(MissingPuzzles)(date, 0))
        assertEquals(PuzzleResultLoad.Failure(PuzzleErrorKind.InvalidPuzzle), loadUseCase(broken)(date, 0))
        assertEquals(PuzzleResultLoad.Skipped(1), loadUseCase()(date, 1))
    }

    @Test
    fun `I5-R2 - an unknown installed version falls back to the row content version`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))
        val noStamp = FakeUserPreferencesRepository(contentVersion = 0, fingerprint = null)

        // retiredIn = 2 при отметке поставки 2: отзыв объявлен установленной когда-то версией.
        val retiredByRow = poolWithFirst({ it.copy(retiredIn = 2, contentVersion = 2) })
        assertTrue((loadUseCase(retiredByRow, noStamp)(date, 0) as PuzzleResultLoad.Content).isRetired)

        // retiredIn = 3 при отметке поставки 2: такого отзыва ни одна установка не объявляла.
        val notYet = poolWithFirst({ it.copy(retiredIn = 3, contentVersion = 2) })
        assertFalse((loadUseCase(notYet, noStamp)(date, 0) as PuzzleResultLoad.Content).isRetired)
    }

    @Test
    fun `I5-R2 - an installed version older than retiredIn does not mark the puzzle`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))
        // Откат приложения: строка более новой поставки осталась в таблице.
        val retiredInTwo = poolWithFirst({ it.copy(retiredIn = 2, contentVersion = 2) })

        val load = loadUseCase(retiredInTwo, FakeUserPreferencesRepository(1, FINGERPRINT))(date, 0)

        assertFalse((load as PuzzleResultLoad.Content).isRetired)
    }

    // --- I5-R4: исторический puzzleId ------------------------------------------------

    @Test
    fun `I5-R4 - replacing the slot in daily_sets does not replace the historical puzzle`() = runTest {
        submitUseCase()(date, 0, Submission.Answer(correctOrderAt(0)))

        // Следующая поставка отозвала головоломку слота 0 и поставила в слот замену
        // (I4-D4): daily_sets теперь указывает на другую головоломку.
        val replacement = TestContent.puzzle(REPLACEMENT_ID, category = Category.CULTURE, contentVersion = 2)
        db.dailySetDao().upsertAll(listOf(fixtureSet.copy(puzzleId1 = REPLACEMENT_ID).toEntity()))
        val pool = poolWithFirst({ it.copy(retiredIn = 2, contentVersion = 2) }, extra = listOf(replacement))

        val load = loadUseCase(pool, FakeUserPreferencesRepository(2, FINGERPRINT))(date, 0)

        load as PuzzleResultLoad.Content
        assertEquals("головоломка — из попытки, а не из набора", TestContent.FIRST_PUZZLE_ID, load.puzzle.puzzleId)
        assertEquals(TestContent.FIRST_PUZZLE_ID, load.attempt.puzzleId)
        assertEquals(Category.GEOGRAPHY, load.puzzle.category)
        assertTrue("исторический результат помечен как отозванный", load.isRetired)
        assertEquals(REPLACEMENT_ID, db.dailySetDao().getSet(packId, 0)!!.puzzleId1)
    }

    private companion object {
        const val REPLACEMENT_ID = "fix-zamena-900"
        const val FINGERPRINT = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
    }
}
