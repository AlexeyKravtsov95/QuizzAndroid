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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.DailySet
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.core.model.InMemoryPuzzleRepository
import ru.poporyadku.core.model.TestContent
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.scoring.PairwiseScoreCalculator

/**
 * ITERATION_3_DESIGN.md, §19: `I3-U14`, `I3-U15`, `I3-U36`.
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

    private fun submitUseCase() = SubmitAnswerUseCase(
        assignments = assignments,
        sets = DailySetRepositoryImpl(db.dailySetDao()),
        puzzles = InMemoryPuzzleRepository(),
        progress = progress,
    )

    /** Каждый вызов даёт НОВЫЙ экземпляр: общего состояния между записью и чтением нет. */
    private fun loadUseCase(puzzles: PuzzleRepository = InMemoryPuzzleRepository()) = GetPuzzleResultUseCase(
        assignments = assignments,
        puzzles = puzzles,
        progress = progress,
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
}
