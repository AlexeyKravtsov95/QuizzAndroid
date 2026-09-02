package ru.poporyadku.ui.puzzleresult

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.poporyadku.core.model.StreakCache
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.usecase.GetPuzzleResultUseCase
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.puzzle.FakeAssignments
import ru.poporyadku.ui.puzzle.FakeProgress
import ru.poporyadku.ui.puzzle.FakePuzzles
import ru.poporyadku.ui.puzzle.PuzzleFixtures

/**
 * `PuzzleResultViewModel` — ITERATION_3_DESIGN.md, `I3-V16` плюс все четыре исхода
 * `PuzzleResultLoad` и навигация последнего слота.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PuzzleResultViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var assignments: FakeAssignments
    private lateinit var puzzles: FakePuzzles
    private lateinit var progress: FakeProgress
    private lateinit var preferences: FakePreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        assignments = FakeAssignments()
        puzzles = FakePuzzles()
        progress = FakeProgress()
        preferences = FakePreferences()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- I3-V16 -----------------------------------------------------------------------

    /**
     * `I3-V16`. Первый в жизни результат показывает подсказку и сразу выставляет флаг:
     * убийство процесса на этом же экране не должно показать её второй раз.
     */
    @Test
    fun `I3-V16 first result shows the scoring hint and sets the flag`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c1", "c2", "c3", "c4"), score = 5)
        val viewModel = createViewModel(slotIndex = 0)
        advanceUntilIdle()

        val content = viewModel.uiState.value as PuzzleResultState.Content
        assertTrue("первый результат обязан показать подсказку", content.showScoringHint)
        assertEquals(listOf(true), preferences.scoringHintWrites)
    }

    /** `I3-V16`. Следующий результат подсказку не показывает и флаг заново не пишет. */
    @Test
    fun `I3-V16 next result does not show the hint again`() = runTest(dispatcher) {
        preferences.setSeen()
        givenAnsweredSlot(slotIndex = 1, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val viewModel = createViewModel(slotIndex = 1)
        advanceUntilIdle()

        val content = viewModel.uiState.value as PuzzleResultState.Content
        assertFalse(content.showScoringHint)
        assertTrue("повторной записи флага быть не должно", preferences.scoringHintWrites.isEmpty())
    }

    // --- Content ----------------------------------------------------------------------

    /**
     * `Content` собирается из сохранённой попытки и головоломки: счёт — из попытки,
     * пары — из пересчёта, правильный порядок — из `correctOrder` со значениями карточек.
     */
    @Test
    fun `content is restored from the stored attempt`() = runTest(dispatcher) {
        // Ответ обратный правильному: ноль баллов и все шесть пар.
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c4", "c3", "c1", "c2"), score = 0)
        val viewModel = createViewModel(slotIndex = 0)
        advanceUntilIdle()

        val content = viewModel.uiState.value as PuzzleResultState.Content
        assertEquals(listOf("c2", "c1", "c3", "c4"), content.correctOrder.map { it.cardId })
        assertEquals(listOf(1, 2, 3, 4), content.correctOrder.map { it.position })
        assertEquals("4808 м", content.correctOrder.first().displayValue)
        assertEquals(listOf("c4", "c3", "c1", "c2"), content.submittedOrder)
        assertEquals(0, content.score)
        assertEquals(MAX_PAIRS, content.invertedPairs.size)
        assertEquals(PuzzleFixtures.PUZZLE_ID, content.puzzleId)
        assertFalse(content.isLastSlot)
        assertEquals(PuzzleFixtures.puzzle.sources, content.sources)
    }

    /** Инвариант `invertedPairs.size == 6 − score` держится и на частичном ответе. */
    @Test
    fun `inverted pairs count matches the lost points`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c1", "c2", "c3", "c4"), score = 5)
        val viewModel = createViewModel(slotIndex = 0)
        advanceUntilIdle()

        val content = viewModel.uiState.value as PuzzleResultState.Content
        assertEquals(MAX_PAIRS - content.score, content.invertedPairs.size)
    }

    /** Последний слот помечается `isLastSlot`, и CTA ведёт в итог дня. */
    @Test
    fun `last slot leads to the day recap`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 2, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val viewModel = createViewModel(slotIndex = 2)
        advanceUntilIdle()

        assertTrue((viewModel.uiState.value as PuzzleResultState.Content).isLastSlot)

        viewModel.effects.test {
            viewModel.onEvent(PuzzleResultEvent.PrimaryAction)
            assertEquals(PuzzleResultEffect.NavigateToRecap, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Слоты 0–1 ведут в следующую головоломку. */
    @Test
    fun `non last slot leads to the next puzzle`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 1, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val viewModel = createViewModel(slotIndex = 1)
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onEvent(PuzzleResultEvent.PrimaryAction)
            assertEquals(PuzzleResultEffect.NavigateToNextSlot(2), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Кнопка «Назад» в шапке ведёт на Home, а не в отвеченную головоломку. */
    @Test
    fun `back returns home`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 1, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val viewModel = createViewModel(slotIndex = 1)
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onEvent(PuzzleResultEvent.BackPressed)
            assertEquals(PuzzleResultEffect.NavigateHome, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Skipped / NoAttempt / Failure --------------------------------------------------

    /** Пропущенный слот кадра не показывает: сразу следующий слот. */
    @Test
    fun `skipped slot redirects without a frame`() = runTest(dispatcher) {
        progress.close(PuzzleFixtures.date, slotIndex = 0, submittedOrder = emptyList())
        val viewModel = createViewModel(slotIndex = 0)
        advanceUntilIdle()

        assertEquals(PuzzleResultState.Loading, viewModel.uiState.value)
        viewModel.effects.test {
            assertEquals(PuzzleResultEffect.NavigateToNextSlot(1), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Пропуск последнего слота ведёт в итог дня, а не в результат. */
    @Test
    fun `skipped last slot redirects to the recap`() = runTest(dispatcher) {
        progress.close(PuzzleFixtures.date, slotIndex = 2, submittedOrder = emptyList())
        val viewModel = createViewModel(slotIndex = 2)
        advanceUntilIdle()

        viewModel.effects.test {
            assertEquals(PuzzleResultEffect.NavigateToRecap, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Попытки нет — слот ещё не сыгран: возвращаемся в головоломку. */
    @Test
    fun `missing attempt returns to the puzzle`() = runTest(dispatcher) {
        val viewModel = createViewModel(slotIndex = 1)
        advanceUntilIdle()

        assertEquals(PuzzleResultState.Loading, viewModel.uiState.value)
        viewModel.effects.test {
            assertEquals(PuzzleResultEffect.NavigateToPuzzle(1), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Головоломка недоступна — экран ошибки, а не падение. */
    @Test
    fun `failure becomes an error state`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        puzzles.remove(PuzzleFixtures.PUZZLE_ID)
        val viewModel = createViewModel(slotIndex = 0)
        advanceUntilIdle()

        assertEquals(
            PuzzleResultState.Error(PuzzleErrorKind.PuzzleNotFound),
            viewModel.uiState.value,
        )
    }

    /** Отказ хранилища даёт `Storage`, а `CancellationException` — не даёт ничего. */
    @Test
    fun `storage failure becomes an error and cancellation does not`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        assignments.failWith = { IllegalStateException("база недоступна") }
        val failing = createViewModel(slotIndex = 0)
        advanceUntilIdle()
        assertEquals(PuzzleResultState.Error(PuzzleErrorKind.Storage), failing.uiState.value)

        setUp()
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        assignments.failCancellation = true
        val cancelled = createViewModel(slotIndex = 0)
        advanceUntilIdle()
        assertEquals(PuzzleResultState.Loading, cancelled.uiState.value)
    }

    // --- Маршрут -----------------------------------------------------------------------

    /** Невалидный маршрут: `InvalidRoute` и немедленный возврат на Home. */
    @Test
    fun `invalid route returns home without touching the use case`() = runTest(dispatcher) {
        val viewModel = PuzzleResultViewModel(
            getPuzzleResult = GetPuzzleResultUseCase(assignments, puzzles, progress),
            preferences = preferences,
            savedStateHandle = SavedStateHandle(mapOf(Destinations.ARG_SLOT_INDEX to 0)),
        )
        advanceUntilIdle()

        assertEquals(
            PuzzleResultState.Error(PuzzleErrorKind.InvalidRoute),
            viewModel.uiState.value,
        )
        viewModel.effects.test {
            assertEquals(PuzzleResultEffect.NavigateHome, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(assignments.queries.isEmpty())
    }

    // --- Инфраструктура -----------------------------------------------------------------

    private fun givenAnsweredSlot(slotIndex: Int, submittedOrder: List<String>, score: Int) {
        progress.close(PuzzleFixtures.date, slotIndex, submittedOrder, score)
    }

    private fun createViewModel(slotIndex: Int) = PuzzleResultViewModel(
        getPuzzleResult = GetPuzzleResultUseCase(assignments, puzzles, progress),
        preferences = preferences,
        savedStateHandle = SavedStateHandle(
            mapOf(
                Destinations.ARG_SLOT_INDEX to slotIndex,
                Destinations.ARG_DATE to Destinations.serialize(PuzzleFixtures.date),
            ),
        ),
    )

    private companion object {
        /** C(4,2) — столько пар у четырёх карточек. */
        const val MAX_PAIRS = 6
    }
}

/** Настройки в памяти: важны только флаг подсказки и факт его записи. */
private class FakePreferences : UserPreferencesRepository {

    val scoringHintWrites = mutableListOf<Boolean>()

    private val state = MutableStateFlow(
        UserPreferences(
            soundEnabled = true,
            vibrationEnabled = true,
            reminderEnabled = false,
            reminderTime = LocalTime.of(9, 0),
            themeMode = ThemeMode.SYSTEM,
            storedContentVersion = 1,
            hasSeenDragHint = false,
            hasSeenScoringHint = false,
            hasCompletedFirstDay = false,
            notificationPromptShown = false,
            lastSeenDate = null,
            streakCache = StreakCache.EMPTY,
        ),
    )

    override val preferences: Flow<UserPreferences> = state

    fun setSeen() {
        state.value = state.value.copy(hasSeenScoringHint = true)
    }

    override suspend fun setHasSeenScoringHint(seen: Boolean) {
        scoringHintWrites += seen
        state.value = state.value.copy(hasSeenScoringHint = seen)
    }

    override suspend fun setSoundEnabled(enabled: Boolean) = unsupported()
    override suspend fun setVibrationEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderTime(time: LocalTime) = unsupported()
    override suspend fun setThemeMode(mode: ThemeMode) = unsupported()
    override suspend fun setStoredContentVersion(version: Int) = unsupported()
    override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
    override suspend fun setHasCompletedFirstDay(completed: Boolean) = unsupported()
    override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
    override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()
    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("не нужен в этом тесте")
}
