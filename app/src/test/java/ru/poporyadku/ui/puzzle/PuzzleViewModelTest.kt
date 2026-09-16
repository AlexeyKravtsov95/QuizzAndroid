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
import ru.poporyadku.data.content.FakeUserPreferencesRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.shuffle.DeterministicShuffler
import ru.poporyadku.domain.usecase.GetPuzzleUseCase
import ru.poporyadku.domain.usecase.ObserveFeedbackSettingsUseCase
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.domain.usecase.SubmitAnswerUseCase
import ru.poporyadku.ui.feedback.FeedbackCue
import ru.poporyadku.ui.feedback.FeedbackRequest
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.settings.ControllablePreferences

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


    // --- I5-V29 – I5-V34: отдача ------------------------------------------------------

    /**
     * `I5-V29`. Каждая фактическая перестановка — включая вызванную custom actions
     * TalkBack (`MoveToTop`/`MoveToBottom`) — даёт `Feedback(CardMoved)` с каналами по
     * настройкам, и он приходит ПЕРЕД объявлением для TalkBack.
     */
    @Test
    fun `I5-V29 a real reorder emits feedback before the announcement`() = runTest(dispatcher) {
        val events = listOf(
            PuzzleEvent.MoveUp("c3"),
            PuzzleEvent.MoveDown("c1"),
            PuzzleEvent.MoveToTop("c4"),
            PuzzleEvent.MoveToBottom("c1"),
        )

        events.forEach { event ->
            setUp()
            val viewModel = playingViewModel(
                order = listOf("c1", "c2", "c3", "c4"),
                feedback = feedbackSettings(sound = true, vibration = true),
            )

            viewModel.effects.test {
                viewModel.onEvent(event)

                assertEquals(
                    "$event: отдача первой",
                    PuzzleEffect.Feedback(
                        FeedbackRequest(FeedbackCue.CardMoved, playSound = true, performHaptic = true),
                    ),
                    awaitItem(),
                )
                assertTrue("$event: затем объявление", awaitItem() is PuzzleEffect.AnnounceCardMoved)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    /**
     * `I5-V30`. Недоступная перестановка (край списка и чужой `cardId`), `Submitting` и
     * `Error` отдачи не дают: `move()` выходит раньше единственного её вызова.
     */
    @Test
    fun `I5-V30 unavailable reorders and wrong states emit no feedback`() = runTest(dispatcher) {
        val enabled = feedbackSettings(sound = true, vibration = true)

        // Край списка: первая карточка вверх, последняя вниз, и чужой cardId.
        val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"), feedback = enabled)
        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.MoveUp("c1"))
            viewModel.onEvent(PuzzleEvent.MoveToTop("c1"))
            viewModel.onEvent(PuzzleEvent.MoveDown("c4"))
            viewModel.onEvent(PuzzleEvent.MoveToBottom("c4"))
            viewModel.onEvent(PuzzleEvent.MoveUp("c9"))
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("c1", "c2", "c3", "c4"), viewModel.orderOrNull())

        // Submitting: запись идёт, управление заблокировано состоянием.
        progress.blockRecording()
        viewModel.onEvent(PuzzleEvent.Submit)
        runCurrent()
        assertTrue(viewModel.uiState.value is PuzzleUiState.Submitting)
        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.MoveUp("c3"))
            runCurrent()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        progress.release()
        advanceUntilIdle()

        // Error: двигать нечего.
        setUp()
        assignments.failWith = { IllegalStateException("база недоступна") }
        val failed = createViewModel(routeHandle(), enabled)
        advanceUntilIdle()
        assertTrue(failed.uiState.value is PuzzleUiState.Error)
        failed.effects.test {
            failed.onEvent(PuzzleEvent.MoveUp("c3"))
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * `I5-V31`. Каналы в эффекте — ровно настройки пользователя; при обоих выключенных и
     * при непрочитанных настройках эффекта нет вовсе.
     */
    @Test
    fun `I5-V31 channels follow the user settings`() = runTest(dispatcher) {
        val expected = mapOf(
            (true to true) to FeedbackRequest(FeedbackCue.CardMoved, playSound = true, performHaptic = true),
            (true to false) to FeedbackRequest(FeedbackCue.CardMoved, playSound = true, performHaptic = false),
            (false to true) to FeedbackRequest(FeedbackCue.CardMoved, playSound = false, performHaptic = true),
        )

        expected.forEach { (settings, request) ->
            val (sound, vibration) = settings
            setUp()
            val viewModel = playingViewModel(
                order = listOf("c1", "c2", "c3", "c4"),
                feedback = feedbackSettings(sound = sound, vibration = vibration),
            )

            viewModel.effects.test {
                viewModel.onEvent(PuzzleEvent.MoveUp("c3"))

                assertEquals("звук=$sound, вибрация=$vibration", PuzzleEffect.Feedback(request), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

        // Оба выключены и «ещё не прочитано» дают одно и то же наблюдаемое поведение —
        // но по разным причинам, и обе обязаны молчать.
        val silent = listOf(
            feedbackSettings(sound = false, vibration = false),
            ControllablePreferences(initial = null),
        )

        silent.forEach { preferences ->
            setUp()
            val viewModel = playingViewModel(order = listOf("c1", "c2", "c3", "c4"), feedback = preferences)

            viewModel.effects.test {
                viewModel.onEvent(PuzzleEvent.MoveUp("c3"))

                // Объявление для TalkBack остаётся: оно не отдача и от настроек не зависит.
                assertTrue(awaitItem() is PuzzleEffect.AnnounceCardMoved)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    /**
     * `I5-V32`. `Recorded(Answered)` → `Feedback(AnswerAccepted)`, затем
     * `NavigateToResult`: порядок в канале, и отдача — уже после записи в базу.
     */
    @Test
    fun `I5-V32 accepted answer emits feedback before navigation`() = runTest(dispatcher) {
        val viewModel = playingViewModel(feedback = feedbackSettings(sound = true, vibration = true))
        progress.blockRecording()

        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.Submit)
            runCurrent()
            // До завершения записи не приходит ничего — в том числе отдача.
            expectNoEvents()
            assertTrue("попытка ещё не записана", progress.recorded.isEmpty())

            progress.release()
            advanceUntilIdle()

            assertEquals(
                PuzzleEffect.Feedback(
                    FeedbackRequest(FeedbackCue.AnswerAccepted, playSound = true, performHaptic = true),
                ),
                awaitItem(),
            )
            assertEquals(PuzzleEffect.NavigateToResult(0), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, progress.recorded.size)
    }

    /**
     * `I5-V33`. Отказ записи, проигранная гонка (`AlreadyClosed`), пропуск и второе
     * нажатие «Проверить» `AnswerAccepted` не дают; при двойном нажатии он ровно один.
     */
    @Test
    fun `I5-V33 refusal, already closed, skip and double submit emit no accept`() = runTest(dispatcher) {
        val enabled = feedbackSettings(sound = true, vibration = true)

        // Отказ записи: подтверждать нечего.
        var viewModel = playingViewModel(feedback = enabled)
        progress.failWith = { IllegalStateException("база недоступна") }
        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.Submit)
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // AlreadyClosed: запись создало не это действие.
        setUp()
        viewModel = playingViewModel(feedback = enabled)
        progress.close(PuzzleFixtures.date, slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"))
        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.Submit)
            advanceUntilIdle()

            assertEquals(PuzzleEffect.NavigateToResult(0), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("второй попытки нет", progress.recorded.isEmpty())

        // «Пропустить» — не «Проверить»: подтверждения ответа нет.
        setUp()
        puzzles.remove(PuzzleFixtures.PUZZLE_ID)
        val skipping = createViewModel(routeHandle(), enabled)
        advanceUntilIdle()
        assertEquals(PuzzleErrorKind.PuzzleNotFound, (skipping.uiState.value as PuzzleUiState.Error).kind)
        skipping.effects.test {
            skipping.onEvent(PuzzleEvent.SkipClicked)
            advanceUntilIdle()

            assertEquals(PuzzleEffect.NavigateToNextSlot(1), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // Второе нажатие в Submitting действия не создаёт — значит и отдачи.
        setUp()
        viewModel = playingViewModel(feedback = enabled)
        progress.blockRecording()
        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.Submit)
            runCurrent()
            viewModel.onEvent(PuzzleEvent.Submit)
            runCurrent()
            progress.release()
            advanceUntilIdle()

            assertEquals(
                PuzzleEffect.Feedback(
                    FeedbackRequest(FeedbackCue.AnswerAccepted, playSound = true, performHaptic = true),
                ),
                awaitItem(),
            )
            assertEquals(PuzzleEffect.NavigateToResult(0), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("ровно одна запись", 1, progress.recorded.size)
    }

    /**
     * `I5-V34`. Перечислением: загрузка, восстановление порядка из `SavedStateHandle`,
     * повторная загрузка и `BackPressed` отдачи не порождают — `emitFeedback` в них не
     * вызывается.
     */
    @Test
    fun `I5-V34 loading, restoring, reloading and back emit no feedback`() = runTest(dispatcher) {
        val enabled = feedbackSettings(sound = true, vibration = true)

        // Первоначальная загрузка.
        var viewModel = createViewModel(routeHandle(), enabled)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is PuzzleUiState.Playing)
        assertNull("загрузка отдачи не даёт", withTimeoutOrNull(REPLAY_PROBE_MS) { viewModel.effects.first() })

        // Восстановление порядка после смерти процесса.
        setUp()
        val restored = listOf("c4", "c3", "c2", "c1")
        viewModel = createViewModel(
            routeHandle(
                KEY_CURRENT_ORDER to restored.joinToString(","),
                KEY_ORDER_PUZZLE_ID to PuzzleFixtures.PUZZLE_ID,
            ),
            enabled,
        )
        advanceUntilIdle()
        assertEquals(restored, viewModel.orderOrNull())
        assertNull(
            "восстановление отдачи не даёт",
            withTimeoutOrNull(REPLAY_PROBE_MS) { viewModel.effects.first() },
        )

        // Повторная загрузка по «Повторить».
        setUp()
        assignments.failWith = { IllegalStateException("база недоступна") }
        viewModel = createViewModel(routeHandle(), enabled)
        advanceUntilIdle()
        assignments.failWith = null
        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.RetryClicked)
            advanceUntilIdle()

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(viewModel.uiState.value is PuzzleUiState.Playing)

        // BackPressed: только навигация.
        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.BackPressed)
            advanceUntilIdle()

            assertEquals(PuzzleEffect.NavigateHome, awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I6-V1 – I6-V12: единый путь перестановки и жест -------------------------------

    /**
     * `I6-V1`. Жест на одну позицию вниз: порядок, `position`/`canMove*` и оба ключа
     * `SavedStateHandle` обновлены; ровно один `Feedback(CardMoved)`; объявления во время
     * жеста нет.
     */
    @Test
    fun `I6-V1 a drag one position down confirms the order once`() = runTest(dispatcher) {
        val handle = orderedHandle(listOf("c1", "c2", "c3", "c4"))
        val viewModel = createViewModel(handle, feedbackSettings(sound = true, vibration = true))
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.DragStarted("c1", G1))
            assertEquals(grabbed(), awaitItem())

            viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 1, gesture = G1))
            assertEquals(moved(), awaitItem())

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("c2", "c1", "c3", "c4"), viewModel.orderOrNull())
        val cards = viewModel.boardOrNull()!!.cards
        assertEquals(listOf(1, 2, 3, 4), cards.map { it.position })
        assertFalse(cards.first().canMoveUp)
        assertFalse(cards.last().canMoveDown)
        assertEquals("c2,c1,c3,c4", handle.get<String>(KEY_CURRENT_ORDER))
        assertEquals(PuzzleFixtures.PUZZLE_ID, handle.get<String>(KEY_ORDER_PUZZLE_ID))
    }

    /** `I6-V2`. Цель сразу на три позиции — одна перестановка и одна отдача. */
    @Test
    fun `I6-V2 a three position target is a single confirmed reorder`() = runTest(dispatcher) {
        val viewModel = draggingViewModel("c1", order = listOf("c1", "c2", "c3", "c4"))

        viewModel.effects.test {
            assertEquals(grabbed(), awaitItem())
            viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 3, gesture = G1))

            assertEquals(moved(), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("c2", "c3", "c4", "c1"), viewModel.orderOrNull())
    }

    /**
     * `I6-V3`. Повтор той же абсолютной цели: второй перестановки, записи и отдачи нет —
     * цель идемпотентна по построению (I6-D3).
     */
    @Test
    fun `I6-V3 repeating the same target changes nothing`() = runTest(dispatcher) {
        val handle = orderedHandle(listOf("c1", "c2", "c3", "c4"))
        val viewModel = createViewModel(handle, feedbackSettings(sound = true, vibration = true))
        advanceUntilIdle()
        viewModel.onEvent(PuzzleEvent.DragStarted("c1", G1))
        viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 2, gesture = G1))
        val afterFirst = viewModel.uiState.value

        viewModel.effects.test {
            assertEquals(grabbed(), awaitItem())
            assertEquals(moved(), awaitItem())
            repeat(REPEATS) {
                viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 2, gesture = G1))
            }

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(afterFirst, viewModel.uiState.value)
        assertEquals("c2,c3,c1,c4", handle.get<String>(KEY_CURRENT_ORDER))
    }

    /**
     * `I6-V4`. Объявление жеста — ровно одно и только при завершении: на пересечениях
     * порога TalkBack молчит, а вернувшаяся на место карточка не объявляется вовсе.
     */
    @Test
    fun `I6-V4 a drag announces once at the end and not at all if it returns`() =
        runTest(dispatcher) {
            val viewModel = draggingViewModel("c1", order = listOf("c1", "c2", "c3", "c4"))

            viewModel.effects.test {
                assertEquals(grabbed(), awaitItem())
                viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 1, gesture = G1))
                assertEquals(moved(), awaitItem())
                viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 2, gesture = G1))
                assertEquals(moved(), awaitItem())

                viewModel.onEvent(PuzzleEvent.DragFinished("c1", G1))
                val announcement = awaitItem() as PuzzleEffect.AnnounceCardMoved
                assertEquals("Эльбрус", announcement.cardTitle)
                assertEquals(3, announcement.position)
                assertEquals(4, announcement.totalPositions)

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            // Второй жест той же карточки, вернувший её на исходную позицию.
            viewModel.onEvent(PuzzleEvent.DragStarted("c1", G2))
            viewModel.effects.test {
                assertEquals(grabbed(), awaitItem())
                viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 0, gesture = G2))
                assertEquals(moved(), awaitItem())
                viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 2, gesture = G2))
                assertEquals(moved(), awaitItem())

                viewModel.onEvent(PuzzleEvent.DragFinished("c1", G2))

                assertEquals("вернулась на своё место — объявлять нечего", 0, cancelAndConsumeRemainingEvents().size)
            }
        }

    /**
     * `I6-V5`. Отдача захвата: только тактильная и ровно одна на принятый жест.
     * Выключенная вибрация и непрочитанные настройки её не порождают; повтор начала того
     * же жеста — тоже; новый жест той же карточки даёт ровно одну новую.
     */
    @Test
    fun `I6-V5 card grabbed is haptic only and happens once per accepted gesture`() =
        runTest(dispatcher) {
            val viewModel = playingViewModel(
                order = listOf("c1", "c2", "c3", "c4"),
                feedback = feedbackSettings(sound = true, vibration = true),
            )

            viewModel.effects.test {
                viewModel.onEvent(PuzzleEvent.DragStarted("c1", G1))
                assertEquals(
                    PuzzleEffect.Feedback(
                        FeedbackRequest(FeedbackCue.CardGrabbed, playSound = false, performHaptic = true),
                    ),
                    awaitItem(),
                )

                // Повтор начала ЭТОГО жеста: двойная доставка события не даёт второй отдачи.
                viewModel.onEvent(PuzzleEvent.DragStarted("c1", G1))
                expectNoEvents()

                // Новый жест той же карточки — новая сессия и ровно одна новая отдача.
                viewModel.onEvent(PuzzleEvent.DragStarted("c1", G2))
                assertEquals(grabbed(), awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            // Новый `startIndex`: жест g2 начат с позиции 0, и возврат на неё не объявляется.
            viewModel.effects.test {
                viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 1, gesture = G2))
                assertEquals(moved(), awaitItem())
                viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 0, gesture = G2))
                assertEquals(moved(), awaitItem())
                viewModel.onEvent(PuzzleEvent.DragFinished("c1", G2))

                assertEquals(0, cancelAndConsumeRemainingEvents().size)
            }

            // Вибрация выключена и настройки ещё не прочитаны — эффекта захвата нет вовсе.
            val silent = listOf(
                feedbackSettings(sound = true, vibration = false),
                ControllablePreferences(initial = null),
            )
            silent.forEach { preferences ->
                setUp()
                val quiet = playingViewModel(order = listOf("c1", "c2", "c3", "c4"), feedback = preferences)

                quiet.effects.test {
                    quiet.onEvent(PuzzleEvent.DragStarted("c1", G1))

                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    /** `I6-V6`. Вне `Playing` события жеста не меняют ни состояние, ни ключи, ни эффекты. */
    @Test
    fun `I6-V6 drag events outside playing change nothing`() = runTest(dispatcher) {
        val enabled = feedbackSettings(sound = true, vibration = true)

        // Loading: головоломка ещё не пришла.
        val loadingHandle = routeHandle()
        val loading = createViewModel(loadingHandle, enabled)
        assertEquals(PuzzleUiState.Loading, loading.uiState.value)
        loading.effects.test {
            dragSequence().forEach(loading::onEvent)

            assertEquals(PuzzleUiState.Loading, loading.uiState.value)
            assertNull(loadingHandle.get<String>(KEY_CURRENT_ORDER))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        advanceUntilIdle()

        // Submitting.Answer: ответ уже отправляется.
        setUp()
        val handle = orderedHandle(listOf("c1", "c2", "c3", "c4"))
        val viewModel = createViewModel(handle, enabled)
        advanceUntilIdle()
        progress.blockRecording()
        viewModel.onEvent(PuzzleEvent.Submit)
        runCurrent()
        val submitting = viewModel.uiState.value as PuzzleUiState.Submitting.Answer

        viewModel.effects.test {
            dragSequence().forEach(viewModel::onEvent)

            assertEquals(submitting, viewModel.uiState.value)
            assertEquals("c1,c2,c3,c4", handle.get<String>(KEY_CURRENT_ORDER))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        progress.release()
        advanceUntilIdle()

        // Error: «стола» нет.
        setUp()
        assignments.failWith = { IllegalStateException("база недоступна") }
        val failed = createViewModel(routeHandle(), enabled)
        advanceUntilIdle()
        val error = failed.uiState.value as PuzzleUiState.Error
        failed.effects.test {
            dragSequence().forEach(failed::onEvent)

            assertEquals(error, failed.uiState.value)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * `I6-V7`. Три эквивалентных намерения — кнопка, custom action и жест — из одинакового
     * исходного состояния дают одинаковый порядок, одинаковую строку `puzzle.currentOrder`
     * и по одному `CardMoved`: путь один (`CardOrder.move`).
     */
    @Test
    fun `I6-V7 button, custom action and drag are the same single path`() = runTest(dispatcher) {
        // Карточка на позиции 2: «вверх», «в начало» и абсолютная цель 0 — одно и то же.
        val intents = listOf<(PuzzleViewModel) -> Unit>(
            { it.onEvent(PuzzleEvent.MoveUp("c2")) },
            { it.onEvent(PuzzleEvent.MoveToTop("c2")) },
            {
                it.onEvent(PuzzleEvent.DragStarted("c2", G1))
                it.onEvent(PuzzleEvent.DragMovedTo("c2", targetIndex = 0, gesture = G1))
            },
        )

        val results = intents.map { intent ->
            setUp()
            val handle = orderedHandle(listOf("c1", "c2", "c3", "c4"))
            val viewModel = createViewModel(handle, feedbackSettings(sound = true, vibration = true))
            advanceUntilIdle()

            var moves = 0
            viewModel.effects.test {
                intent(viewModel)
                cancelAndConsumeRemainingEvents().forEach { event ->
                    val effect = (event as app.cash.turbine.Event.Item).value
                    if (effect == moved()) moves++
                }
            }

            Triple(viewModel.orderOrNull(), handle.get<String>(KEY_CURRENT_ORDER), moves)
        }

        assertEquals("все три намерения дают один результат", 1, results.toSet().size)
        assertEquals(
            Triple(listOf("c2", "c1", "c3", "c4"), "c2,c1,c3,c4", 1),
            results.first(),
        )
    }

    /**
     * `I6-V8`. `Submit` при открытой сессии отправляет последний подтверждённый порядок,
     * а запоздалое завершение жеста после него уже ничего не делает.
     */
    @Test
    fun `I6-V8 submit during a drag sends the confirmed order`() = runTest(dispatcher) {
        val viewModel = draggingViewModel("c1", order = listOf("c1", "c2", "c3", "c4"))

        viewModel.effects.test {
            assertEquals(grabbed(), awaitItem())
            viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 1, gesture = G1))
            assertEquals(moved(), awaitItem())
            viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 2, gesture = G1))
            assertEquals(moved(), awaitItem())

            viewModel.onEvent(PuzzleEvent.Submit)
            advanceUntilIdle()
            assertEquals(
                PuzzleEffect.Feedback(
                    FeedbackRequest(FeedbackCue.AnswerAccepted, playSound = true, performHaptic = true),
                ),
                awaitItem(),
            )
            assertEquals(PuzzleEffect.NavigateToResult(0), awaitItem())

            // Запоздалое завершение жеста после отправки: сессия уже закрыта `onSubmit`.
            viewModel.onEvent(PuzzleEvent.DragFinished("c1", G1))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            listOf("c2", "c3", "c1", "c4"),
            progress.recorded.single().submittedOrder,
        )
    }

    /**
     * `I6-V9` (а). Пересоздание UI без `DragFinished`: новый жест той же карточки
     * полностью заменяет висящую сессию — одна новая отдача, новый `startIndex`, — а
     * запоздалое завершение прежнего жеста не объявляет и не меняет ничего.
     */
    @Test
    fun `I6-V9 a new gesture replaces a session left open by a destroyed UI`() =
        runTest(dispatcher) {
            val viewModel = draggingViewModel("c1", order = listOf("c1", "c2", "c3", "c4"))
            viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 2, gesture = G1))
            assertEquals(listOf("c2", "c3", "c1", "c4"), viewModel.orderOrNull())

            // UI уничтожен без `DragFinished`; новый экран начинает свой жест.
            viewModel.effects.test {
                assertEquals(grabbed(), awaitItem())
                assertEquals(moved(), awaitItem())

                viewModel.onEvent(PuzzleEvent.DragStarted("c1", G2))
                assertEquals("ровно одна отдача захвата", grabbed(), awaitItem())

                viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 0, gesture = G2))
                assertEquals(moved(), awaitItem())

                viewModel.onEvent(PuzzleEvent.DragFinished("c1", G2))
                val announcement = awaitItem() as PuzzleEffect.AnnounceCardMoved
                // Сравнение с позицией на начало g2 (была 2), а не с началом g1 (было 0).
                assertEquals(1, announcement.position)

                // Запоздалое завершение старого жеста: ни объявления, ни изменений.
                viewModel.onEvent(PuzzleEvent.DragFinished("c1", G1))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf("c1", "c2", "c3", "c4"), viewModel.orderOrNull())
        }

    /**
     * `I6-V9` (б). Новый ViewModel на том же `SavedStateHandle` — восстановление после
     * смерти процесса: порядок восстановлен, отдачи и объявления нет, а событие жеста без
     * своего `DragStarted` игнорируется.
     */
    @Test
    fun `I6-V9 a restored view model has no session and no feedback`() = runTest(dispatcher) {
        val handle = orderedHandle(listOf("c1", "c2", "c3", "c4"))
        val first = createViewModel(handle, feedbackSettings(sound = true, vibration = true))
        advanceUntilIdle()
        first.onEvent(PuzzleEvent.DragStarted("c1", G1))
        first.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 2, gesture = G1))

        val restored = createViewModel(handle, feedbackSettings(sound = true, vibration = true))
        advanceUntilIdle()

        assertEquals(listOf("c2", "c3", "c1", "c4"), restored.orderOrNull())
        restored.effects.test {
            restored.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 1, gesture = G2))

            assertEquals(
                "сессии нет — событие жеста игнорируется",
                listOf("c2", "c3", "c1", "c4"),
                restored.orderOrNull(),
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * `I6-V10`. Устаревшие и чужие события не делают ничего: неизвестный `cardId`,
     * завершение без сессии, верный `cardId` с чужим жестом и верный жест с чужим
     * `cardId`.
     */
    @Test
    fun `I6-V10 stale and foreign gesture events are ignored`() = runTest(dispatcher) {
        val handle = orderedHandle(listOf("c1", "c2", "c3", "c4"))
        val viewModel = createViewModel(handle, feedbackSettings(sound = true, vibration = true))
        advanceUntilIdle()
        // Ключ порядка поставил маршрут; дальше любая запись видна как его появление.
        handle.remove<String>(KEY_CURRENT_ORDER)

        viewModel.effects.test {
            // Завершение без сессии и неизвестная карточка.
            viewModel.onEvent(PuzzleEvent.DragFinished("c1", G1))
            viewModel.onEvent(PuzzleEvent.DragStarted("c9", G1))
            viewModel.onEvent(PuzzleEvent.DragMovedTo("c9", targetIndex = 0, gesture = G1))
            expectNoEvents()

            // Сессия есть; события с чужим жестом и с чужой карточкой не проходят.
            viewModel.onEvent(PuzzleEvent.DragStarted("c1", G1))
            assertEquals(grabbed(), awaitItem())

            viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 2, gesture = G2))
            viewModel.onEvent(PuzzleEvent.DragMovedTo("c2", targetIndex = 2, gesture = G1))
            viewModel.onEvent(PuzzleEvent.DragFinished("c1", G2))
            viewModel.onEvent(PuzzleEvent.DragFinished("c2", G1))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("c1", "c2", "c3", "c4"), viewModel.orderOrNull())
        assertNull("ни одной записи порядка", handle.get<String>(KEY_CURRENT_ORDER))

        // Сессия по-прежнему открыта: своё событие проходит.
        viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 1, gesture = G1))
        assertEquals(listOf("c2", "c1", "c3", "c4"), viewModel.orderOrNull())
    }

    /**
     * `I6-V11`. Кнопка при открытой сессии: одна кнопочная перестановка со своим
     * объявлением, сессия закрыта молча, следующее событие прежнего жеста игнорируется.
     */
    @Test
    fun `I6-V11 a button press silently closes an open drag session`() = runTest(dispatcher) {
        val viewModel = draggingViewModel("c1", order = listOf("c1", "c2", "c3", "c4"))

        viewModel.effects.test {
            assertEquals(grabbed(), awaitItem())
            viewModel.onEvent(PuzzleEvent.MoveDown("c3"))

            assertEquals(moved(), awaitItem())
            val announcement = awaitItem() as PuzzleEffect.AnnounceCardMoved
            assertEquals("Килиманджаро", announcement.cardTitle)
            assertEquals(4, announcement.position)

            // Сессия закрыта: событие жеста, начатого до кнопки, больше не принимается.
            viewModel.onEvent(PuzzleEvent.DragMovedTo("c1", targetIndex = 3, gesture = G1))
            viewModel.onEvent(PuzzleEvent.DragFinished("c1", G1))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("c1", "c2", "c4", "c3"), viewModel.orderOrNull())
    }

    /**
     * `I6-V12`. Кнопочный контракт итерации 5 не изменился: `Feedback(CardMoved)`, затем
     * `AnnounceCardMoved`; на краю — ничего.
     */
    @Test
    fun `I6-V12 the button contract of iteration 5 is unchanged`() = runTest(dispatcher) {
        val viewModel = playingViewModel(
            order = listOf("c1", "c2", "c3", "c4"),
            feedback = feedbackSettings(sound = true, vibration = true),
        )

        viewModel.effects.test {
            viewModel.onEvent(PuzzleEvent.MoveUp("c2"))

            assertEquals(moved(), awaitItem())
            assertTrue(awaitItem() is PuzzleEffect.AnnounceCardMoved)

            // Край списка: ни отдачи, ни объявления.
            viewModel.onEvent(PuzzleEvent.MoveUp("c2"))
            viewModel.onEvent(PuzzleEvent.MoveDown("c4"))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Инфраструктура ----------------------------------------------------------------

    /**
     * @param feedback настройки отдачи. По умолчанию DataStore не эмитит вовсе, поэтому
     * настройки остаются `Unknown` и отдачи нет (`FeedbackPolicy`): тесты игрового цикла
     * итерации 3 проверяют свои эффекты без неё, а включают её тесты `I5-V29`…`I5-V34`.
     */
    private fun createViewModel(
        handle: SavedStateHandle,
        feedback: UserPreferencesRepository = ControllablePreferences(initial = null),
    ) = PuzzleViewModel(
        getPuzzle = GetPuzzleUseCase(content, assignments, sets, puzzles, progress),
        // FakeProgress не хранит day_results: день для флага первого дня никогда не
        // завершён, и настройки SubmitAnswerUseCase здесь не читаются вовсе.
        submitAnswer = SubmitAnswerUseCase(assignments, sets, puzzles, progress, FakeUserPreferencesRepository()),
        observeFeedbackSettings = ObserveFeedbackSettingsUseCase(feedback),
        savedStateHandle = handle,
    )

    /** ViewModel в `Playing` с нужным порядком карточек. */
    private fun kotlinx.coroutines.test.TestScope.playingViewModel(
        order: List<String>? = null,
        feedback: UserPreferencesRepository = ControllablePreferences(initial = null),
    ): PuzzleViewModel {
        val handle = if (order == null) {
            routeHandle()
        } else {
            routeHandle(
                KEY_CURRENT_ORDER to order.joinToString(","),
                KEY_ORDER_PUZZLE_ID to PuzzleFixtures.PUZZLE_ID,
            )
        }
        val viewModel = createViewModel(handle, feedback)
        advanceUntilIdle()
        check(viewModel.uiState.value is PuzzleUiState.Playing) { "ожидалось Playing" }
        return viewModel
    }

    /** Настройки отдачи, прочитанные из DataStore: оба канала по флагам. */
    private fun feedbackSettings(sound: Boolean, vibration: Boolean) = ControllablePreferences(
        ControllablePreferences.defaults(soundEnabled = sound, vibrationEnabled = vibration),
    )

    /** `SavedStateHandle` маршрута с заранее заданным порядком карточек. */
    private fun orderedHandle(order: List<String>) = routeHandle(
        KEY_CURRENT_ORDER to order.joinToString(","),
        KEY_ORDER_PUZZLE_ID to PuzzleFixtures.PUZZLE_ID,
    )

    /** ViewModel в `Playing` с уже принятым жестом [G1] над карточкой [cardId]. */
    private fun kotlinx.coroutines.test.TestScope.draggingViewModel(
        cardId: String,
        order: List<String>,
    ): PuzzleViewModel {
        val viewModel = playingViewModel(
            order = order,
            feedback = feedbackSettings(sound = true, vibration = true),
        )
        viewModel.onEvent(PuzzleEvent.DragStarted(cardId, G1))
        return viewModel
    }

    /** Полная последовательность событий одного жеста — для проверок «ничего не произошло». */
    private fun dragSequence(): List<PuzzleEvent> = listOf(
        PuzzleEvent.DragStarted("c1", G1),
        PuzzleEvent.DragMovedTo("c1", targetIndex = 3, gesture = G1),
        PuzzleEvent.DragFinished("c1", G1),
    )

    private fun grabbed() = PuzzleEffect.Feedback(
        FeedbackRequest(FeedbackCue.CardGrabbed, playSound = false, performHaptic = true),
    )

    private fun moved() = PuzzleEffect.Feedback(
        FeedbackRequest(FeedbackCue.CardMoved, playSound = true, performHaptic = true),
    )

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

        /** Два разных жеста: идентификаторы сравниваются, а не пересоздаются генератором. */
        val G1 = DragGestureId(1)
        val G2 = DragGestureId(2)

        /** Сколько раз повторяется заведомо пустая операция. */
        const val REPEATS = 3
    }
}
