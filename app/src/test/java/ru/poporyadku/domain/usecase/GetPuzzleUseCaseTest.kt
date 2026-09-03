package ru.poporyadku.domain.usecase

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
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
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.InMemoryPuzzleRepository
import ru.poporyadku.core.model.TestContent
import ru.poporyadku.core.time.FakeClockProvider
import ru.poporyadku.data.db.AppDatabase
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.mapper.toEntity
import ru.poporyadku.data.progress.ProgressRepositoryImpl
import ru.poporyadku.data.repository.DailySetRepositoryImpl
import ru.poporyadku.data.repository.DayAssignmentRepositoryImpl
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.shuffle.DeterministicShuffler

/**
 * ITERATION_3_DESIGN.md, §19: `I3-U18`, `I3-U19`, `I3-U33`, `I3-U34`.
 *
 * Установщик контента подменён на пустышку: этот тест ставит наборы и назначения
 * руками, в том числе заведомо сломанные, а `TemporaryContentInstaller` объявил бы их
 * конфликтом. Его собственное поведение проверяет `TemporaryContentInstallerTest`.
 */
@RunWith(RobolectricTestRunner::class)
class GetPuzzleUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var progress: ProgressRepositoryImpl
    private lateinit var assignments: DayAssignmentRepositoryImpl

    private val date = LocalDate.of(2026, 9, 1)
    private val zone = ZoneOffset.UTC
    private val packId = ContentPack.CORE_RU

    private object NoopInstaller : ContentInstaller {
        override suspend fun ensureInstalled() = Unit
    }

    /** Считает обращения: «слот закрыт — головоломка не читается» иначе не проверить. */
    private class CountingPuzzles(private val delegate: PuzzleRepository) : PuzzleRepository {
        var calls = 0
        override suspend fun getPuzzle(puzzleId: String): Puzzle? {
            calls++
            return delegate.getPuzzle(puzzleId)
        }
    }

    private class FixedPuzzles(private val puzzle: Puzzle?) : PuzzleRepository {
        override suspend fun getPuzzle(puzzleId: String): Puzzle? = puzzle
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = FakeClockProvider(Clock.fixed(date.atTime(LocalTime.NOON).atZone(zone).toInstant(), zone))
        progress = ProgressRepositoryImpl(db, db.attemptDao(), db.dayResultDao(), clock)
        assignments = DayAssignmentRepositoryImpl(
            db, db.assignmentDao(), db.dailySetDao(), clock, packId,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun useCase(puzzles: PuzzleRepository = InMemoryPuzzleRepository()) = GetPuzzleUseCase(
        content = NoopInstaller,
        assignments = assignments,
        sets = DailySetRepositoryImpl(db.dailySetDao()),
        puzzles = puzzles,
        progress = progress,
    )

    private suspend fun assign(setIndex: Int) {
        db.assignmentDao().insert(
            DayAssignmentEntity(date.toString(), packId, setIndex, assignedAt = 1L)
        )
    }

    private suspend fun seedSet(setIndex: Int, set: DailySet) {
        db.dailySetDao().upsertAll(listOf(set.copy(setIndex = setIndex).toEntity()))
    }

    /** Набор 0 независимой фикстуры (I4-D22): временный источник тут ни при чём. */
    private suspend fun seedFixtureSet() = seedSet(0, TestContent.set)

    private suspend fun closeSlot(slotIndex: Int, order: List<String>) {
        progress.recordAttempt(
            PuzzleAttempt(
                id = 0,
                localDate = date,
                slotIndex = slotIndex,
                puzzleId = TestContent.FIRST_PUZZLE_ID,
                submittedOrder = order,
                score = if (order.isEmpty()) 0 else 6,
                submittedAt = 0L,
            )
        )
    }

    @Test
    fun `I3-U18 - a closed slot redirects even when its puzzle cannot be loaded`() = runTest {
        assign(setIndex = 0)
        seedFixtureSet()
        closeSlot(0, listOf("c2", "c1", "c3", "c4"))

        // Головоломки нет вовсе: проверка отвеченности стоит ДО загрузки набора и Puzzle.
        val puzzles = CountingPuzzles(FixedPuzzles(null))
        val result = useCase(puzzles)(date, 0)

        assertEquals(GetPuzzleResult.AlreadyClosed(0, AttemptKind.Answered), result)
        assertEquals(0, puzzles.calls)
    }

    @Test
    fun `I3-U19 - a playable slot returns the puzzle and a deterministic start order`() = runTest {
        assign(setIndex = 0)
        seedFixtureSet()

        val result = useCase()(date, 0)

        assertTrue("ожидался Playable, получен $result", result is GetPuzzleResult.Playable)
        result as GetPuzzleResult.Playable
        assertEquals(TestContent.FIRST_PUZZLE_ID, result.puzzle.puzzleId)
        assertEquals(0, result.setIndex)
        assertEquals(
            DeterministicShuffler.shuffle(
                TestContent.FIRST_PUZZLE_ID,
                result.puzzle.cards.map { it.cardId },
            ),
            result.startOrder,
        )
    }

    @Test
    fun `I3-U19 - slotIndex out of range is rejected before anything is read`() = runTest {
        assign(setIndex = 0)
        seedFixtureSet()

        assertEquals(GetPuzzleResult.Failure(PuzzleErrorKind.SlotOutOfRange), useCase()(date, 3))
        assertEquals(GetPuzzleResult.Failure(PuzzleErrorKind.SlotOutOfRange), useCase()(date, -1))
    }

    @Test
    fun `I3-U19 - a date without an assignment is NoAssignment`() = runTest {
        seedFixtureSet()

        assertEquals(GetPuzzleResult.Failure(PuzzleErrorKind.NoAssignment), useCase()(date, 0))
    }

    @Test
    fun `I3-U19 - an assignment without its set is SetNotFound`() = runTest {
        assign(setIndex = 7) // назначение есть, строки набора под него нет

        assertEquals(GetPuzzleResult.Failure(PuzzleErrorKind.SetNotFound), useCase()(date, 0))
    }

    @Test
    fun `I3-U19 - an unknown puzzleId is PuzzleNotFound`() = runTest {
        assign(setIndex = 0)
        seedSet(0, DailySet(packId, 0, "нет-такой-1", "нет-такой-2", "нет-такой-3"))

        assertEquals(GetPuzzleResult.Failure(PuzzleErrorKind.PuzzleNotFound), useCase()(date, 0))
    }

    @Test
    fun `I3-U19 - a puzzle failing the shape check is InvalidPuzzle`() = runTest {
        assign(setIndex = 0)
        seedFixtureSet()
        val broken = TestContent.puzzles.first().let { it.copy(cards = it.cards.drop(1)) }

        assertEquals(
            GetPuzzleResult.Failure(PuzzleErrorKind.InvalidPuzzle),
            useCase(FixedPuzzles(broken))(date, 0),
        )
    }

    @Test
    fun `I3-U33 - a slot closed by Skip in this session never leads to PuzzleResult`() = runTest {
        assign(setIndex = 0)
        seedFixtureSet()
        // Ровно то, что пишет SubmitAnswerUseCase на Skip: пустой порядок, ноль баллов.
        closeSlot(0, emptyList())

        assertEquals(GetPuzzleResult.AlreadyClosed(0, AttemptKind.Skipped), useCase()(date, 0))
    }

    @Test
    fun `I3-U34 - opening a skipped slot directly gives the same classification`() = runTest {
        assign(setIndex = 0)
        seedFixtureSet()
        // Записи в этой сессии не было: попытка уже лежит в базе, как после смерти процесса.
        db.attemptDao().insert(
            PuzzleAttempt(
                id = 0,
                localDate = date,
                slotIndex = 2,
                puzzleId = TestContent.THIRD_PUZZLE_ID,
                submittedOrder = emptyList(),
                score = 0,
                submittedAt = 1L,
            ).toEntity()
        )
        db.attemptDao().insert(
            PuzzleAttempt(
                id = 0,
                localDate = date,
                slotIndex = 1,
                puzzleId = TestContent.SECOND_PUZZLE_ID,
                submittedOrder = listOf("c2", "c4", "c1", "c3"),
                score = 6,
                submittedAt = 1L,
            ).toEntity()
        )

        assertEquals(GetPuzzleResult.AlreadyClosed(2, AttemptKind.Skipped), useCase()(date, 2))
        assertEquals(GetPuzzleResult.AlreadyClosed(1, AttemptKind.Answered), useCase()(date, 1))
    }
}
