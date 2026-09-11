package ru.poporyadku.ui.archive

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt
import ru.poporyadku.core.time.DateProvider
import ru.poporyadku.domain.model.ArchiveDay
import ru.poporyadku.domain.repository.ArchiveRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.usecase.GetArchiveUseCase
import ru.poporyadku.domain.usecase.GetStatisticsUseCase

/**
 * `ArchiveViewModel` — ITERATION_5_DESIGN.md, §6.1, §8.5: `I5-V1`…`I5-V10`.
 *
 * `GetArchiveUseCase` и `GetStatisticsUseCase` — final-классы, поэтому подменяются их
 * репозитории. Архив и прогресс читают одну и ту же историю в памяти: окно, разведка и
 * статистика согласованы так же, как в Room (`day_results` — источник всех трёх).
 * Отказы включаются флагами: разведка бросает на вызове, наблюдаемые потоки — при
 * следующей эмиссии, после чего завершаются, как упавший Room-`Flow`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var history: History
    private lateinit var archive: FakeArchive
    private lateinit var progress: FakeProgress
    private lateinit var dateProvider: FixedDateProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        history = History()
        archive = FakeArchive(history)
        progress = FakeProgress(history)
        dateProvider = FixedDateProvider(TODAY)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- I5-V1 / I5-V2 --------------------------------------------------------------

    @Test
    fun `I5-V1 an empty database goes from Loading to Empty`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val states = collectStates(viewModel)
        advanceUntilIdle()

        assertEquals(ArchiveState.Loading, states.first())
        assertEquals(ArchiveState.Empty, viewModel.uiState.value)
        assertTrue("пустых нулей не показываем: $states", states.none { it is ArchiveState.Content })
    }

    @Test
    fun `I5-V2 fifty one rows show the first page with CanLoadMore and statistics`() = runTest(dispatcher) {
        history.fill(count = 51)
        val viewModel = createViewModel()
        val states = collectStates(viewModel)
        advanceUntilIdle()

        assertEquals(ArchiveState.Loading, states.first())
        val content = viewModel.uiState.value as ArchiveState.Content
        assertEquals(50, content.items.size)
        assertEquals(ArchivePaging.CanLoadMore, content.paging)
        assertEquals(history.dateOf(0), content.items.first().localDate)
        assertEquals(history.dateOf(49), content.items.last().localDate)
        // Строки — из окна, а не из разведки: окно ограничено датой 50-й строки.
        assertEquals(listOf(history.dateOf(49)), archive.windowBounds)
        assertEquals(51, content.statistics.playedDays)
        assertEquals(51, content.statistics.currentStreak)
    }

    @Test
    fun `I5-V2 fifty rows or fewer end the list without a further probe`() = runTest(dispatcher) {
        history.fill(count = 50)
        val viewModel = createViewModel()
        collectStates(viewModel)
        advanceUntilIdle()

        val content = viewModel.uiState.value as ArchiveState.Content
        assertEquals(50, content.items.size)
        assertEquals(ArchivePaging.EndReached, content.paging)
        assertEquals("окно «все дни»", listOf<LocalDate?>(null), archive.windowBounds)

        // Продолжения нет — подгрузка не запускается.
        viewModel.onEvent(ArchiveEvent.EndReached)
        advanceUntilIdle()
        assertEquals(listOf<LocalDate?>(null), archive.probes)
    }

    // --- I5-V3 ---------------------------------------------------------------------------

    @Test
    fun `I5-V3 a failed first probe is Error and retry shows Content`() = runTest(dispatcher) {
        history.fill(count = 3)
        archive.probeFailure = { IOException("база недоступна") }
        val viewModel = createViewModel()
        collectStates(viewModel)
        advanceUntilIdle()
        assertEquals(ArchiveState.Error, viewModel.uiState.value)

        archive.probeFailure = null
        viewModel.onEvent(ArchiveEvent.RetryClicked)
        advanceUntilIdle()

        val content = viewModel.uiState.value as ArchiveState.Content
        assertEquals(3, content.items.size)
        assertEquals(ArchivePaging.EndReached, content.paging)
        assertEquals(listOf<LocalDate?>(null, null), archive.probes)
    }

    // --- I5-V4 / I5-V6 --------------------------------------------------------------------

    @Test
    fun `I5-V4 EndReached loads the next page and moves the boundary, I5-V6 a repeat is ignored`() =
        runTest(dispatcher) {
            history.fill(count = 101)
            val viewModel = createViewModel()
            collectStates(viewModel)
            advanceUntilIdle()
            val b1 = history.dateOf(49)
            assertEquals(ArchivePaging.CanLoadMore, (viewModel.uiState.value as ArchiveState.Content).paging)

            val gate = CompletableDeferred<Unit>()
            archive.probeGate = gate
            viewModel.onEvent(ArchiveEvent.EndReached)
            advanceUntilIdle()
            val loading = viewModel.uiState.value as ArchiveState.Content
            assertEquals(ArchivePaging.LoadingMore, loading.paging)
            assertEquals("строки на месте, пока идёт разведка", 50, loading.items.size)

            // I5-V6: повторный EndReached во время LoadingMore второй разведки не создаёт.
            viewModel.onEvent(ArchiveEvent.EndReached)
            viewModel.onEvent(ArchiveEvent.EndReached)
            advanceUntilIdle()
            assertEquals(listOf(null, b1), archive.probes)

            archive.probeGate = null
            gate.complete(Unit)
            advanceUntilIdle()

            val b2 = history.dateOf(99)
            val second = viewModel.uiState.value as ArchiveState.Content
            assertEquals(100, second.items.size)
            assertEquals(ArchivePaging.CanLoadMore, second.paging)
            assertEquals(listOf(b1, b2), archive.windowBounds)

            viewModel.onEvent(ArchiveEvent.EndReached)
            advanceUntilIdle()

            val all = viewModel.uiState.value as ArchiveState.Content
            assertEquals(101, all.items.size)
            assertEquals(ArchivePaging.EndReached, all.paging)
            assertEquals(listOf(null, b1, b2), archive.probes)
            assertEquals(listOf(b1, b2, null), archive.windowBounds)
            // Каждая дата ровно один раз, порядок строго убывающий.
            assertEquals(history.dates(), all.items.map { it.localDate })
        }

    // --- I5-V5 ----------------------------------------------------------------------------

    @Test
    fun `I5-V5 a failed next page keeps the rows and retries from the same boundary`() = runTest(dispatcher) {
        history.fill(count = 80)
        val viewModel = createViewModel()
        collectStates(viewModel)
        advanceUntilIdle()
        val b1 = history.dateOf(49)

        archive.probeFailure = { IOException("база недоступна") }
        viewModel.onEvent(ArchiveEvent.EndReached)
        advanceUntilIdle()

        val failed = viewModel.uiState.value as ArchiveState.Content
        assertEquals(ArchivePaging.LoadMoreFailed, failed.paging)
        assertEquals("показанное не сбрасывается", 50, failed.items.size)
        assertEquals(listOf(b1), archive.windowBounds)

        archive.probeFailure = null
        viewModel.onEvent(ArchiveEvent.RetryClicked)
        advanceUntilIdle()

        assertEquals("повтор — с той же границы", listOf(null, b1, b1), archive.probes)
        val recovered = viewModel.uiState.value as ArchiveState.Content
        assertEquals(80, recovered.items.size)
        assertEquals(ArchivePaging.EndReached, recovered.paging)
    }

    // --- I5-V7 ----------------------------------------------------------------------------

    @Test
    fun `I5-V7 DayClicked sends exactly one OpenDay`() = runTest(dispatcher) {
        history.fill(count = 3)
        val viewModel = createViewModel()
        collectStates(viewModel)
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onEvent(ArchiveEvent.DayClicked(history.dateOf(1)))
            assertEquals(ArchiveEffect.OpenDay(history.dateOf(1)), awaitItem())
            expectNoEvents()

            viewModel.onEvent(ArchiveEvent.BackClicked)
            assertEquals(ArchiveEffect.NavigateBack, awaitItem())
            viewModel.onEvent(ArchiveEvent.ToTodayClicked)
            assertEquals(ArchiveEffect.NavigateHome, awaitItem())
            expectNoEvents()
        }
    }

    // --- I5-V8 ----------------------------------------------------------------------------

    @Test
    fun `I5-V8 a failed window refresh keeps the last good rows and retry resubscribes`() = runTest(dispatcher) {
        history.fill(count = 4)
        val viewModel = createViewModel()
        collectStates(viewModel)
        advanceUntilIdle()
        val shown = (viewModel.uiState.value as ArchiveState.Content).items

        archive.windowFailure = IOException("окно не перечиталось")
        history.add(daysAfterToday = 1) // запись будит окно — и оно падает
        advanceUntilIdle()

        val failed = viewModel.uiState.value as ArchiveState.Content
        assertEquals(ArchivePaging.RefreshFailed, failed.paging)
        assertEquals("последнее удачное окно не стирается", shown, failed.items)

        archive.windowFailure = null
        val subscriptionsBefore = archive.windowBounds.size
        viewModel.onEvent(ArchiveEvent.RetryClicked)
        advanceUntilIdle()

        assertEquals("новая подписка окна", subscriptionsBefore + 1, archive.windowBounds.size)
        val recovered = viewModel.uiState.value as ArchiveState.Content
        assertEquals(ArchivePaging.EndReached, recovered.paging)
        assertEquals(5, recovered.items.size)
    }

    @Test
    fun `I5-V8 a failed statistics refresh keeps the last good statistics and ON_START resubscribes`() =
        runTest(dispatcher) {
            history.fill(count = 4)
            val viewModel = createViewModel()
            collectStates(viewModel)
            advanceUntilIdle()
            val shown = viewModel.uiState.value as ArchiveState.Content

            progress.statisticsFailure = IOException("статистика не перечиталась")
            history.add(daysAfterToday = 1)
            advanceUntilIdle()

            val failed = viewModel.uiState.value as ArchiveState.Content
            assertEquals(ArchivePaging.RefreshFailed, failed.paging)
            assertEquals("последняя удачная статистика не стирается", shown.statistics, failed.statistics)

            progress.statisticsFailure = null
            viewModel.onScreenStarted()
            advanceUntilIdle()

            val recovered = viewModel.uiState.value as ArchiveState.Content
            assertEquals(ArchivePaging.EndReached, recovered.paging)
            assertEquals(5, recovered.statistics.playedDays)
        }

    // --- I5-V9 ----------------------------------------------------------------------------

    @Test
    fun `I5-V9 statistics failing before the first show is Error`() = runTest(dispatcher) {
        history.fill(count = 4)
        progress.statisticsFailure = IOException("статистика недоступна")
        val viewModel = createViewModel()
        collectStates(viewModel)
        advanceUntilIdle()

        assertEquals(ArchiveState.Error, viewModel.uiState.value)

        progress.statisticsFailure = null
        viewModel.onEvent(ArchiveEvent.RetryClicked)
        advanceUntilIdle()
        assertEquals(4, (viewModel.uiState.value as ArchiveState.Content).statistics.playedDays)
    }

    // --- I5-V10 ---------------------------------------------------------------------------

    @Test
    fun `I5-V10 cancellation of the first probe is not an Error`() = runTest(dispatcher) {
        history.fill(count = 4)
        archive.probeFailure = { CancellationException("скоуп отменён") }
        val viewModel = createViewModel()
        val states = collectStates(viewModel)
        advanceUntilIdle()

        assertEquals(ArchiveState.Loading, viewModel.uiState.value)
        assertTrue("отмена ошибкой не становится: $states", states.none { it == ArchiveState.Error })
    }

    @Test
    fun `I5-V10 cancelling the scope while a probe is suspended produces no Error`() = runTest(dispatcher) {
        history.fill(count = 4)
        val gate = CompletableDeferred<Unit>()
        archive.probeGate = gate
        val viewModel = createViewModel()
        val states = collectStates(viewModel)
        advanceUntilIdle()

        viewModel.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("отмена ошибкой не становится: $states", states.none { it == ArchiveState.Error })
        assertEquals(ArchiveState.Loading, viewModel.uiState.value)
    }

    // --- Прочее поведение §6.1 ------------------------------------------------------------

    /** Ошибочная эмиссия не стирает удачную: `reduce` читает только последнее удачное. */
    @Test
    fun `a new day on top appears without losing loaded rows`() = runTest(dispatcher) {
        history.fill(count = 51)
        val viewModel = createViewModel()
        collectStates(viewModel)
        advanceUntilIdle()

        history.add(daysAfterToday = 1)
        advanceUntilIdle()

        val content = viewModel.uiState.value as ArchiveState.Content
        assertEquals("новый день сверху, 50-я строка на месте", 51, content.items.size)
        assertEquals(TODAY.plusDays(1), content.items.first().localDate)
        assertEquals(history.dateOf(50), content.items.last().localDate)
    }

    /** Debug-сброс при открытом архиве: окно с границей пустеет → новая первая разведка → `Empty`. */
    @Test
    fun `an emptied database probes again, shows Empty and returns to Content on the next game`() =
        runTest(dispatcher) {
            history.fill(count = 60)
            val viewModel = createViewModel()
            collectStates(viewModel)
            advanceUntilIdle()
            assertEquals(ArchivePaging.CanLoadMore, (viewModel.uiState.value as ArchiveState.Content).paging)

            history.clear()
            advanceUntilIdle()

            assertEquals(ArchiveState.Empty, viewModel.uiState.value)
            assertEquals("первая разведка заново", listOf<LocalDate?>(null, null), archive.probes)

            history.add(daysAfterToday = 0)
            advanceUntilIdle()

            val content = viewModel.uiState.value as ArchiveState.Content
            assertEquals(listOf(TODAY), content.items.map { it.localDate })
            assertEquals(1, content.statistics.playedDays)
        }

    /** `ON_START` пересчитывает серию с новым `today` без новой записи в базу. */
    @Test
    fun `ON_START recomputes the streak for a new today`() = runTest(dispatcher) {
        history.fill(count = 3) // TODAY, TODAY-1, TODAY-2 — все завершены
        val viewModel = createViewModel()
        collectStates(viewModel)
        advanceUntilIdle()
        assertEquals(3, (viewModel.uiState.value as ArchiveState.Content).statistics.currentStreak)

        dateProvider.today = TODAY.plusDays(2) // сегодня и вчера не сыграны
        viewModel.onScreenStarted()
        advanceUntilIdle()

        val content = viewModel.uiState.value as ArchiveState.Content
        assertEquals(0, content.statistics.currentStreak)
        assertEquals(3, content.statistics.bestStreak)
    }

    // --- Инфраструктура -------------------------------------------------------------------

    private fun createViewModel() = ArchiveViewModel(
        archive = GetArchiveUseCase(archive),
        getStatistics = GetStatisticsUseCase(progress, dateProvider),
    )

    /** Подписка экрана: `WhileSubscribed` держит поток, пока кто-то собирает состояние. */
    private fun TestScope.collectStates(viewModel: ArchiveViewModel): List<ArchiveState> {
        val states = mutableListOf<ArchiveState>()
        backgroundScope.launch { viewModel.uiState.collect { states += it } }
        return states
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 1)
    }
}

