package ru.poporyadku.domain.usecase

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.DailySet
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.puzzleIdAt
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.content.temporary.BundledPuzzles
import ru.poporyadku.data.content.temporary.TemporaryPuzzleRepository
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.dao.AttemptDao
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.scoring.PairwiseScoreCalculator
import ru.poporyadku.domain.repository.PuzzleRepository

/**
 * ITERATION_3_DESIGN.md, §19: `I3-U12`–`I3-U17`, `I3-U26`, `I3-U27`, `I3-U30`–`I3-U32`, `I3-U35`.
 *
 * Гонки (`I3-U17`, `I3-U35`) синхронизируются барьером на общем чтении «есть ли уже
 * попытка»: обе корутины гарантированно проходят ранний рубеж до того, как любая из них
 * запишет. Ни задержек, ни надежды на порядок планировщика.
 */
@RunWith(RobolectricTestRunner::class)
class SubmitAnswerUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var clock: FakeClockProvider
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var assignments: DayAssignmentRepositoryImpl

    private val date = LocalDate.of(2026, 9, 1)
    private val zone = ZoneOffset.UTC
    private val packId = ContentPack.CORE_RU
    private val bundledSet: DailySet = BundledPuzzles.sets.first()

    /** Правильный порядок головоломки слота — ответ на 6 из 6. */
    private fun correctOrderAt(slotIndex: Int): List<String> {
        val puzzleId = bundledSet.puzzleIdAt(slotIndex)
        return BundledPuzzles.puzzles.first { it.puzzleId == puzzleId }.correctOrder
    }

    /** Пропускает стороны только все разом: ни задержек, ни расчёта на планировщик. */
    private class Barrier(private val parties: Int) {
        private val mutex = Mutex()
        private val waiting = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun await() {
            val mine = CompletableDeferred<Unit>()
            val release = mutex.withLock {
                waiting += mine
                if (waiting.size == parties) waiting.toList().also { waiting.clear() } else null
            }
            release?.forEach { it.complete(Unit) }
            mine.await()
        }
    }

    /**
     * Держит обе корутины на пороге записи. Пока барьер не собрал [gatedCalls] сторон,
     * ни одна строка не вставлена, поэтому ранние рубежи обеих корутин заведомо уже
     * пройдены и настоящую гонку разрешает только UNIQUE(local_date, slot_index).
     *
     * Барьер стоит на записи, а не на чтении попытки: тогда сценарий остаётся настоящей
     * гонкой и при временно убранном раннем рубеже — именно это проверяет разовая
     * проверка «сломай и убедись» из критериев готовности PR 3B.
     */
    private class GatedProgress(
        private val delegate: ProgressRepository,
        private val barrier: Barrier,
        private val gatedCalls: Int,
    ) : ProgressRepository by delegate {
        private val seen = AtomicInteger(0)

        override suspend fun recordAttempt(attempt: PuzzleAttempt) {
            if (seen.incrementAndGet() <= gatedCalls) barrier.await()
            delegate.recordAttempt(attempt)
        }
    }

    /**
     * Ослепляет ровно [blindCalls] первых чтений попытки: ранний рубеж перестаёт видеть
     * уже записанную строку, и вторая запись доходит до базы. Так проверяется, что
     * единственность строки держит `OnConflictStrategy.ABORT`, а не только рубеж use case.
     */
    private class BlindProgress(
        private val delegate: ProgressRepository,
        private val blindCalls: Int,
    ) : ProgressRepository by delegate {
        private val seen = AtomicInteger(0)

        override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int): PuzzleAttempt? =
            if (seen.incrementAndGet() <= blindCalls) null else delegate.getAttempt(localDate, slotIndex)
    }

    /** Нарушение ограничения, не связанное с уникальностью слота (I3-U26). */
    private class ThrowingAttemptDao(private val delegate: AttemptDao) : AttemptDao by delegate {
        override suspend fun insert(attempt: PuzzleAttemptEntity): Long =
            throw SQLiteConstraintException("NOT NULL constraint failed: puzzle_attempts.some_future_column")
    }

    private class FixedPuzzles(private val puzzle: Puzzle?) : PuzzleRepository {
        var calls = 0
        override suspend fun getPuzzle(puzzleId: String): Puzzle? {
            calls++
            return puzzle
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClockProvider(Clock.fixed(date.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone))
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        assignments = DayAssignmentRepositoryImpl(db, db.assignmentDao(), db.dailySetDao(), clock, packId)
        runBlocking {
            db.dailySetDao().upsertAll(listOf(bundledSet.toEntity()))
            db.assignmentDao().insert(DayAssignmentEntity(date.toString(), packId, 0, assignedAt = 1L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun useCase(
        puzzles: PuzzleRepository = TemporaryPuzzleRepository(),
        progressRepository: ProgressRepository = progress,
    ) = SubmitAnswerUseCase(
        assignments = assignments,
        sets = DailySetRepositoryImpl(db.dailySetDao()),
        puzzles = puzzles,
        progress = progressRepository,
    )

    private suspend fun rows() = db.attemptDao().getByDate(date.toString())

    @Test
    fun `I3-U12 - a submit writes exactly one row and a repeat does not overwrite it`() = runTest {
        val first = useCase()(date, 0, Submission.Answer(correctOrderAt(0)))
        assertEquals(SubmitResult.Recorded(0, 6, AttemptKind.Answered), first)
        assertEquals(1, rows().size)

        // Другой порядок, тот же слот: перезаписи быть не должно.
        val wrong = correctOrderAt(0).reversed()
        val second = useCase()(date, 0, Submission.Answer(wrong))

        assertEquals(SubmitResult.AlreadyClosed(0, AttemptKind.Answered), second)
        assertEquals(1, rows().size)
        assertEquals(6, rows().single().score)
        assertEquals(correctOrderAt(0).joinToString(","), rows().single().submittedOrder)

        // И то же самое, когда ранний рубеж строки НЕ видит: вторая запись доходит до
        // базы и отбивается ограничением, а не проверкой в use case.
        val blind = useCase(progressRepository = BlindProgress(progress, blindCalls = 1))(
            date, 0, Submission.Answer(wrong),
        )

        assertEquals(SubmitResult.AlreadyClosed(0, AttemptKind.Answered), blind)
        assertEquals(1, rows().size)
        assertEquals(6, rows().single().score)
        assertEquals(correctOrderAt(0).joinToString(","), rows().single().submittedOrder)
    }

    @Test
    fun `I3-U13 - a full day through the use case gives 18 of 18`() = runTest {
        repeat(3) { slot ->
            assertEquals(
                SubmitResult.Recorded(slot, 6, AttemptKind.Answered),
                useCase()(date, slot, Submission.Answer(correctOrderAt(slot))),
            )
        }

        val result = requireNotNull(progress.getDayResult(date))
        assertEquals(18, result.totalScore)
        assertEquals(3, result.completedCount)
        assertTrue(result.isComplete)
        assertNotNull(result.completedAt)
    }

    @Test
    fun `I3-U14, I3-U15 - what Submit stored is exactly what the result screen reads back`() = runTest {
        // Ответ с двумя инверсиями: 4 из 6.
        val order = correctOrderAt(0).let { listOf(it[1], it[0], it[3], it[2]) }

        val recorded = useCase()(date, 0, Submission.Answer(order)) as SubmitResult.Recorded

        val stored = requireNotNull(progress.getAttempt(date, 0))
        assertEquals(order, stored.submittedOrder)
        assertEquals(recorded.score, stored.score)
        val scored = PairwiseScoreCalculator.evaluate(stored.submittedOrder, correctOrderAt(0))
        assertEquals(stored.score, scored.score)
        assertEquals(PairwiseScoreCalculator.MAX_PER_PUZZLE - stored.score, scored.invertedPairs.size)
        // Метку времени ставит репозиторий: переданное use case значение до базы не доезжает.
        assertEquals(clock.now().epochMillis, stored.submittedAt)
    }

    @Test
    fun `I3-U16 - an empty submittedOrder survives the round trip as an empty list`() = runTest {
        useCase()(date, 0, Submission.Skip)

        assertEquals("", rows().single().submittedOrder)
        val restored = requireNotNull(progress.getAttempt(date, 0))
        assertEquals(emptyList<String>(), restored.submittedOrder)
        assertEquals(AttemptKind.Skipped, AttemptKind.of(restored))
    }

    @Test
    fun `I3-U17 - two racing submits leave one row and one AlreadyClosed`() {
        // runBlocking, а не runTest: обе корутины ждут настоящих ответов Room, и
        // виртуальное время тестового планировщика проматывалось бы мимо них.
        runBlocking {
            val barrier = Barrier(parties = 2)
            val gated = GatedProgress(progress, barrier, gatedCalls = 2)
            val order = correctOrderAt(0)

            val results = listOf(
                async(Dispatchers.Default) { useCase(progressRepository = gated)(date, 0, Submission.Answer(order)) },
                async(Dispatchers.Default) { useCase(progressRepository = gated)(date, 0, Submission.Answer(order)) },
            ).awaitAll()

            assertEquals(1, results.count { it is SubmitResult.Recorded })
            assertEquals(1, results.count { it is SubmitResult.AlreadyClosed })
            assertEquals(1, rows().size)

            // day_results не испорчен откатившейся транзакцией.
            val day = requireNotNull(progress.getDayResult(date))
            assertEquals(1, day.completedCount)
            assertEquals(6, day.totalScore)
            assertEquals(1, db.dayResultDao().observeAll().first().size)
        }
    }

    @Test
    fun `I3-U26 - an unrelated constraint failure is not mistaken for a repeat`() = runTest {
        val throwing = ProgressRepositoryImpl(db, ThrowingAttemptDao(db.attemptDao()), db.dayResultDao(), clock)

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                useCase(progressRepository = throwing)(date, 0, Submission.Answer(correctOrderAt(0)))
            }
        }
        assertEquals(0, rows().size)
    }

    @Test
    fun `I3-U27 - a Skip without its DailySet writes nothing`() = runTest {
        db.dailySetDao().deleteOutside(packId, keep = listOf(99))
        assertEquals(0, db.dailySetDao().setIndexes(packId).size)

        val result = useCase()(date, 0, Submission.Skip)

        assertEquals(SubmitResult.Failure(PuzzleErrorKind.SetNotFound), result)
        assertEquals(0, rows().size)
    }

    @Test
    fun `I3-U30 - a Skip records zero without ever touching PuzzleRepository`() = runTest {
        val puzzles = FixedPuzzles(null) // PuzzleNotFound, если бы к нему обратились

        val result = useCase(puzzles)(date, 1, Submission.Skip)

        assertEquals(SubmitResult.Recorded(1, 0, AttemptKind.Skipped), result)
        assertEquals(0, puzzles.calls)
        val stored = rows().single()
        assertEquals(bundledSet.puzzleIdAt(1), stored.puzzleId) // puzzleId — из DailySet
        assertEquals("", stored.submittedOrder)
        assertEquals(0, stored.score)
    }

    @Test
    fun `I3-U31 - a broken puzzle does not block a Skip either`() = runTest {
        val broken = BundledPuzzles.puzzles.first().let { it.copy(correctOrder = emptyList()) }
        val puzzles = FixedPuzzles(broken)

        val result = useCase(puzzles)(date, 2, Submission.Skip)

        assertEquals(SubmitResult.Recorded(2, 0, AttemptKind.Skipped), result)
        assertEquals(0, puzzles.calls)
        // А ответ на ту же головоломку отвергается формой — ветки действительно разные.
        assertEquals(
            SubmitResult.Failure(PuzzleErrorKind.InvalidPuzzle),
            useCase(FixedPuzzles(broken))(date, 0, Submission.Answer(correctOrderAt(0))),
        )
    }

    @Test
    fun `I3-U32 - a repeated Skip keeps the first record intact`() = runTest {
        useCase()(date, 0, Submission.Skip)

        val second = useCase()(date, 0, Submission.Skip)

        assertEquals(SubmitResult.AlreadyClosed(0, AttemptKind.Skipped), second)
        assertEquals(1, rows().size)
        assertEquals("", rows().single().submittedOrder)
        assertEquals(0, rows().single().score)
    }

    @Test
    fun `I3-U35 - Answer racing Skip is resolved by the stored record, not by intent`() {
        runBlocking {
            val barrier = Barrier(parties = 2)
            val gated = GatedProgress(progress, barrier, gatedCalls = 2)

            val results = listOf(
                async(Dispatchers.Default) {
                    useCase(progressRepository = gated)(date, 0, Submission.Answer(correctOrderAt(0)))
                },
                async(Dispatchers.Default) {
                    useCase(progressRepository = gated)(date, 0, Submission.Skip)
                },
            ).awaitAll()

            assertEquals(1, rows().size)
            val stored = requireNotNull(progress.getAttempt(date, 0))
            val winner = AttemptKind.of(stored)

            // ОБА исхода говорят про то, что реально записано: проигравшая корутина
            // не навигирует по своему намерению.
            val kinds = results.map {
                when (it) {
                    is SubmitResult.Recorded -> it.kind
                    is SubmitResult.AlreadyClosed -> it.kind
                    is SubmitResult.Failure -> error("неожиданный отказ: $it")
                }
            }
            assertEquals(listOf(winner, winner), kinds)
            assertEquals(1, results.count { it is SubmitResult.Recorded })
            assertEquals(1, results.count { it is SubmitResult.AlreadyClosed })
            assertEquals(stored.score, (results.first { it is SubmitResult.Recorded } as SubmitResult.Recorded).score)
        }
    }
}
