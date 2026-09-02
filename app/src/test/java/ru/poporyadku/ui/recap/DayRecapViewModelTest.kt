package ru.poporyadku.ui.recap

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.poporyadku.core.model.Card
import ru.poporyadku.core.model.Category
import ru.poporyadku.core.model.DayAssignment
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.model.SortDirection
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.core.time.DateProvider
import ru.poporyadku.domain.assignment.DecisionContext
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.PuzzleRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.usecase.GetDayRecapUseCase
import ru.poporyadku.domain.usecase.GetStreaksUseCase
import ru.poporyadku.ui.navigation.Destinations

/**
 * `DayRecapViewModel` — ITERATION_3_DESIGN.md, `I3-V34` (источник `today`, I3-D51).
 *
 * `GetDayRecapUseCase` — final-класс, поэтому подменяется не он, а его репозитории:
 * какие даты в него ушли, видно по записям фейков. `localDate` попадает в
 * `progress.getDayResult`/`getAttempts` и `assignments.getAssignment`, а `today` —
 * единственный параметр, который доезжает до `updateStreakCache`.
 */
class DayRecapViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Архивная дата D в маршруте. */
    private val archiveDate = LocalDate.of(2026, 8, 20)

    /** `DateProvider.today()` отдаёт D + 5. */
    private val currentDate = archiveDate.plusDays(5)

    private lateinit var assignments: RecordingAssignments
    private lateinit var progress: RecordingProgress
    private lateinit var puzzles: FakePuzzleRepository
    private lateinit var preferences: RecordingPreferences
    private lateinit var dateProvider: RecordingDateProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        assignments = RecordingAssignments()
        progress = RecordingProgress()
        puzzles = FakePuzzleRepository()
        preferences = RecordingPreferences()
        dateProvider = RecordingDateProvider(currentDate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * `I3-V34`. Маршрут несёт архивную дату D, `DateProvider` отдаёт D + 5:
     * use case вызывается ровно с `(localDate = D, today = D + 5)`, дата маршрута не
     * подменяется, `Content` корректно маппится, а `hasCompletedFirstDay`
     * выставляется на завершённом дне.
     */
    @Test
    fun `I3-V34 route date is never replaced by today`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 14)
        val viewModel = createViewModel(route = Destinations.serialize(archiveDate))

        viewModel.uiState.test {
            assertEquals(DayRecapState.Loading, awaitItem())
            val content = awaitItem() as DayRecapState.Content

            // Дата маршрута доехала до всех чтений дня без подмены.
            assertEquals(listOf(archiveDate), progress.dayResultQueries)
            assertEquals(listOf(archiveDate), progress.attemptQueries)
            assertEquals(listOf(archiveDate), assignments.assignmentQueries)

            // `today` — только из DateProvider, ровно одно чтение на загрузку.
            assertEquals(1, dateProvider.reads)
            assertEquals(currentDate, preferences.streakCacheDate)

            // Архивный день показывает дату, а не «Сегодня».
            assertEquals(DayRecapTitle.Date(archiveDate), content.title)
            assertEquals(14, content.totalScore)
            assertEquals(3, content.slots.size)

            // Завершённый день выставляет флаг для итерации 6.
            assertTrue(preferences.hasCompletedFirstDay == true)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Сегодняшний итог показывает заголовок «Сегодня». */
    @Test
    fun `today recap uses the Today title`() = runTest(dispatcher) {
        givenCompletedDay(currentDate, totalScore = 18)
        val viewModel = createViewModel(route = Destinations.serialize(currentDate))

        viewModel.uiState.test {
            skipItems(1)
            val content = awaitItem() as DayRecapState.Content
            assertEquals(DayRecapTitle.Today, content.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** `SlotOutcome.Unavailable` доносит ФАКТИЧЕСКИЙ счёт, а не константный ноль. */
    @Test
    fun `unavailable slot keeps its actual score`() = runTest(dispatcher) {
        progress.dayResult = DayResult(archiveDate, totalScore = 10, completedCount = 3, isComplete = true, completedAt = 1L)
        assignments.assignment = DayAssignment(archiveDate, PACK, setIndex = 2, assignedAt = 0L)
        progress.attempts = listOf(
            attempt(slot = 0, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 6),
            // Пропуск: порядок не отправлялся.
            attempt(slot = 1, puzzleId = "p2", order = emptyList(), score = 0),
            // Отвеченная головоломка, которую нечем показать: счёт обязан быть виден.
            attempt(slot = 2, puzzleId = "missing", order = listOf("a", "b", "c", "d"), score = 4),
        )
        val viewModel = createViewModel(route = Destinations.serialize(archiveDate))

        viewModel.uiState.test {
            skipItems(1)
            val content = awaitItem() as DayRecapState.Content
            assertEquals(
                listOf<SlotResultUi>(
                    SlotResultUi.Played(0, 6, Category.GEOGRAPHY),
                    SlotResultUi.Unavailable(1, 0),
                    SlotResultUi.Unavailable(2, 4),
                ),
                content.slots,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Дня нет в `day_results` → `NotFound`; флаг первого дня не выставляется. */
    @Test
    fun `missing day publishes NotFound`() = runTest(dispatcher) {
        val viewModel = createViewModel(route = Destinations.serialize(archiveDate))

        viewModel.uiState.test {
            skipItems(1)
            assertEquals(DayRecapState.NotFound, awaitItem())
            assertEquals(null, preferences.hasCompletedFirstDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Аргумент отсутствует — `NotFound`, а не подстановка сегодняшней даты. */
    @Test
    fun `absent route argument publishes NotFound without touching the date provider`() = runTest(dispatcher) {
        val viewModel = createViewModel(route = null)

        viewModel.uiState.test {
            assertEquals(DayRecapState.NotFound, awaitItem())
            runCurrent()
            assertEquals("подмены на «сегодня» нет", 0, dateProvider.reads)
            assertTrue(progress.dayResultQueries.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Повреждённый аргумент — тоже `NotFound`. */
    @Test
    fun `malformed route argument publishes NotFound`() = runTest(dispatcher) {
        val viewModel = createViewModel(route = "вчера")

        viewModel.uiState.test {
            assertEquals(DayRecapState.NotFound, awaitItem())
            runCurrent()
            assertEquals(0, dateProvider.reads)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** «Готово» создаёт ровно один `NavigateHome`. */
    @Test
    fun `done click produces a single NavigateHome effect`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 12)
        val viewModel = createViewModel(route = Destinations.serialize(archiveDate))

        viewModel.effects.test {
            viewModel.onEvent(DayRecapEvent.DoneClicked)
            runCurrent()
            assertEquals(DayRecapEffect.NavigateHome, awaitItem())
            expectNoEvents()
        }
    }

    /** Незавершённый день не выставляет `hasCompletedFirstDay`. */
    @Test
    fun `incomplete day does not set the first day flag`() = runTest(dispatcher) {
        progress.dayResult = DayResult(archiveDate, totalScore = 6, completedCount = 1, isComplete = false, completedAt = null)
        assignments.assignment = DayAssignment(archiveDate, PACK, setIndex = 1, assignedAt = 0L)
        progress.attempts = listOf(
            attempt(slot = 0, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 6),
        )
        val viewModel = createViewModel(route = Destinations.serialize(archiveDate))

        viewModel.uiState.test {
            skipItems(1)
            assertTrue(awaitItem() is DayRecapState.Content)
            assertEquals(null, preferences.hasCompletedFirstDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Маппер переносит `isRecordUpdated` без изменений и не считает его сам. */
    @Test
    fun `mapper carries isRecordUpdated as is`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 18)
        // Единственный завершённый день в истории — рекорд установлен именно им.
        progress.completedDates = listOf(archiveDate)
        val viewModel = createViewModel(route = Destinations.serialize(archiveDate))

        viewModel.uiState.test {
            skipItems(1)
            val content = awaitItem() as DayRecapState.Content
            assertTrue(content.isRecordUpdated)
            assertFalse(content.slots.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Инфраструктура --------------------------------------------------------------

    private fun createViewModel(route: String?): DayRecapViewModel {
        val handle = SavedStateHandle(
            if (route == null) emptyMap() else mapOf(Destinations.ARG_DATE to route),
        )
        return DayRecapViewModel(
            getDayRecap = GetDayRecapUseCase(
                assignments = assignments,
                puzzles = puzzles,
                progress = progress,
                streaks = GetStreaksUseCase(progress, preferences),
            ),
            preferences = preferences,
            dateProvider = dateProvider,
            savedStateHandle = handle,
        )
    }

    private fun givenCompletedDay(date: LocalDate, totalScore: Int) {
        progress.dayResult = DayResult(date, totalScore, completedCount = 3, isComplete = true, completedAt = 1L)
        assignments.assignment = DayAssignment(date, PACK, setIndex = 2, assignedAt = 0L)
        progress.attempts = listOf(
            attempt(slot = 0, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 6),
            attempt(slot = 1, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 4),
            attempt(slot = 2, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 4),
        )
    }

    private fun attempt(slot: Int, puzzleId: String, order: List<String>, score: Int) = PuzzleAttempt(
        id = slot.toLong(),
        localDate = archiveDate,
        slotIndex = slot,
        puzzleId = puzzleId,
        submittedOrder = order,
        score = score,
        submittedAt = 0L,
    )

    private companion object {
        const val PACK = "core-ru"
    }
}

// --- Фейки -------------------------------------------------------------------------

private class RecordingDateProvider(private val value: LocalDate) : DateProvider {
    var reads: Int = 0
        private set

    override fun today(): LocalDate {
        reads++
        return value
    }
}

private class RecordingAssignments : DayAssignmentRepository {
    var assignment: DayAssignment? = null
    val assignmentQueries = mutableListOf<LocalDate>()

    override suspend fun peek(): DecisionContext = unsupported()
    override suspend fun startSession(): DecisionContext = unsupported()

    override suspend fun getAssignment(localDate: LocalDate): DayAssignment? {
        assignmentQueries += localDate
        return assignment?.takeIf { it.localDate == localDate }
    }

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("DayRecap ничего не назначает")
}

private class RecordingProgress : ProgressRepository {
    var dayResult: DayResult? = null
    var attempts: List<PuzzleAttempt> = emptyList()
    var completedDates: List<LocalDate> = emptyList()

    val dayResultQueries = mutableListOf<LocalDate>()
    val attemptQueries = mutableListOf<LocalDate>()

    override suspend fun recordAttempt(attempt: PuzzleAttempt) =
        throw UnsupportedOperationException("DayRecap ничего не пишет")

    override suspend fun getDayResult(localDate: LocalDate): DayResult? {
        dayResultQueries += localDate
        return dayResult?.takeIf { it.localDate == localDate }
    }

    override suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult> = emptyList()

    override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int): PuzzleAttempt? = null

    override suspend fun getAttempts(localDate: LocalDate): List<PuzzleAttempt> {
        attemptQueries += localDate
        return attempts
    }

    override suspend fun getAllDayResults(): List<DayResult> = listOfNotNull(dayResult)

    override suspend fun getCompletedDates(): List<LocalDate> = completedDates

    override fun observeDayResults(): Flow<List<DayResult>> = emptyFlow()
}

/** Головоломка есть только под id `p1`; всё остальное недоступно. */
private class FakePuzzleRepository : PuzzleRepository {
    override suspend fun getPuzzle(puzzleId: String): Puzzle? =
        if (puzzleId == "p1") PLAYABLE else null

    private companion object {
        val PLAYABLE = Puzzle(
            puzzleId = "p1",
            packId = "core-ru",
            category = Category.GEOGRAPHY,
            prompt = "Расставьте вершины по высоте",
            sortKey = "height",
            sortDirection = SortDirection.DESCENDING,
            directionLabel = "Сверху — самая высокая",
            cards = listOf(card("a"), card("b"), card("c"), card("d")),
            correctOrder = listOf("a", "b", "c", "d"),
            explanation = "Высоты приведены по данным съёмок",
            sources = emptyList(),
            difficulty = 2,
            retiredIn = null,
            contentVersion = 1,
        )

        fun card(id: String) = Card(
            cardId = id,
            title = "Карточка $id",
            subtitle = null,
            sortValue = 1.0,
            displayValue = "1",
            note = null,
            sourceIds = emptyList(),
            disputed = false,
        )
    }
}

private class RecordingPreferences : UserPreferencesRepository {
    var streakCacheDate: LocalDate? = null
        private set
    var hasCompletedFirstDay: Boolean? = null
        private set

    override val preferences: Flow<UserPreferences> = emptyFlow()

    override suspend fun setSoundEnabled(enabled: Boolean) = unsupported()
    override suspend fun setVibrationEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderTime(time: LocalTime) = unsupported()
    override suspend fun setThemeMode(mode: ThemeMode) = unsupported()
    override suspend fun setStoredContentVersion(version: Int) = unsupported()
    override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
    override suspend fun setHasSeenScoringHint(seen: Boolean) = unsupported()

    override suspend fun setHasCompletedFirstDay(completed: Boolean) {
        hasCompletedFirstDay = completed
    }

    override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
    override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()

    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) {
        streakCacheDate = date
    }

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("не нужен в этом тесте")
}
