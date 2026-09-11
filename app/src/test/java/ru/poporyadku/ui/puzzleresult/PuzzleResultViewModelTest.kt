package ru.poporyadku.ui.puzzleresult

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import ru.poporyadku.core.model.AppBuildInfo
import ru.poporyadku.core.model.StreakCache
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.domain.model.InstalledContentVersion
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.usecase.GetInstalledContentVersionUseCase
import ru.poporyadku.domain.usecase.GetPuzzleResultUseCase
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.ui.navigation.Destinations
import ru.poporyadku.ui.navigation.RouteOrigin
import ru.poporyadku.ui.puzzle.FakeAssignments
import ru.poporyadku.ui.puzzle.FakeProgress
import ru.poporyadku.ui.puzzle.FakePuzzles
import ru.poporyadku.ui.puzzle.PuzzleFixtures
import ru.poporyadku.ui.puzzle.RouteArgError
import ru.poporyadku.ui.report.ReportContext

/**
 * `PuzzleResultViewModel` — ITERATION_3_DESIGN.md, `I3-V16` плюс все четыре исхода
 * `PuzzleResultLoad` и навигация последнего слота; ITERATION_5_DESIGN.md, §3.7, §4.4:
 * архивный режим `I5-V16`…`I5-V18` и разбор `origin`; PR 5C — «Сообщить о неточности»
 * `I5-V26`.
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
        assertEquals(RouteOrigin.Session, content.origin)
        assertFalse(content.isRetired)
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
            getPuzzleResult = useCase(),
            preferences = preferences,
            appBuildInfo = APP,
            getInstalledContentVersion = GetInstalledContentVersionUseCase(preferences),
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

    /** Неизвестный `origin` — тоже невалидный маршрут: ни один режим не угадывается. */
    @Test
    fun `unknown origin is an invalid route that returns home`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val viewModel = createViewModel(slotIndex = 0, origin = "settings")
        advanceUntilIdle()

        assertEquals(PuzzleResultState.Error(PuzzleErrorKind.InvalidRoute), viewModel.uiState.value)
        viewModel.effects.test {
            assertEquals(PuzzleResultEffect.NavigateHome, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("база не читалась", assignments.queries.isEmpty())
    }

    /** Разбор маршрута результата: сначала дата и слот, затем `origin`. */
    @Test
    fun `readResultRoute parses origin after the strict date and slot`() {
        fun parse(vararg args: Pair<String, Any?>) = SavedStateHandle(mapOf(*args)).readResultRoute()
        val date = Destinations.serialize(PuzzleFixtures.date)

        assertEquals(
            ResultRouteArgs.Valid(1, PuzzleFixtures.date, RouteOrigin.Session),
            parse(Destinations.ARG_SLOT_INDEX to 1, Destinations.ARG_DATE to date),
        )
        assertEquals(
            ResultRouteArgs.Valid(1, PuzzleFixtures.date, RouteOrigin.Archive),
            parse(Destinations.ARG_SLOT_INDEX to 1, Destinations.ARG_DATE to date, Destinations.ARG_ORIGIN to "archive"),
        )
        assertEquals(
            ResultRouteArgs.Invalid(ResultRouteError.OriginMalformed),
            parse(Destinations.ARG_SLOT_INDEX to 1, Destinations.ARG_DATE to date, Destinations.ARG_ORIGIN to "Archive"),
        )
        // Порча даты или слота обнаруживается раньше origin — тем же разбором, что у Puzzle.
        assertEquals(
            ResultRouteArgs.Invalid(ResultRouteError.Base(RouteArgError.DateMalformed)),
            parse(Destinations.ARG_SLOT_INDEX to 1, Destinations.ARG_DATE to "вчера", Destinations.ARG_ORIGIN to "bad"),
        )
    }

    // --- I5-V16 / I5-V17 / I5-V18: архивный режим ------------------------------------------

    /** `I5-V16`. Архивный результат: CTA «К итогу дня» и «Назад» в шапке → `NavigateBack`. */
    @Test
    fun `I5-V16 archive CTA and back both go back to the archived recap`() = runTest(dispatcher) {
        // Слот 0: в сессии CTA вёл бы к следующему слоту — в архиве только назад.
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val viewModel = createViewModel(slotIndex = 0, origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        val content = viewModel.uiState.value as PuzzleResultState.Content
        assertEquals(RouteOrigin.Archive, content.origin)

        viewModel.effects.test {
            viewModel.onEvent(PuzzleResultEvent.PrimaryAction)
            assertEquals(PuzzleResultEffect.NavigateBack(isRedirect = false), awaitItem())
            viewModel.onEvent(PuzzleResultEvent.BackPressed)
            assertEquals(PuzzleResultEffect.NavigateBack(isRedirect = false), awaitItem())
            expectNoEvents()
        }
    }

    /**
     * `I5-V17`. Архивный режим ни при каком исходе не отправляет `NavigateToPuzzle`,
     * `NavigateToNextSlot` и `NavigateToRecap`: `NoAttempt` и `Skipped` — назад без кадра,
     * `Content` и `Failure` — назад по нажатию. Игра прошлого дня не запускается.
     */
    @Test
    fun `I5-V17 no archive outcome ever leads forward into the day`() = runTest(dispatcher) {
        val collected = mutableListOf<PuzzleResultEffect>()

        // NoAttempt: слот не сыгран — назад, а не в головоломку.
        setUp()
        createViewModel(slotIndex = 1, origin = Destinations.ORIGIN_ARCHIVE).also { vm ->
            advanceUntilIdle()
            assertEquals(PuzzleResultState.Loading, vm.uiState.value)
            vm.effects.test {
                assertEquals(PuzzleResultEffect.NavigateBack(isRedirect = true), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

        // Skipped, в том числе последнего слота: назад, а не к следующему слоту и не в итог.
        for (slot in 0..2) {
            setUp()
            progress.close(PuzzleFixtures.date, slotIndex = slot, submittedOrder = emptyList())
            createViewModel(slotIndex = slot, origin = Destinations.ORIGIN_ARCHIVE).also { vm ->
                advanceUntilIdle()
                assertEquals(PuzzleResultState.Loading, vm.uiState.value)
                vm.effects.test {
                    assertEquals("Skipped, слот $slot", PuzzleResultEffect.NavigateBack(isRedirect = true), awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // Content каждого слота и Failure: все действия экрана.
        for (slot in 0..2) {
            setUp()
            givenAnsweredSlot(slotIndex = slot, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
            createViewModel(slotIndex = slot, origin = Destinations.ORIGIN_ARCHIVE).also { vm ->
                advanceUntilIdle()
                vm.onEvent(PuzzleResultEvent.PrimaryAction)
                vm.onEvent(PuzzleResultEvent.BackPressed)
                vm.effects.test {
                    collected += awaitItem()
                    collected += awaitItem()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
        setUp()
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        puzzles.remove(PuzzleFixtures.PUZZLE_ID)
        createViewModel(slotIndex = 0, origin = Destinations.ORIGIN_ARCHIVE).also { vm ->
            advanceUntilIdle()
            assertEquals(PuzzleResultState.Error(PuzzleErrorKind.PuzzleNotFound), vm.uiState.value)
            vm.onEvent(PuzzleResultEvent.PrimaryAction) // кнопки нет, событие не принимается
            vm.onEvent(PuzzleResultEvent.BackPressed)
            vm.effects.test {
                collected += awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        }

        assertEquals(List(7) { PuzzleResultEffect.NavigateBack(isRedirect = false) }, collected)
        assertTrue(
            "в архивном режиме нет переходов вперёд: $collected",
            collected.none {
                it is PuzzleResultEffect.NavigateToPuzzle ||
                    it is PuzzleResultEffect.NavigateToNextSlot ||
                    it == PuzzleResultEffect.NavigateToRecap
            },
        )
        assertTrue("архивный результат ничего не записывает", progress.recorded.isEmpty())
    }

    /** `I5-V18`. `isRetired` доменного исхода доезжает до `PuzzleResultState.Content`. */
    @Test
    fun `I5-V18 the retired flag of the domain outcome reaches the screen state`() = runTest(dispatcher) {
        preferences.setInstalled(version = 2)
        puzzles.put(PuzzleFixtures.puzzle.copy(retiredIn = 2, contentVersion = 2))
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val archived = createViewModel(slotIndex = 0, origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        val content = archived.uiState.value as PuzzleResultState.Content
        assertTrue(content.isRetired)
        // Полный результат: правильный порядок, счёт и объяснение на месте.
        assertEquals(4, content.correctOrder.size)
        assertEquals(6, content.score)
        assertEquals(PuzzleFixtures.PUZZLE_ID, content.puzzleId)

        // Та же головоломка при установленной версии 1 (откат) отозванной не считается.
        setUp()
        preferences.setInstalled(version = 1)
        puzzles.put(PuzzleFixtures.puzzle.copy(retiredIn = 2, contentVersion = 2))
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val rolledBack = createViewModel(slotIndex = 0)
        advanceUntilIdle()
        assertFalse((rolledBack.uiState.value as PuzzleResultState.Content).isRetired)
    }

    // --- I5-V26: «Сообщить о неточности» -------------------------------------------------

    /**
     * `I5-V26`. `ReportClicked` на показанном результате — ровно один `ComposeReport` с
     * `puzzleId` из ПОПЫТКИ, версией приложения и установленной версией контента; в
     * сессии и в архиве одинаково. Навигационных эффектов при этом нет.
     */
    @Test
    fun `I5-V26 report click emits exactly one ComposeReport with the attempt puzzle id`() = runTest(dispatcher) {
        for (origin in listOf(null, Destinations.ORIGIN_ARCHIVE)) {
            setUp()
            preferences.setInstalled(version = 3)
            givenAnsweredSlot(slotIndex = 1, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
            val viewModel = createViewModel(slotIndex = 1, origin = origin)
            advanceUntilIdle()
            val content = viewModel.uiState.value as PuzzleResultState.Content

            viewModel.effects.test {
                viewModel.onEvent(PuzzleResultEvent.ReportClicked)
                assertEquals(
                    "origin=$origin",
                    PuzzleResultEffect.ComposeReport(
                        ReportContext(
                            puzzleId = content.puzzleId,
                            app = APP,
                            content = InstalledContentVersion.Known(3),
                        ),
                    ),
                    awaitItem(),
                )
                expectNoEvents()
            }
            assertEquals(PuzzleFixtures.PUZZLE_ID, content.puzzleId)
            assertTrue("письмо ничего не записывает", progress.recorded.isEmpty())
        }
    }

    /** `I5-V26`. Без показанного результата (загрузка, ошибка) действия нет — эффекта тоже. */
    @Test
    fun `I5-V26 report click without content emits nothing`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        puzzles.remove(PuzzleFixtures.PUZZLE_ID)
        val viewModel = createViewModel(slotIndex = 0)
        advanceUntilIdle()
        assertEquals(PuzzleResultState.Error(PuzzleErrorKind.PuzzleNotFound), viewModel.uiState.value)

        viewModel.effects.test {
            viewModel.onEvent(PuzzleResultEvent.ReportClicked)
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    /**
     * `I5-V26`. Отказ чтения версии контента не роняет процесс и не отменяет письмо: строка
     * версии — «не установлена» (`Unknown`).
     */
    @Test
    fun `I5-V26 a failed content version read still composes the report`() = runTest(dispatcher) {
        givenAnsweredSlot(slotIndex = 0, submittedOrder = listOf("c2", "c1", "c3", "c4"), score = 6)
        val failingVersion = GetInstalledContentVersionUseCase(ThrowingPreferences())
        val viewModel = createViewModel(slotIndex = 0, installedContentVersion = failingVersion)
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onEvent(PuzzleResultEvent.ReportClicked)
            val effect = awaitItem() as PuzzleResultEffect.ComposeReport
            assertEquals(InstalledContentVersion.Unknown, effect.context.content)
            assertEquals(PuzzleFixtures.PUZZLE_ID, effect.context.puzzleId)
            expectNoEvents()
        }
    }

    // --- Инфраструктура -----------------------------------------------------------------

    private fun givenAnsweredSlot(slotIndex: Int, submittedOrder: List<String>, score: Int) {
        progress.close(PuzzleFixtures.date, slotIndex, submittedOrder, score)
    }

    private fun useCase() = GetPuzzleResultUseCase(
        assignments = assignments,
        puzzles = puzzles,
        progress = progress,
        getInstalledContentVersion = GetInstalledContentVersionUseCase(preferences),
    )

    private fun createViewModel(
        slotIndex: Int,
        origin: String? = null,
        installedContentVersion: GetInstalledContentVersionUseCase = GetInstalledContentVersionUseCase(preferences),
    ) = PuzzleResultViewModel(
        getPuzzleResult = useCase(),
        preferences = preferences,
        appBuildInfo = APP,
        getInstalledContentVersion = installedContentVersion,
        savedStateHandle = SavedStateHandle(
            buildMap {
                put(Destinations.ARG_SLOT_INDEX, slotIndex)
                put(Destinations.ARG_DATE, Destinations.serialize(PuzzleFixtures.date))
                if (origin != null) put(Destinations.ARG_ORIGIN, origin)
            },
        ),
    )

    private companion object {
        /** C(4,2) — столько пар у четырёх карточек. */
        const val MAX_PAIRS = 6

        val APP = AppBuildInfo(versionName = "9.9.9", versionCode = 99)
    }
}

/** Настройки, чтение которых падает: версия контента для письма недоступна. */
private class ThrowingPreferences : UserPreferencesRepository by FakePreferences() {
    override val preferences: Flow<UserPreferences> = flow { throw IllegalStateException("DataStore недоступен") }
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
            storedContentFingerprint = null,
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

    /** Отметка установленного контента — та, что пишет импортёр одной операцией с отпечатком. */
    fun setInstalled(version: Int) {
        state.value = state.value.copy(storedContentVersion = version, storedContentFingerprint = "fingerprint-$version")
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
    override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) = unsupported()
    override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
    override suspend fun setHasCompletedFirstDay(completed: Boolean) = unsupported()
    override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
    override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()
    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("не нужен в этом тесте")
}
