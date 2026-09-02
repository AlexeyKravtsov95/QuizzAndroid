package ru.poporyadku.ui.puzzle

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.poporyadku.core.model.Card
import ru.poporyadku.core.model.Category
import ru.poporyadku.core.model.ContentPack
import ru.poporyadku.core.model.DailySet
import ru.poporyadku.core.model.DayAssignment
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SortDirection
import ru.poporyadku.domain.assignment.DecisionContext
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.repository.AttemptAlreadyExistsException
import ru.poporyadku.domain.repository.DailySetRepository
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.PuzzleRepository

/**
 * Общая фикстура игровых ViewModel-тестов.
 *
 * `GetPuzzleUseCase`, `SubmitAnswerUseCase` и `GetPuzzleResultUseCase` — final-классы,
 * поэтому подменяются не они, а их репозитории: что и с какими аргументами ушло в
 * домен, видно по записям фейков.
 */
internal object PuzzleFixtures {

    const val PUZZLE_ID = "tmp-geo-vysota-001"
    const val PACK_ID = ContentPack.CORE_RU
    const val SET_INDEX = 0

    val date: LocalDate = LocalDate.of(2026, 9, 2)

    val cardIds = listOf("c1", "c2", "c3", "c4")

    /** correctOrder = c2, c1, c3, c4 — как у временного контента итерации 3. */
    val puzzle = Puzzle(
        puzzleId = PUZZLE_ID,
        packId = PACK_ID,
        category = Category.GEOGRAPHY,
        prompt = "Расположите вершины от самой низкой к самой высокой",
        sortKey = "height",
        sortDirection = SortDirection.ASCENDING,
        directionLabel = "Сверху — самая низкая",
        cards = listOf(
            card("c1", "Эльбрус", "Кавказ, Россия", 5642.0, "5642 м"),
            card("c2", "Монблан", "Альпы, Франция и Италия", 4808.0, "4808 м"),
            card("c3", "Килиманджаро", "Танзания", 5895.0, "5895 м"),
            card("c4", "Аконкагуа", "Анды, Аргентина", 6961.0, "6961 м"),
        ),
        correctOrder = listOf("c2", "c1", "c3", "c4"),
        explanation = "Монблан уступает Эльбрусу, а рекорд держит Аконкагуа.",
        sources = listOf(
            Puzzle.Source(
                sourceId = "s1",
                title = "Encyclopaedia Britannica",
                kind = "encyclopedia",
                url = "https://www.britannica.com/",
                reference = null,
                accessedAt = "2026-08-20",
                note = null,
            ),
        ),
        difficulty = 2,
        retiredIn = null,
        contentVersion = 1,
    )

    val dailySet = DailySet(
        packId = PACK_ID,
        setIndex = SET_INDEX,
        puzzleId1 = PUZZLE_ID,
        puzzleId2 = "tmp-hist-izobreteniya-002",
        puzzleId3 = "tmp-sci-otkrytiya-003",
    )

    fun assignment(localDate: LocalDate = date) = DayAssignment(
        localDate = localDate,
        packId = PACK_ID,
        setIndex = SET_INDEX,
        assignedAt = 0L,
    )

    fun attempt(
        localDate: LocalDate = date,
        slotIndex: Int = 0,
        submittedOrder: List<String> = listOf("c2", "c1", "c3", "c4"),
        score: Int = 6,
        puzzleId: String = PUZZLE_ID,
    ) = PuzzleAttempt(
        id = 1L,
        localDate = localDate,
        slotIndex = slotIndex,
        puzzleId = puzzleId,
        submittedOrder = submittedOrder,
        score = score,
        submittedAt = 0L,
    )

    private fun card(
        cardId: String,
        title: String,
        subtitle: String,
        sortValue: Double,
        displayValue: String,
    ) = Card(
        cardId = cardId,
        title = title,
        subtitle = subtitle,
        sortValue = sortValue,
        displayValue = displayValue,
        note = null,
        sourceIds = listOf("s1"),
        disputed = false,
    )
}

