package ru.poporyadku.ui.recap

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import ru.poporyadku.ui.navigation.RouteOrigin
import ru.poporyadku.ui.share.ShareCardInput

/**
 * `DayRecapViewModel` — ITERATION_3_DESIGN.md, `I3-V34` (источник `today`, I3-D51);
 * ITERATION_5_DESIGN.md, §6.8: `I5-V11`…`I5-V15`, `I5-V35`.
 *
 * `GetDayRecapUseCase` — final-класс, поэтому подменяется не он, а его репозитории:
 * какие даты в него ушли, видно по записям фейков. `localDate` попадает в
 * `progress.getDayResult`/`getAttempts` и `assignments.getAssignment`; `today` с PR 5B в
 * use case не входит вовсе и нужен только заголовку сессионного варианта.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayRecapViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Архивная дата D в маршруте. */
    private val archiveDate = LocalDate.of(2026, 8, 20)

    /** `DateProvider.today()` отдаёт D + 5. */
    private val currentDate = archiveDate.plusDays(5)

    private lateinit var assignments: RecordingAssignments
    private lateinit var progress: RecordingProgress
    private lateinit var puzzles: FakePuzzleRepository
    private lateinit var dateProvider: RecordingDateProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        assignments = RecordingAssignments()
        progress = RecordingProgress()
        puzzles = FakePuzzleRepository()
        dateProvider = RecordingDateProvider(currentDate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- I3-V34 ------------------------------------------------------------------------

    /**
     * `I3-V34`. Маршрут несёт архивную дату D, `DateProvider` отдаёт D + 5: день читается
     * ровно по дате маршрута, `today` читается ровно один раз и управляет только
     * заголовком — архивный день показывает дату, а не «Сегодня».
     */
    @Test
    fun `I3-V34 route date is never replaced by today`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 14)
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate))

        viewModel.uiState.test {
            assertEquals(DayRecapState.Loading, awaitItem())
            val content = awaitItem() as DayRecapState.Content

            // Дата маршрута доехала до всех чтений дня без подмены.
            assertEquals(listOf(archiveDate), progress.dayResultQueries)
            assertEquals(listOf(archiveDate), progress.attemptQueries)
            assertEquals(listOf(archiveDate), assignments.assignmentQueries)

            // `today` — только из DateProvider, ровно одно чтение на загрузку.
            assertEquals(1, dateProvider.reads)

            assertEquals(DayRecapTitle.Date(archiveDate), content.title)
            assertEquals(14, content.totalScore)
            assertEquals(3, content.slots.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Сегодняшний сессионный итог показывает заголовок «Сегодня». */
    @Test
    fun `today session recap uses the Today title`() = runTest(dispatcher) {
        givenCompletedDay(currentDate, totalScore = 18)
        val viewModel = createViewModel(date = Destinations.serialize(currentDate))

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
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate))

        viewModel.uiState.test {
            skipItems(1)
            val content = awaitItem() as DayRecapState.Content
            assertEquals(
                listOf<SlotResultUi>(
                    SlotResultUi.Played(0, 6, Category.GEOGRAPHY, isOpenable = false),
                    SlotResultUi.Unavailable(1, 0),
                    SlotResultUi.Unavailable(2, 4),
                ),
                content.slots,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Дня нет в `day_results` → `NotFound` своего варианта. */
    @Test
    fun `missing day publishes NotFound of its origin`() = runTest(dispatcher) {
        val session = createViewModel(date = Destinations.serialize(archiveDate))
        val archive = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        assertEquals(DayRecapState.NotFound(RouteOrigin.Session), session.uiState.value)
        assertEquals(DayRecapState.NotFound(RouteOrigin.Archive), archive.uiState.value)
    }

    /** Аргумент отсутствует — `NotFound`, а не подстановка сегодняшней даты. */
    @Test
    fun `absent route argument publishes NotFound without touching the date provider`() = runTest(dispatcher) {
        val viewModel = createViewModel(date = null)

        viewModel.uiState.test {
            assertEquals(DayRecapState.NotFound(RouteOrigin.Session), awaitItem())
            runCurrent()
            assertEquals("подмены на «сегодня» нет", 0, dateProvider.reads)
            assertTrue(progress.dayResultQueries.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Повреждённый аргумент — тоже `NotFound`. */
    @Test
    fun `malformed route argument publishes NotFound`() = runTest(dispatcher) {
        val viewModel = createViewModel(date = "вчера")

        viewModel.uiState.test {
            assertEquals(DayRecapState.NotFound(RouteOrigin.Session), awaitItem())
            runCurrent()
            assertEquals(0, dateProvider.reads)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Маппер переносит `isRecordUpdated` без изменений и не считает его сам. */
    @Test
    fun `mapper carries isRecordUpdated and the streak of that day as is`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 18)
        // Единственный завершённый день в истории — рекорд установлен именно им.
        progress.completedDates = listOf(archiveDate)
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate))

        viewModel.uiState.test {
            skipItems(1)
            val content = awaitItem() as DayRecapState.Content
            assertTrue(content.isRecordUpdated)
            assertEquals(1, content.streakDays)
            assertTrue(content.canShare)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I5-V11 / I5-V12: вариант по origin ---------------------------------------------

    /**
     * `I5-V11`. `origin=archive`: вариант `Archive`, заголовок — дата и для СЕГОДНЯШНЕГО
     * дня; строки `Played` открываются; «Назад» внизу → `NavigateBack`.
     */
    @Test
    fun `I5-V11 archive origin shows the date even for today and primary goes back`() = runTest(dispatcher) {
        givenCompletedDay(currentDate, totalScore = 16)
        val viewModel = createViewModel(date = Destinations.serialize(currentDate), origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        val content = viewModel.uiState.value as DayRecapState.Content
        assertEquals(RouteOrigin.Archive, content.origin)
        assertEquals("вариант выбирает origin, а не дата", DayRecapTitle.Date(currentDate), content.title)
        assertTrue(content.slots.all { it is SlotResultUi.Played && it.isOpenable })

        viewModel.effects.test {
            viewModel.onEvent(DayRecapEvent.PrimaryClicked)
            assertEquals(DayRecapEffect.NavigateBack, awaitItem())
            viewModel.onEvent(DayRecapEvent.BackClicked)
            assertEquals(DayRecapEffect.NavigateBack, awaitItem())
            expectNoEvents()
        }
    }

    /** `I5-V12`. Без `origin`: `Session`, строки не открываются, «Готово» → `NavigateHome` (I3). */
    @Test
    fun `I5-V12 no origin is the session variant and primary goes home`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 12)
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate))
        advanceUntilIdle()

        val content = viewModel.uiState.value as DayRecapState.Content
        assertEquals(RouteOrigin.Session, content.origin)
        assertTrue(content.slots.none { it is SlotResultUi.Played && it.isOpenable })

        viewModel.effects.test {
            viewModel.onEvent(DayRecapEvent.PrimaryClicked)
            assertEquals(DayRecapEffect.NavigateHome, awaitItem())
            expectNoEvents()
        }
    }

    /** `I5-V13`. Неизвестный `origin` → `NotFound(Session)`, use case не вызывался. */
    @Test
    fun `I5-V13 unknown origin is NotFound without calling the use case`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 12)
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate), origin = "settings")
        advanceUntilIdle()

        assertEquals(DayRecapState.NotFound(RouteOrigin.Session), viewModel.uiState.value)
        assertTrue("база не читалась", progress.dayResultQueries.isEmpty())
        assertTrue(assignments.assignmentQueries.isEmpty())
        assertEquals(0, dateProvider.reads)
    }

    /** `I5-V14`. Исключение чтения дня → `NotFound(origin)`, процесс не падает. */
    @Test
    fun `I5-V14 a failing read becomes NotFound of its origin`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 12)
        progress.failWith = { IOException("база недоступна") }

        val archive = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        val session = createViewModel(date = Destinations.serialize(archiveDate))
        advanceUntilIdle()

        assertEquals(DayRecapState.NotFound(RouteOrigin.Archive), archive.uiState.value)
        assertEquals(DayRecapState.NotFound(RouteOrigin.Session), session.uiState.value)
    }

    /** `I5-V14`. Отмена ошибкой не становится: экран остаётся в `Loading`. */
    @Test
    fun `I5-V14 cancellation during the read is not NotFound`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 12)
        progress.failWith = { CancellationException("скоуп отменён") }

        val viewModel = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        assertEquals(DayRecapState.Loading, viewModel.uiState.value)
    }

    // --- I5-V15: какие строки нажимаются -------------------------------------------------

    @Test
    fun `I5-V15 only Played of the archive variant opens a historical result`() = runTest(dispatcher) {
        progress.dayResult = DayResult(archiveDate, totalScore = 6, completedCount = 2, isComplete = false, completedAt = null)
        assignments.assignment = DayAssignment(archiveDate, PACK, setIndex = 2, assignedAt = 0L)
        progress.attempts = listOf(
            attempt(slot = 0, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 6),
            attempt(slot = 1, puzzleId = "p1", order = emptyList(), score = 0),
            // Слот 2 без попытки — NotPlayed.
        )

        val archive = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        val session = createViewModel(date = Destinations.serialize(archiveDate))
        advanceUntilIdle()
        assertEquals(
            listOf(
                SlotResultUi.Played(0, 6, Category.GEOGRAPHY, isOpenable = true),
                SlotResultUi.Unavailable(1, 0),
                SlotResultUi.NotPlayed(2),
            ),
            (archive.uiState.value as DayRecapState.Content).slots,
        )

        archive.effects.test {
            archive.onEvent(DayRecapEvent.SlotClicked(1)) // Unavailable
            archive.onEvent(DayRecapEvent.SlotClicked(2)) // NotPlayed
            archive.onEvent(DayRecapEvent.SlotClicked(0)) // Played
            assertEquals(DayRecapEffect.OpenResult(slotIndex = 0, localDate = archiveDate), awaitItem())
            expectNoEvents()
        }

        session.effects.test {
            session.onEvent(DayRecapEvent.SlotClicked(0))
            session.onEvent(DayRecapEvent.SlotClicked(1))
            session.onEvent(DayRecapEvent.SlotClicked(2))
            expectNoEvents()
        }
    }

    /** Незавершённый день: серии нет, «Поделиться» недоступно. */
    @Test
    fun `an incomplete day has no streak and cannot be shared`() = runTest(dispatcher) {
        progress.dayResult = DayResult(archiveDate, totalScore = 6, completedCount = 1, isComplete = false, completedAt = null)
        assignments.assignment = DayAssignment(archiveDate, PACK, setIndex = 1, assignedAt = 0L)
        progress.attempts = listOf(attempt(slot = 0, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 6))
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        val content = viewModel.uiState.value as DayRecapState.Content
        assertEquals(false, content.isComplete)
        assertNull(content.streakDays)
        assertEquals(false, content.canShare)
        assertEquals(listOf(SlotResultUi.NotPlayed(1), SlotResultUi.NotPlayed(2)), content.slots.drop(1))
    }

    // --- I5-V28: «Поделиться» ------------------------------------------------------------

    /**
     * `I5-V28`. Завершённый день даёт ровно один `Share` и в сессионном, и в архивном
     * варианте; вход несёт номер дня, три счёта в порядке слотов 0..2 и серию ЭТОГО дня.
     * Общего счёта во входе нет — его считает `ShareCardBuilder`.
     */
    @Test
    fun `I5-V28 a completed day shares the same card from session and from archive`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 14)
        val expected = ShareCardInput(dayNumber = 3, slotScores = listOf(6, 4, 4), streakDays = 1)

        val session = createViewModel(date = Destinations.serialize(archiveDate))
        val archive = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        session.effects.test {
            session.onEvent(DayRecapEvent.ShareClicked)
            assertEquals(DayRecapEffect.Share(expected), awaitItem())
            expectNoEvents()
        }
        archive.effects.test {
            archive.onEvent(DayRecapEvent.ShareClicked)
            assertEquals(DayRecapEffect.Share(expected), awaitItem())
            expectNoEvents()
        }
    }

    /** `I5-V28`. `Unavailable` входит в карточку своим фактическим счётом, а не нулём. */
    @Test
    fun `I5-V28 an unavailable slot contributes its actual score`() = runTest(dispatcher) {
        progress.dayResult = DayResult(archiveDate, totalScore = 11, completedCount = 3, isComplete = true, completedAt = 1L)
        assignments.assignment = DayAssignment(archiveDate, PACK, setIndex = 5, assignedAt = 0L)
        progress.attempts = listOf(
            attempt(slot = 0, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 6),
            // Головоломка недоступна (id не читается), но попытка была и счёт у неё есть.
            attempt(slot = 1, puzzleId = "missing", order = listOf("a", "b"), score = 2),
            attempt(slot = 2, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 3),
        )
        progress.completedDates = listOf(archiveDate)
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate))
        advanceUntilIdle()

        val content = viewModel.uiState.value as DayRecapState.Content
        assertTrue(content.slots[1] is SlotResultUi.Unavailable)

        viewModel.effects.test {
            viewModel.onEvent(DayRecapEvent.ShareClicked)
            assertEquals(
                DayRecapEffect.Share(ShareCardInput(dayNumber = 6, slotScores = listOf(6, 2, 3), streakDays = 1)),
                awaitItem(),
            )
            expectNoEvents()
        }
    }

    /** `I5-V28`. У незавершённого дня кнопки нет, и событие эффекта не создаёт. */
    @Test
    fun `I5-V28 an incomplete day shares nothing`() = runTest(dispatcher) {
        progress.dayResult = DayResult(archiveDate, totalScore = 6, completedCount = 1, isComplete = false, completedAt = null)
        assignments.assignment = DayAssignment(archiveDate, PACK, setIndex = 1, assignedAt = 0L)
        progress.attempts = listOf(attempt(slot = 0, puzzleId = "p1", order = listOf("a", "b", "c", "d"), score = 6))
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        assertEquals(false, (viewModel.uiState.value as DayRecapState.Content).canShare)

        viewModel.effects.test {
            viewModel.onEvent(DayRecapEvent.ShareClicked)
            expectNoEvents()
        }
    }

    /**
     * `I5-V28`. Одно нажатие — ровно один эффект: два принятых нажатия дают два эффекта и
     * ни одного лишнего, скрытого повтора у `Channel` нет. Отсечение второго запуска —
     * работа `ExternalApps`, а не ViewModel.
     */
    @Test
    fun `I5-V28 each accepted click produces exactly one effect`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 14)
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate))
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onEvent(DayRecapEvent.ShareClicked)
            viewModel.onEvent(DayRecapEvent.ShareClicked)
            assertTrue(awaitItem() is DayRecapEffect.Share)
            assertTrue(awaitItem() is DayRecapEffect.Share)
            expectNoEvents()
        }
    }

    /** `I5-V28`. До загрузки дня делиться нечем: у `Loading` эффекта нет. */
    @Test
    fun `I5-V28 sharing before the day is loaded produces nothing`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 14)
        val viewModel = createViewModel(date = Destinations.serialize(archiveDate))

        assertEquals(DayRecapState.Loading, viewModel.uiState.value)
        viewModel.effects.test {
            viewModel.onEvent(DayRecapEvent.ShareClicked)
            expectNoEvents()
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    /** `I5-V28`. Навигационные эффекты не изменились: «Поделиться» их не подменяет. */
    @Test
    fun `I5-V28 navigation effects are unchanged`() = runTest(dispatcher) {
        givenCompletedDay(archiveDate, totalScore = 14)
        val session = createViewModel(date = Destinations.serialize(archiveDate))
        val archive = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()

        session.effects.test {
            session.onEvent(DayRecapEvent.PrimaryClicked)
            assertEquals(DayRecapEffect.NavigateHome, awaitItem())
            expectNoEvents()
        }
        archive.effects.test {
            archive.onEvent(DayRecapEvent.BackClicked)
            assertEquals(DayRecapEffect.NavigateBack, awaitItem())
            archive.onEvent(DayRecapEvent.SlotClicked(0))
            assertEquals(DayRecapEffect.OpenResult(slotIndex = 0, localDate = archiveDate), awaitItem())
            expectNoEvents()
        }
    }

    // --- I5-V35: загрузка итога не пишет в DataStore -------------------------------------

    /**
     * `I5-V35`. Итог завершённого дня загружен и в сессионном, и в архивном варианте;
     * настройки, чей сеттер флага и запись кэша серии бросают, на загрузку не влияют:
     * экран остаётся `Content`, в `NotFound` не превращается, ни один сеттер не вызван.
     *
     * Настройки здесь — ловушка, а не зависимость: ни `DayRecapViewModel`, ни
     * `GetDayRecapUseCase` не принимают `UserPreferencesRepository` (I5-D31), поэтому
     * передать её в путь загрузки просто некуда. Тест фиксирует это наблюдаемо — счётчиком
     * вызовов — на тех же данных, на которых итерация 3 писала флаг и `StreakCache`.
     */
    @Test
    fun `I5-V35 loading a completed day writes nothing to DataStore and stays Content`() = runTest(dispatcher) {
        val trap = ThrowingPreferences()
        givenCompletedDay(archiveDate, totalScore = 18)
        progress.completedDates = listOf(archiveDate)

        val session = createViewModel(date = Destinations.serialize(archiveDate))
        val archive = createViewModel(date = Destinations.serialize(archiveDate), origin = Destinations.ORIGIN_ARCHIVE)
        advanceUntilIdle()
        // И после публикации состояния ничто не заменяет готовый Content.
        runCurrent()

        assertTrue(session.uiState.value is DayRecapState.Content)
        assertTrue(archive.uiState.value is DayRecapState.Content)
        assertEquals("setHasCompletedFirstDay не вызывался", 0, trap.firstDayFlagCalls)
        assertEquals("updateStreakCache не вызывался", 0, trap.streakCacheCalls)

        // Путь загрузки не может дотянуться до DataStore: ни ViewModel, ни use case не
        // принимают ни настроек, ни их единственного писателя кэша серии.
        val loadPath = listOf(DayRecapViewModel::class.java, GetDayRecapUseCase::class.java)
        val dependencies = loadPath.flatMap { type -> type.constructors.flatMap { it.parameterTypes.toList() } }
        assertTrue(
            "в пути загрузки итога не должно быть записей DataStore: $dependencies",
            dependencies.none {
                UserPreferencesRepository::class.java.isAssignableFrom(it) || it == GetStreaksUseCase::class.java
            },
        )
    }

    // --- Инфраструктура --------------------------------------------------------------

    private fun createViewModel(date: String?, origin: String? = null): DayRecapViewModel {
        val args = buildMap<String, Any?> {
            if (date != null) put(Destinations.ARG_DATE, date)
            if (origin != null) put(Destinations.ARG_ORIGIN, origin)
        }
        return DayRecapViewModel(
            getDayRecap = GetDayRecapUseCase(
                assignments = assignments,
                puzzles = puzzles,
                progress = progress,
            ),
            dateProvider = dateProvider,
            savedStateHandle = SavedStateHandle(args),
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
        progress.completedDates = listOf(date)
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

    /** Чем ответить на чтение дня вместо чтения; `null` — читать штатно. */
    var failWith: (() -> Throwable)? = null

    val dayResultQueries = mutableListOf<LocalDate>()
    val attemptQueries = mutableListOf<LocalDate>()

    override suspend fun recordAttempt(attempt: PuzzleAttempt) =
        throw UnsupportedOperationException("DayRecap ничего не пишет")

    override suspend fun getDayResult(localDate: LocalDate): DayResult? {
        failWith?.let { throw it() }
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

/**
 * Ловушка `I5-V35`: запись флага первого дня и кэша серии бросает и считается. Итерация 3
 * писала оба значения в пути загрузки итога; с PR 5B ни одному из них там взяться неоткуда.
 */
private class ThrowingPreferences : UserPreferencesRepository {
    var firstDayFlagCalls = 0
        private set
    var streakCacheCalls = 0
        private set

    override val preferences: Flow<UserPreferences> = emptyFlow()

    override suspend fun setHasCompletedFirstDay(completed: Boolean) {
        firstDayFlagCalls++
        throw IOException("DataStore недоступен")
    }

    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) {
        streakCacheCalls++
        throw IOException("DataStore недоступен")
    }

    override suspend fun setSoundEnabled(enabled: Boolean) = unsupported()
    override suspend fun setVibrationEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderEnabled(enabled: Boolean) = unsupported()
    override suspend fun setReminderTime(time: LocalTime) = unsupported()
    override suspend fun setThemeMode(mode: ThemeMode) = unsupported()
    override suspend fun setInstalledContent(contentVersion: Int, fingerprint: String) = unsupported()
    override suspend fun setHasSeenDragHint(seen: Boolean) = unsupported()
    override suspend fun setHasSeenScoringHint(seen: Boolean) = unsupported()
    override suspend fun setNotificationPromptShown(shown: Boolean) = unsupported()
    override suspend fun setLastSeenDate(date: LocalDate?) = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("не нужен в этом тесте")
}
