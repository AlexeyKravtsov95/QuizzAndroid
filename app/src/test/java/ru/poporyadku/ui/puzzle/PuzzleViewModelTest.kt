package ru.poporyadku.ui.puzzle

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.poporyadku.domain.shuffle.DeterministicShuffler
import ru.poporyadku.domain.usecase.GetPuzzleUseCase
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.domain.usecase.SubmitAnswerUseCase
import ru.poporyadku.ui.navigation.Destinations

/**
 * `PuzzleViewModel` — ITERATION_3_DESIGN.md, `I3-V1`–`I3-V12`, `I3-V17`, `I3-V22`–`I3-V31`,
 * `I3-V33`.
 *
 * Use cases настоящие: подменены их репозитории, поэтому проверяется поведение всей
 * цепочки «маршрут → use case → состояние → эффект», а не заглушка вокруг ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PuzzleViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var content: FakeContentInstaller
    private lateinit var assignments: FakeAssignments
    private lateinit var sets: FakeSets
    private lateinit var puzzles: FakePuzzles
    private lateinit var progress: FakeProgress

    /** Детерминированный стартовый порядок — тот же, что отдаст use case. */
    private val startOrder: List<String> =
        DeterministicShuffler.shuffle(PuzzleFixtures.PUZZLE_ID, PuzzleFixtures.cardIds)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        content = FakeContentInstaller()
        assignments = FakeAssignments()
        sets = FakeSets()
        puzzles = FakePuzzles()
        progress = FakeProgress()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- I3-V1 / I3-V2: восстановление порядка ---------------------------------------

    /** `I3-V1`. Порядок из `SavedStateHandle` применяется; стартовый вектор не берётся. */
    @Test
    fun `I3-V1 restores the saved order instead of reshuffling`() = runTest(dispatcher) {
        val saved = listOf("c4", "c3", "c2", "c1")
        val handle = routeHandle(
            KEY_CURRENT_ORDER to saved.joinToString(","),
            KEY_ORDER_PUZZLE_ID to PuzzleFixtures.PUZZLE_ID,
        )
        val viewModel = createViewModel(handle)
        advanceUntilIdle()

        assertEquals(saved, viewModel.orderOrNull())
        assertTrue("порядок обязан отличаться от стартового", saved != startOrder)
    }

    /**
     * `I3-V2`. Любая порча восстановленного значения даёт детерминированный стартовый
     * порядок, состояние `Playing` и **ни одной** ошибки.
     */
    @Test
    fun `I3-V2 corrupted saved order falls back to the deterministic start order`() =
        runTest(dispatcher) {
            val corrupted = mapOf(
                "обрезанная строка" to listOf("c1", "c2"),
                "дубликат" to listOf("c1", "c1", "c3", "c4"),
                "лишний id" to listOf("c1", "c2", "c3", "c9"),
                "отсутствующий id" to listOf("c1", "c2", "c3"),
            )

            corrupted.forEach { (name, order) ->
                val viewModel = createViewModel(
                    routeHandle(
                        KEY_CURRENT_ORDER to order.joinToString(","),
                        KEY_ORDER_PUZZLE_ID to PuzzleFixtures.PUZZLE_ID,
                    ),
                )
                advanceUntilIdle()

                assertTrue("$name: состояние обязано быть Playing", viewModel.uiState.value is PuzzleUiState.Playing)
                assertEquals(name, startOrder, viewModel.orderOrNull())
            }
        }

    /** `I3-V2`. Чужой `orderPuzzleId` не принимается, даже если сам список корректен. */
    @Test
    fun `I3-V2 saved order of another puzzle is rejected`() = runTest(dispatcher) {
        val viewModel = createViewModel(
            routeHandle(
                KEY_CURRENT_ORDER to "c4,c3,c2,c1",
                KEY_ORDER_PUZZLE_ID to "tmp-hist-izobreteniya-002",
            ),
        )
        advanceUntilIdle()

        assertEquals(startOrder, viewModel.orderOrNull())
    }

    // --- I3-V3 – I3-V6: перемещения --------------------------------------------------

    /** `I3-V3`. `MoveUp`/`MoveDown` меняют порядок, позиции и флаги краёв. */
    @Test
    fun `I3-V3 move up and down update order positions and edge flags`() = runTest(dispatcher) {
        val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"))

        viewModel.onEvent(PuzzleEvent.MoveUp("c3"))
        assertEquals(listOf("c1", "c3", "c2", "c4"), viewModel.orderOrNull())

        val cards = viewModel.boardOrNull()!!.cards
        assertEquals(listOf(1, 2, 3, 4), cards.map { it.position })
        assertFalse("у первой карточки нет хода вверх", cards.first().canMoveUp)
        assertFalse("у последней карточки нет хода вниз", cards.last().canMoveDown)
        assertTrue(cards.first().canMoveDown)
        assertTrue(cards.last().canMoveUp)

        viewModel.onEvent(PuzzleEvent.MoveDown("c1"))
        assertEquals(listOf("c3", "c1", "c2", "c4"), viewModel.orderOrNull())
    }

    /** `I3-V4`. Граничное перемещение не меняет состояние. */
    @Test
    fun `I3-V4 edge moves change nothing`() = runTest(dispatcher) {
        val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"))
        val before = viewModel.uiState.value

        viewModel.onEvent(PuzzleEvent.MoveUp("c1"))
        viewModel.onEvent(PuzzleEvent.MoveDown("c4"))
        viewModel.onEvent(PuzzleEvent.MoveToTop("c1"))
        viewModel.onEvent(PuzzleEvent.MoveToBottom("c4"))

        assertEquals(before, viewModel.uiState.value)
    }

    /** `I3-V5`. `MoveToTop`/`MoveToBottom` переносят карточку на край, остальные сдвигаются. */
    @Test
    fun `I3-V5 move to top and to bottom keep the relative order of the rest`() =
        runTest(dispatcher) {
            val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"))

            viewModel.onEvent(PuzzleEvent.MoveToTop("c4"))
            assertEquals(listOf("c4", "c1", "c2", "c3"), viewModel.orderOrNull())

            viewModel.onEvent(PuzzleEvent.MoveToBottom("c4"))
            assertEquals(listOf("c1", "c2", "c3", "c4"), viewModel.orderOrNull())
        }

    /** `I3-V6`. Каждая перестановка немедленно пишет ОБА ключа. */
    @Test
    fun `I3-V6 every move writes both saved state keys immediately`() = runTest(dispatcher) {
        val handle = routeHandle()
        val viewModel = createViewModel(handle)
        advanceUntilIdle()

        assertNull("до первой перестановки писать нечего", handle.get<String>(KEY_CURRENT_ORDER))

        val moved = viewModel.boardOrNull()!!.cards[1].cardId
        viewModel.onEvent(PuzzleEvent.MoveUp(moved))

        assertEquals(
            viewModel.orderOrNull()!!.joinToString(","),
            handle.get<String>(KEY_CURRENT_ORDER),
        )
        assertEquals(PuzzleFixtures.PUZZLE_ID, handle.get<String>(KEY_ORDER_PUZZLE_ID))
    }

    /** После перемещения приходит структурированное объявление для TalkBack. */
    @Test
    fun `move emits a structured accessibility announcement`() = runTest(dispatcher) {
        val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"))

        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.MoveToBottom("c1"))
            val effect = awaitItem() as PuzzleEffect.AnnounceCardMoved
            assertEquals("Эльбрус", effect.cardTitle)
            assertEquals(4, effect.position)
            assertEquals(4, effect.totalPositions)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I3-V7 – I3-V9: отправка ------------------------------------------------------

    /** `I3-V7`. `Submit` переводит в `Submitting` и блокирует перестановки. */
    @Test
    fun `I3-V7 submit blocks controls`() = runTest(dispatcher) {
        val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"))
        progress.blockRecording()

        viewModel.onEvent(PuzzleEvent.Submit)
        runCurrent()

        val submitting = viewModel.uiState.value as PuzzleUiState.Submitting.Answer
        viewModel.onEvent(PuzzleEvent.MoveUp("c3"))
        assertEquals(submitting, viewModel.uiState.value)
        assertEquals(listOf("c1", "c2", "c3", "c4"), submitting.board.cards.map { it.cardId })
    }

    /** `I3-V8`. Повторный `Submit` не запускает вторую запись. */
    @Test
    fun `I3-V8 repeated submit records exactly one attempt`() = runTest(dispatcher) {
        val viewModel = playingViewModel()
        progress.blockRecording()

        viewModel.onEvent(PuzzleEvent.Submit)
        runCurrent()
        viewModel.onEvent(PuzzleEvent.Submit)
        viewModel.onEvent(PuzzleEvent.Submit)
        progress.release()
        advanceUntilIdle()

        assertEquals(1, progress.recorded.size)
    }

    /** `I3-V9`. Навигационный эффект приходит ТОЛЬКО после завершения записи. */
    @Test
    fun `I3-V9 navigation effect waits for the write to finish`() = runTest(dispatcher) {
        val viewModel = playingViewModel()
        progress.blockRecording()

        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.Submit)
            runCurrent()
            expectNoEvents()

            progress.release()
            advanceUntilIdle()
            assertEquals(PuzzleEffect.NavigateToResult(0), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, progress.recorded.size)
    }

    /** `I3-V9`. Отказ записи не даёт эффекта и оставляет экран в `Error` с повтором. */
    @Test
    fun `I3-V9 write failure produces an error and no effect`() = runTest(dispatcher) {
        val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"))
        progress.failWith = { IllegalStateException("база недоступна") }

        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.Submit)
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        val error = viewModel.uiState.value as PuzzleUiState.Error
        assertEquals(PuzzleErrorKind.Storage, error.kind)
        assertEquals(
            RetryAction.Resubmit(Submission.Answer(listOf("c1", "c2", "c3", "c4"))),
            error.retry,
        )
    }

    // --- I3-V10 – I3-V12 --------------------------------------------------------------

    /** `I3-V10`. Закрытый слот редиректится без прохода через `Playing`. */
    @Test
    fun `I3-V10 closed slot redirects without a playing frame`() = runTest(dispatcher) {
        progress.close(PuzzleFixtures.date, slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"))
        val viewModel = createViewModel(routeHandle())

        viewModel.uiState.test {
            assertEquals(PuzzleUiState.Loading, awaitItem())
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(PuzzleEffect.NavigateToResult(0), viewModel.effects.first())
    }

    /** `I3-V11`. `BackPressed` из `Playing` ведёт на Home, из `Submitting` — ничего. */
    @Test
    fun `I3-V11 back is ignored while submitting`() = runTest(dispatcher) {
        val viewModel = playingViewModel()

        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.BackPressed)
            assertEquals(PuzzleEffect.NavigateHome, awaitItem())

            progress.blockRecording()
            viewModel.onEvent(PuzzleEvent.Submit)
            runCurrent()
            viewModel.onEvent(PuzzleEvent.BackPressed)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** `I3-V12`. Эффект доставляется один раз: повторный сбор его не повторяет. */
    @Test
    fun `I3-V12 effect is delivered exactly once`() = runTest(dispatcher) {
        val viewModel = playingViewModel()

        viewModel.onEvent(PuzzleEvent.BackPressed)
        assertEquals(PuzzleEffect.NavigateHome, viewModel.effects.first())

        // Модель поворота экрана: новый коллектор не получает уже доставленный элемент.
        val repeated = withTimeoutOrNull(REPLAY_PROBE_MS) { viewModel.effects.first() }
        assertNull("Channel не реплеит доставленный эффект", repeated)
    }

    // --- I3-V17 -----------------------------------------------------------------------

    /** `I3-V17`. `CancellationException` остаётся отменой и не становится `Error`. */
    @Test
    fun `I3-V17 cancellation is never converted into an error state`() = runTest(dispatcher) {
        assignments.failCancellation = true
        val viewModel = createViewModel(routeHandle())
        advanceUntilIdle()

        assertEquals(PuzzleUiState.Loading, viewModel.uiState.value)
    }

    // --- I3-V22 – I3-V24: маршрут -----------------------------------------------------

    /** `I3-V22`. Маршрут без даты: `InvalidRoute`, Home и НИ ОДНОГО обращения к use case. */
    @Test
    fun `I3-V22 missing date never reaches the use case`() = runTest(dispatcher) {
        val viewModel = createViewModel(SavedStateHandle(mapOf(Destinations.ARG_SLOT_INDEX to 0)))
        advanceUntilIdle()

        val error = viewModel.uiState.value as PuzzleUiState.Error
        assertEquals(PuzzleErrorKind.InvalidRoute, error.kind)
        assertEquals(RetryAction.None, error.retry)
        assertNull(error.board)
        assertEquals(PuzzleEffect.NavigateHome, viewModel.effects.first())

        assertEquals("GetPuzzleUseCase не должен вызываться вовсе", 0, content.calls)
        assertTrue(assignments.queries.isEmpty())
        assertTrue("в базу ничего не пишется", progress.recorded.isEmpty())
    }

    /** `I3-V23`. Неразбираемая дата не подменяется текущей. */
    @Test
    fun `I3-V23 malformed date is not replaced by today`() = runTest(dispatcher) {
        val viewModel = createViewModel(
            SavedStateHandle(
                mapOf(Destinations.ARG_SLOT_INDEX to 0, Destinations.ARG_DATE to "вчера"),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            PuzzleErrorKind.InvalidRoute,
            (viewModel.uiState.value as PuzzleUiState.Error).kind,
        )
        assertTrue(assignments.queries.isEmpty())
    }

    /** `I3-V24`. Сессионная дата приходит из маршрута и переживает смену системной даты. */
    @Test
    fun `I3-V24 session date comes from the route only`() = runTest(dispatcher) {
        val sessionDate = LocalDate.of(2026, 8, 31)
        assignments = FakeAssignments(PuzzleFixtures.assignment(sessionDate))
        val viewModel = createViewModel(routeHandle(date = sessionDate))
        advanceUntilIdle()

        viewModel.onEvent(PuzzleEvent.Submit)
        advanceUntilIdle()

        assertEquals(listOf(sessionDate, sessionDate), assignments.queries)
        assertEquals(sessionDate, progress.recorded.single().localDate)
    }

    // --- I3-V25, I3-V33: пропуск -------------------------------------------------------

    /** `I3-V25`. Пропуск доступен на `PuzzleNotFound` и недоступен на `SetNotFound`. */
    @Test
    fun `I3-V25 skip is offered only where there is something to record`() = runTest(dispatcher) {
        puzzles.remove(PuzzleFixtures.PUZZLE_ID)
        val viewModel = createViewModel(routeHandle())
        advanceUntilIdle()

        assertEquals(
            PuzzleErrorKind.PuzzleNotFound,
            (viewModel.uiState.value as PuzzleUiState.Error).kind,
        )
        viewModel.onEvent(PuzzleEvent.SkipClicked)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), progress.recorded.single().submittedOrder)
        assertEquals(0, progress.recorded.single().score)
    }

    /** `I3-V25`. `SetNotFound` не skippable: Home и ни одной записи. */
    @Test
    fun `I3-V25 set not found leaves the screen without writing anything`() = runTest(dispatcher) {
        sets.clear()
        val viewModel = createViewModel(routeHandle())
        advanceUntilIdle()

        val error = viewModel.uiState.value as PuzzleUiState.Error
        assertEquals(PuzzleErrorKind.SetNotFound, error.kind)
        assertEquals(RetryAction.None, error.retry)
        assertEquals(PuzzleEffect.NavigateHome, viewModel.effects.first())

        viewModel.onEvent(PuzzleEvent.SkipClicked)
        advanceUntilIdle()
        assertTrue(progress.recorded.isEmpty())
    }

    /**
     * `I3-V33`. Пропуск во время записи представим, «стола» в нём нет, второй
     * `SkipClicked` не запускает вторую запись, `BackPressed` не даёт эффекта, а отказ
     * записи повторяется именно пропуском.
     */
    @Test
    fun `I3-V33 skip while submitting is representable and idempotent`() = runTest(dispatcher) {
        puzzles.remove(PuzzleFixtures.PUZZLE_ID)
        val viewModel = createViewModel(routeHandle())
        advanceUntilIdle()
        progress.blockRecording()

        viewModel.onEvent(PuzzleEvent.SkipClicked)
        runCurrent()

        val submitting = viewModel.uiState.value as PuzzleUiState.Submitting.Skip
        assertEquals(PuzzleErrorKind.PuzzleNotFound, submitting.sourceErrorKind)
        assertEquals(Submission.Skip, submitting.pending)

        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.SkipClicked)
            viewModel.onEvent(PuzzleEvent.BackPressed)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        progress.release()
        advanceUntilIdle()
        assertEquals("SubmitAnswerUseCase вызывается ровно один раз", 1, progress.recorded.size)
    }

    /** `I3-V33`. Отказ записи пропуска даёт `Resubmit(Skip)` и `board = null`. */
    @Test
    fun `I3-V33 failed skip retries the skip itself`() = runTest(dispatcher) {
        puzzles.remove(PuzzleFixtures.PUZZLE_ID)
        progress.failWith = { IllegalStateException("база недоступна") }
        val viewModel = createViewModel(routeHandle())
        advanceUntilIdle()

        viewModel.onEvent(PuzzleEvent.SkipClicked)
        advanceUntilIdle()

        val error = viewModel.uiState.value as PuzzleUiState.Error
        assertEquals(PuzzleErrorKind.Storage, error.kind)
        assertEquals(RetryAction.Resubmit(Submission.Skip), error.retry)
        assertNull(error.board)
    }

    // --- I3-V27 – I3-V30: повтор -------------------------------------------------------

    /** `I3-V27`. «Повторить» после неудавшейся загрузки повторяет именно загрузку. */
    @Test
    fun `I3-V27 retry after a failed load calls GetPuzzleUseCase`() = runTest(dispatcher) {
        assignments.failWith = { IllegalStateException("база недоступна") }
        val viewModel = createViewModel(routeHandle())
        advanceUntilIdle()

        val error = viewModel.uiState.value as PuzzleUiState.Error
        assertEquals(PuzzleErrorKind.Storage, error.kind)
        assertEquals(RetryAction.Reload, error.retry)

        assignments.failWith = null
        viewModel.onEvent(PuzzleEvent.RetryClicked)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is PuzzleUiState.Playing)
        assertTrue("попыток не записано", progress.recorded.isEmpty())
    }

    /** `I3-V28`. «Повторить» после отказа записи отправляет ТОТ ЖЕ порядок. */
    @Test
    fun `I3-V28 retry after a failed write resubmits the same order`() = runTest(dispatcher) {
        val viewModel = playingViewModel(order = listOf("c3", "c4", "c1", "c2"))
        progress.failWith = { IllegalStateException("база недоступна") }

        viewModel.onEvent(PuzzleEvent.Submit)
        advanceUntilIdle()

        progress.failWith = null
        viewModel.onEvent(PuzzleEvent.RetryClicked)
        advanceUntilIdle()

        assertEquals(listOf("c3", "c4", "c1", "c2"), progress.recorded.single().submittedOrder)
    }

    /** `I3-V30`. `Submitting.Answer` несёт тот же «стол», что предшествующий `Playing`. */
    @Test
    fun `I3-V30 submitting carries the very same board`() = runTest(dispatcher) {
        val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"))
        val playing = viewModel.uiState.value as PuzzleUiState.Playing
        progress.blockRecording()

        viewModel.onEvent(PuzzleEvent.Submit)
        runCurrent()

        val submitting = viewModel.uiState.value as PuzzleUiState.Submitting.Answer
        assertEquals(playing.board, submitting.board)
    }

    // --- I3-V31: навигация по AttemptKind ---------------------------------------------

    /** `I3-V31`. Все шесть комбинаций `AttemptKind` × слот идут по одной таблице. */
    @Test
    fun `I3-V31 attempt kind and slot decide the destination`() = runTest(dispatcher) {
        val answered = listOf("c2", "c1", "c3", "c4")
        val expected = mapOf(
            0 to (PuzzleEffect.NavigateToResult(0) to PuzzleEffect.NavigateToNextSlot(1)),
            1 to (PuzzleEffect.NavigateToResult(1) to PuzzleEffect.NavigateToNextSlot(2)),
            2 to (PuzzleEffect.NavigateToResult(2) to PuzzleEffect.NavigateToRecap),
        )

        expected.forEach { (slot, effects) ->
            val (whenAnswered, whenSkipped) = effects

            setUp()
            progress.close(PuzzleFixtures.date, slot, submittedOrder = answered)
            val answeredVm = createViewModel(routeHandle(slotIndex = slot))
            advanceUntilIdle()
            assertEquals("слот $slot, Answered", whenAnswered, answeredVm.effects.first())

            setUp()
            progress.close(PuzzleFixtures.date, slot, submittedOrder = emptyList())
            val skippedVm = createViewModel(routeHandle(slotIndex = slot))
            advanceUntilIdle()
            assertEquals("слот $slot, Skipped", whenSkipped, skippedVm.effects.first())
        }
    }

    /** Проигранная гонка идёт по ПОБЕДИВШЕЙ записи, а не по своему намерению. */
    @Test
    fun `race is resolved by the stored attempt, not by the intent`() = runTest(dispatcher) {
        val viewModel = playingViewModel()

        // Пока идёт отправка ответа, слот закрывается пропуском извне.
        progress.close(PuzzleFixtures.date, slotIndex = 0, submittedOrder = emptyList())
        viewModel.onEvent(PuzzleEvent.Submit)
        advanceUntilIdle()

        assertEquals(PuzzleEffect.NavigateToNextSlot(1), viewModel.effects.first())
    }

    // --- Инфраструктура ----------------------------------------------------------------

    private fun createViewModel(handle: SavedStateHandle) = PuzzleViewModel(
        getPuzzle = GetPuzzleUseCase(content, assignments, sets, puzzles, progress),
        submitAnswer = SubmitAnswerUseCase(assignments, sets, puzzles, progress),
        savedStateHandle = handle,
    )

    /** ViewModel в `Playing` с нужным порядком карточек. */
    private fun kotlinx.coroutines.test.TestScope.playingViewModel(
        order: List<String>? = null,
    ): PuzzleViewModel {
        val handle = if (order == null) {
            routeHandle()
        } else {
            routeHandle(
                KEY_CURRENT_ORDER to order.joinToString(","),
                KEY_ORDER_PUZZLE_ID to PuzzleFixtures.PUZZLE_ID,
            )
        }
        val viewModel = createViewModel(handle)
        advanceUntilIdle()
        check(viewModel.uiState.value is PuzzleUiState.Playing) { "ожидалось Playing" }
        return viewModel
    }

    private fun routeHandle(
        vararg extras: Pair<String, Any?>,
        slotIndex: Int = 0,
        date: LocalDate = PuzzleFixtures.date,
    ) = SavedStateHandle(
        buildMap {
            put(Destinations.ARG_SLOT_INDEX, slotIndex)
            put(Destinations.ARG_DATE, Destinations.serialize(date))
            putAll(extras)
        },
    )

    private fun PuzzleViewModel.boardOrNull(): PuzzleBoard? = when (val state = uiState.value) {
        is PuzzleUiState.Playing -> state.board
        is PuzzleUiState.Submitting.Answer -> state.board
        is PuzzleUiState.Error -> state.board
        else -> null
    }

    private fun PuzzleViewModel.orderOrNull(): List<String>? =
        boardOrNull()?.cards?.map { it.cardId }

    private companion object {
        /** Окно, за которое реплей проявился бы, будь он у механизма эффектов. */
        const val REPLAY_PROBE_MS = 100L
    }
}