internal class FakeContentInstaller : ContentInstaller {
    var calls = 0
        private set

    override suspend fun ensureInstalled() {
        calls++
    }
}

internal class FakeAssignments(
    private var assignment: DayAssignment? = PuzzleFixtures.assignment(),
) : DayAssignmentRepository {

    val queries = mutableListOf<LocalDate>()

    /** Чем ответить вместо чтения; `null` — читать штатно. */
    var failWith: (() -> Throwable)? = null

    /**
     * Отмена скоупа посреди чтения. Отдельный флаг, а не [failWith]: тест обязан
     * убедиться, что `CancellationException` пробрасывается, а не становится `Error`.
     */
    var failCancellation: Boolean = false

    fun clear() {
        assignment = null
    }

    override suspend fun peek(): DecisionContext = error("не используется игровыми экранами")

    override suspend fun startSession(): DecisionContext = error("не используется игровыми экранами")

    override suspend fun getAssignment(localDate: LocalDate): DayAssignment? {
        if (failCancellation) throw kotlinx.coroutines.CancellationException("скоуп отменён")
        failWith?.let { throw it() }
        queries += localDate
        return assignment?.takeIf { it.localDate == localDate }
    }
}

internal class FakeSets(private var set: DailySet? = PuzzleFixtures.dailySet) : DailySetRepository {

    fun clear() {
        set = null
    }

    override suspend fun getSet(packId: String, setIndex: Int): DailySet? = set
}

internal class FakePuzzles(
    private val puzzles: MutableMap<String, Puzzle> = mutableMapOf(
        PuzzleFixtures.PUZZLE_ID to PuzzleFixtures.puzzle,
    ),
) : PuzzleRepository {

    fun remove(puzzleId: String) {
        puzzles.remove(puzzleId)
    }

    fun put(puzzle: Puzzle) {
        puzzles[puzzle.puzzleId] = puzzle
    }

    override suspend fun getPuzzle(puzzleId: String): Puzzle? = puzzles[puzzleId]
}

/**
 * Прогресс в памяти. Умеет то, что нужно тестам игрового цикла: подставить уже
 * записанную попытку, посчитать записи, задержать запись до явного разрешения и
 * бросить нужное исключение.
 */
internal class FakeProgress : ProgressRepository {

    val attempts = mutableMapOf<Pair<LocalDate, Int>, PuzzleAttempt>()
    val recorded = mutableListOf<PuzzleAttempt>()

    /** Что бросить вместо записи; `null` — записать штатно. */
    var failWith: (() -> Throwable)? = null

    /** Пока не `null`, запись ждёт вызова [release]. */
    private var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun blockRecording() {
        gate = kotlinx.coroutines.CompletableDeferred()
    }

    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    fun close(localDate: LocalDate, slotIndex: Int, submittedOrder: List<String>, score: Int = 0) {
        attempts[localDate to slotIndex] = PuzzleFixtures.attempt(
            localDate = localDate,
            slotIndex = slotIndex,
            submittedOrder = submittedOrder,
            score = score,
        )
    }

    override suspend fun recordAttempt(attempt: PuzzleAttempt) {
        gate?.await()
        failWith?.let { throw it() }
        val key = attempt.localDate to attempt.slotIndex
        if (attempts.containsKey(key)) {
            throw AttemptAlreadyExistsException(attempt.localDate, attempt.slotIndex)
        }
        attempts[key] = attempt
        recorded += attempt
    }

    override suspend fun getDayResult(localDate: LocalDate): DayResult? = null

    override suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult> = emptyList()

    override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int): PuzzleAttempt? =
        attempts[localDate to slotIndex]

    override suspend fun getAttempts(localDate: LocalDate): List<PuzzleAttempt> =
        attempts.filterKeys { it.first == localDate }.values.sortedBy { it.slotIndex }

    override suspend fun getAllDayResults(): List<DayResult> = emptyList()

    override suspend fun getCompletedDates(): List<LocalDate> = emptyList()

    override fun observeDayResults(): Flow<List<DayResult>> = emptyFlow()
}