// --- Фейки -----------------------------------------------------------------------------

/** История дней в памяти: общий источник окна, разведки и статистики. */
private class History {
    val days = MutableStateFlow<List<ArchiveDay>>(emptyList())

    /** [count] завершённых дней подряд, заканчивая сегодняшним; номер дня растёт к сегодняшнему. */
    fun fill(count: Int) {
        days.value = (0 until count).map { offset ->
            ArchiveDay(
                localDate = TODAY.minusDays(offset.toLong()),
                dayNumber = count - offset,
                totalScore = 15,
                completedCount = 3,
                isComplete = true,
            )
        }
    }

    fun add(daysAfterToday: Long) {
        val next = (days.value.maxOfOrNull { it.dayNumber } ?: 0) + 1
        days.value = days.value + ArchiveDay(TODAY.plusDays(daysAfterToday), next, 6, 1, false)
    }

    fun clear() {
        days.value = emptyList()
    }

    /** Дата строки [index] в порядке убывания. */
    fun dateOf(index: Int): LocalDate = dates()[index]

    fun dates(): List<LocalDate> = days.value.map { it.localDate }.sortedDescending()

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 1)
    }
}

private class FakeArchive(private val history: History) : ArchiveRepository {
    /** `before` каждой разведки по порядку. */
    val probes = mutableListOf<LocalDate?>()

    /** Нижняя граница каждой подписки окна по порядку; `null` — «все дни». */
    val windowBounds = mutableListOf<LocalDate?>()

    var probeFailure: (() -> Throwable)? = null
    var probeGate: CompletableDeferred<Unit>? = null

    /** Пока задано, окно бросает при следующей эмиссии и завершается. */
    var windowFailure: Throwable?
        get() = windowError.value
        set(value) {
            windowError.value = value
        }
    private val windowError = MutableStateFlow<Throwable?>(null)

    override suspend fun probe(before: LocalDate?, limit: Int): List<ArchiveDay> {
        probes += before
        probeGate?.await()
        probeFailure?.let { throw it() }
        return history.days.value
            .filter { before == null || it.localDate < before }
            .sortedByDescending { it.localDate }
            .take(limit)
    }

    override fun observeWindow(lowerBound: LocalDate?): Flow<List<ArchiveDay>> {
        windowBounds += lowerBound
        return combine(history.days, windowError) { all, error ->
            if (error != null) throw error
            all.filter { lowerBound == null || it.localDate >= lowerBound }.sortedByDescending { it.localDate }
        }
    }
}

private class FakeProgress(private val history: History) : ProgressRepository {
    private val statisticsError = MutableStateFlow<Throwable?>(null)

    /** Пока задано, поток `day_results` бросает при следующей эмиссии и завершается. */
    var statisticsFailure: Throwable?
        get() = statisticsError.value
        set(value) {
            statisticsError.value = value
        }

    override fun observeDayResults(): Flow<List<DayResult>> =
        combine(history.days, statisticsError) { days, error ->
            if (error != null) throw error
            days.map { DayResult(it.localDate, it.totalScore, it.completedCount, it.isComplete, completedAt = null) }
        }

    override suspend fun recordAttempt(attempt: PuzzleAttempt) = unsupported()
    override suspend fun getDayResult(localDate: LocalDate): DayResult? = unsupported()
    override suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult> = unsupported()
    override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int): PuzzleAttempt? = unsupported()
    override suspend fun getAttempts(localDate: LocalDate): List<PuzzleAttempt> = unsupported()
    override suspend fun getAllDayResults(): List<DayResult> = unsupported()
    override suspend fun getCompletedDates(): List<LocalDate> = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("архив читает только поток day_results")
}

private class FixedDateProvider(var today: LocalDate) : DateProvider {
    override fun today(): LocalDate = today
}
