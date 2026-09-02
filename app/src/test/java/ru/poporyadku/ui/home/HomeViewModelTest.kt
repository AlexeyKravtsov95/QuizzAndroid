package ru.poporyadku.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.domain.assignment.Decision
import ru.poporyadku.domain.assignment.DecisionContext
import ru.poporyadku.core.model.DailySet
import ru.poporyadku.core.model.DayAssignment
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.core.model.UserPreferences
import ru.poporyadku.core.time.TimeSnapshot
import ru.poporyadku.domain.content.ContentInstallException
import ru.poporyadku.domain.content.ContentInstaller
import ru.poporyadku.domain.model.CompletedDaySummary
import ru.poporyadku.domain.model.TodayFailureKind
import ru.poporyadku.domain.model.TodayState
import ru.poporyadku.domain.model.TodayStats
import ru.poporyadku.domain.repository.DailySetRepository
import ru.poporyadku.domain.repository.DayAssignmentRepository
import ru.poporyadku.domain.repository.ProgressRepository
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.scoring.Streaks
import ru.poporyadku.domain.usecase.GetStreaksUseCase
import ru.poporyadku.domain.usecase.GetTodayStateUseCase
import ru.poporyadku.domain.usecase.StartDailySessionUseCase

/**
 * `HomeViewModel` — ITERATION_3_DESIGN.md, I3-V13, I3-V14, I3-V15, I3-V18–V21,
 * I3-V26, I3-V32, I3-V35.
 *
 * Тесты идут на фейках доменных зависимостей, а не на in-memory Room: предмет проверки —
 * топология потока, нумерация поколений и машина восстановления, а не SQL.
 *
 * Чистый JVM, без Robolectric: ни `HomeViewModel`, ни маппер не трогают Android-типы.
 */
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val today = LocalDate.of(2026, 8, 29)
    private val zone: ZoneId = ZoneOffset.UTC

    private lateinit var clock: MutableClockProvider
    private lateinit var assignments: FakeAssignmentRepository
    private lateinit var progress: FakeProgressRepository
    private lateinit var content: FakeContentInstaller
    private lateinit var sets: FakeDailySetRepository

    /** Владеет созданными ViewModel: clear() отменяет их viewModelScope вместе с тикером. */
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = MutableClockProvider(today, zone)
        assignments = FakeAssignmentRepository(clock)
        progress = FakeProgressRepository()
        content = FakeContentInstaller()
        sets = FakeDailySetRepository()
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    // --- I3-V20: начальная эмиссия --------------------------------------------------

    /**
     * `I3-V20`. Новый `HomeViewModel`, `onScreenStarted()` НЕ вызывался: первое значение
     * после `Loading` — уже вычисленное состояние. Начальная эмиссия гарантирована
     * конструкцией потока (I3-D38), а не внешним событием.
     */
    @Test
    fun `I3-V20 initial emission arrives after Loading without onScreenStarted`() = homeTest {
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            assertEquals(HomeState.Loading, awaitItem())
            val first = awaitItem()
            assertTrue("ожидали вычисленное состояние, получили $first", first is HomeState.FirstRun)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I3-V13: Ready -> InProgress -> Completed ------------------------------------

    /**
     * `I3-V13`. Последовательные изменения базы и сигналы обновления переводят Home
     * `Ready → InProgress → Completed` в одном и том же коллекторе.
     */
    @Test
    fun `I3-V13 Ready to InProgress to Completed through refresh signals`() = homeTest {
        progress.dayResults.value = listOf(completedDay(today.minusDays(1), score = 12))
        assignments.decision = Decision.NewSet(PACK, setIndex = 3)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            assertEquals(HomeState.Loading, awaitItem())
            assertTrue(awaitItem() is HomeState.Ready)

            // День начат: назначение на сегодня, попыток пока нет.
            assignments.decision = Decision.Assigned(PACK, setIndex = 3)
            progress.dayResults.value = progress.dayResults.value + partialDay(today, completed = 1)
            viewModel.onScreenStarted()
            val inProgress = awaitItem()
            assertTrue("ожидали InProgress, получили $inProgress", inProgress is HomeState.InProgress)
            assertEquals(1, (inProgress as HomeState.InProgress).completedCount)

            // Все три слота закрыты.
            progress.dayResults.value = listOf(
                completedDay(today.minusDays(1), score = 12),
                completedDay(today, score = 15),
            )
            viewModel.onScreenStarted()
            val completed = awaitItem()
            assertTrue("ожидали Completed, получили $completed", completed is HomeState.Completed)
            assertEquals(15, (completed as HomeState.Completed).totalScore)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I3-V14: двойное нажатие CTA -------------------------------------------------

    /** `I3-V14`. Два быстрых `PrimaryAction` вызывают `StartDailySessionUseCase` один раз. */
    @Test
    fun `I3-V14 double primary action starts session once`() = homeTest {
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItems(2)

            viewModel.onEvent(HomeEvent.PrimaryAction)
            viewModel.onEvent(HomeEvent.PrimaryAction)
            runCurrent()

            assertEquals(1, assignments.startSessionCalls)
            cancelAndIgnoreRemainingEvents()
        }

        val effect = viewModel.effects.first()
        assertEquals(HomeEffect.NavigateToPuzzle(slotIndex = 0, date = today), effect)
    }

    /**
     * Утверждённая таблица переходов CTA готовых состояний: `Completed` открывает итог
     * сессионной даты, `AwaitingNextDay` — итог последнего завершённого дня,
     * `ContentExhausted` ведёт в архив, а `AwaitingFirstDay` CTA не имеет вовсе.
     */
    @Test
    fun `primary action of settled states navigates by the approved table`() = homeTest {
        progress.dayResults.value = listOf(completedDay(today, score = 15))
        assignments.decision = Decision.Assigned(PACK, setIndex = 4)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItems(2)
            viewModel.onEvent(HomeEvent.PrimaryAction)
            runCurrent()
            assertEquals(HomeEffect.NavigateToRecap(today), viewModel.effects.first())
            assertEquals("CTA готового состояния не начинает день", 0, assignments.startSessionCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `content exhausted primary action opens archive`() = homeTest {
        progress.dayResults.value = listOf(completedDay(today.minusDays(1), score = 12))
        assignments.decision = Decision.ContentExhausted
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItems(2)
            viewModel.onEvent(HomeEvent.PrimaryAction)
            runCurrent()
            assertEquals(HomeEffect.NavigateToArchive, viewModel.effects.first())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** `SessionStart.AwaitingNextDay` оставляет на Home и пересчитывает состояние. */
    @Test
    fun `awaiting next day session start recomputes home instead of navigating`() = homeTest {
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItems(2)
            // К моменту пересчёта политика уже отвечает «сегодня не положено».
            assignments.decision = Decision.AwaitingNextDay
            viewModel.onEvent(HomeEvent.PrimaryAction)
            runCurrent()
            assertTrue(awaitItem() is HomeState.AwaitingFirstDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I3-V15: смена даты + ON_START -----------------------------------------------

    /** `I3-V15`. Смена даты в `FakeClockProvider` и `onScreenStarted()` пересчитывают Home. */
    @Test
    fun `I3-V15 date change plus onScreenStarted recomputes state`() = homeTest {
        progress.dayResults.value = listOf(completedDay(today, score = 18))
        assignments.decision = Decision.Assigned(PACK, setIndex = 2)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            skipItems(2)

            val tomorrow = today.plusDays(1)
            clock.moveTo(tomorrow)
            assignments.decision = Decision.NewSet(PACK, setIndex = 3)
            viewModel.onScreenStarted()

            val next = awaitItem()
            assertTrue("ожидали Ready, получили $next", next is HomeState.Ready)
            assertEquals(tomorrow, (next as HomeState.Ready).today)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I3-V21: полночь без ON_START ------------------------------------------------

    /**
     * `I3-V21`. `FakeClockProvider` переводится на следующие сутки; `advanceTimeBy(60_000)`
     * даёт минутный тик, который выводит Home из `Completed` **без** `onScreenStarted()`
     * и обновляет обратный отсчёт.
     */
    @Test
    fun `I3-V21 minute ticker leaves Completed after midnight and updates countdown`() = homeTest {
        progress.dayResults.value = listOf(completedDay(today, score = 18))
        assignments.decision = Decision.Assigned(PACK, setIndex = 2)
        val viewModel = createViewModel()

        val countdownCollector = launch { viewModel.countdown.collect { } }
        viewModel.uiState.test {
            skipItems(2)
            runCurrent()

            // Отсчёт посчитан СРАЗУ на первом же Completed, без ожидания минуты:
            // countdown зависит и от тикера, и от recomputes.
            val before = viewModel.countdown.value
            assertNotNull("countdown должен быть посчитан на Completed", before)

            clock.moveTo(today, LocalTime.of(23, 0))
            advanceTimeBy(60_001)
            runCurrent()
            val closer = viewModel.countdown.value
            assertNotNull(closer)
            assertTrue(
                "обратный отсчёт должен уменьшиться: было $before, стало $closer",
                closer!! < before!!,
            )

            val tomorrow = today.plusDays(1)
            clock.moveTo(tomorrow)
            assignments.decision = Decision.NewSet(PACK, setIndex = 3)
            advanceTimeBy(60_001)
            runCurrent()

            val next = expectMostRecentItem()
            assertTrue(
                "полночь обязана вывести Home из Completed без ON_START, получили $next",
                next is HomeState.Ready,
            )
            cancelAndIgnoreRemainingEvents()
        }
        countdownCollector.cancel()
    }

    /**
     * `I3-V21` (продолжение): **первый же** `Completed` даёт ненулевой обратный отсчёт
     * без единого `advanceTimeBy()`. Первый тик приходится на момент подписки, когда
     * доменного состояния ещё нет, поэтому countdown обязан пересчитываться и на
     * эмиссии `recomputes`, а не только по тикеру.
     */
    @Test
    fun `I3-V21 first Completed yields a countdown without advancing time`() = homeTest {
        progress.dayResults.value = listOf(completedDay(today, score = 18))
        assignments.decision = Decision.Assigned(PACK, setIndex = 2)
        val viewModel = createViewModel()

        viewModel.countdown.test {
            // Первое значение — начальное null: состояния ещё нет.
            assertNull(awaitItem())
            val first = awaitItem()
            assertNotNull("первый Completed обязан дать отсчёт немедленно", first)
            assertTrue("отсчёт до полуночи не может быть нулевым", first!! > Duration.ZERO)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Вне `Completed` обратного отсчёта нет вовсе. */
    @Test
    fun `countdown is null outside Completed`() = homeTest {
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val viewModel = createViewModel()

        val collector = launch { viewModel.countdown.collect { } }
        runCurrent()
        assertNull(viewModel.countdown.value)
        collector.cancel()
    }

    /** Один `ClockProvider.now()` на тик: countdown и решение о дате — из одного снимка. */
    @Test
    fun `minute tick reads the clock exactly once`() = homeTest {
        progress.dayResults.value = listOf(completedDay(today, score = 18))
        assignments.decision = Decision.Assigned(PACK, setIndex = 2)
        val viewModel = createViewModel()

        val countdownCollector = launch { viewModel.countdown.collect { } }
        viewModel.uiState.test {
            skipItems(2)
            runCurrent()
            val afterFirstTick = clock.reads
            advanceTimeBy(60_001)
            runCurrent()
            assertEquals("ровно одно чтение часов на тик", afterFirstTick + 1, clock.reads)
            cancelAndIgnoreRemainingEvents()
        }
        countdownCollector.cancel()
    }

    // --- I3-V18 / I3-V19: маппер -----------------------------------------------------

    /** `I3-V18`. Маппер исчерпывающе покрывает все доменные варианты. */
    @Test
    fun `I3-V18 mapper covers every domain state`() {
        val stats = stats(completedDayCount = 4)

        assertTrue(
            TodayState.FirstRun(today, dayNumber = 1).map() is HomeState.FirstRun,
        )
        assertTrue(
            TodayState.Ready(today, dayNumber = 2, stats = stats).map() is HomeState.Ready,
        )
        assertTrue(
            TodayState.InProgress(today, today, dayNumber = 2, completedCount = 1)
                .map() is HomeState.InProgress,
        )
        assertTrue(
            TodayState.Completed(
                today = today,
                sessionDate = today,
                dayNumber = 2,
                totalScore = 15,
                streaks = Streaks(3, 5),
                nextLocalDateStartsAt = Instant.EPOCH,
            ).map() is HomeState.Completed,
        )
        assertTrue(
            TodayState.AwaitingNextDay(
                today = today,
                lastCompleted = CompletedDaySummary(today.minusDays(1), 1, 15),
                stats = stats,
            ).map() is HomeState.AwaitingNextDay,
        )
        assertTrue(
            TodayState.ContentExhausted(today, stats).map() is HomeState.ContentExhausted,
        )
        assertTrue(
            TodayState.Failure(today, stats, TodayFailureKind.Generic).map() is HomeState.Error,
        )
    }

    /** `I3-V19`. `AwaitingNextDay(lastCompleted = null)` даёт `AwaitingFirstDay`. */
    @Test
    fun `I3-V19 awaiting next day without history maps to AwaitingFirstDay`() {
        val mapped = TodayState.AwaitingNextDay(
            today = today,
            lastCompleted = null,
            stats = stats(completedDayCount = 0, playedDayCount = 0),
        ).map()

        assertTrue("ожидали AwaitingFirstDay, получили $mapped", mapped is HomeState.AwaitingFirstDay)
        assertFalse((mapped as HomeState.AwaitingFirstDay).isArchiveVisible)
    }

    /** Поколение переносится только в `Error`: остальным композициям оно не нужно. */
    @Test
    fun `mapper carries recomputeGeneration only into Error`() {
        val error = TodayState.Failure(today, null, TodayFailureKind.ContentConflict)
            .toHomeState(
                recoveryActions = listOf(RECOVERY_DESCRIPTOR),
                runningRecoveryId = null,
                recomputeGeneration = 42L,
            ) as HomeState.Error

        assertEquals(42L, error.recomputeGeneration)
        assertEquals(listOf(RECOVERY_DESCRIPTOR), error.recoveryActions)
        assertFalse("прогресс не прочитан — «Архив» скрыт", error.isArchiveVisible)
    }

    /** `I3-V19` (продолжение): при непрочитанном прогрессе Home не выдумывает статистику. */
    @Test
    fun `awaiting first day keeps stats and hides archive when nothing is completed`() {
        val mapped = TodayState.AwaitingNextDay(
            today = today,
            lastCompleted = null,
            stats = stats(completedDayCount = 0, playedDayCount = 2),
        ).map() as HomeState.AwaitingFirstDay

        assertEquals(2, mapped.stats.playedDayCount)
        assertFalse(mapped.isArchiveVisible)
    }

    // --- I3-V26: живой поток после отказа --------------------------------------------

    /**
     * `I3-V26`. Первая попытка `compute()` бросает → `Error`; `RetryClicked` запускает
     * второй `compute()`, который отдаёт успешное состояние. Оба значения приходят в
     * ОДИН коллектор — подписка не пересоздаётся (I3-D43).
     */
    @Test
    fun `I3-V26 retry recomputes in the same collector after failure`() = homeTest {
        content.failure = IllegalStateException("база недоступна")
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val viewModel = createViewModel()

        viewModel.uiState.test {
            assertEquals(HomeState.Loading, awaitItem())
            val failed = awaitItem()
            assertTrue("ожидали Error, получили $failed", failed is HomeState.Error)
            assertEquals(TodayFailureKind.Generic, (failed as HomeState.Error).kind)

            content.failure = null
            viewModel.onEvent(HomeEvent.RetryClicked)

            val recovered = awaitItem()
            assertTrue("ожидали FirstRun, получили $recovered", recovered is HomeState.FirstRun)
            // Оба значения пришли в ОДИН коллектор Turbine: подписка не пересоздавалась,
            // а compute() выполнился ровно дважды — по разу на сигнал.
            assertEquals(2, content.computes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I3-V32: recovery-машина целиком ---------------------------------------------

    /**
     * `I3-V32`. Полный цикл восстановления от `ContentConflict` и защита от устаревших
     * подтверждений — двенадцать проверок из тестовой матрицы.
     */
    @Test
    fun `I3-V32 full recovery machine and stale confirmation protection`() = homeTest {
        content.failure = conflict()
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val action = FakeRecoveryAction()
        val viewModel = createViewModel(recoveryActions = setOf(action))

        viewModel.uiState.test {
            assertEquals(HomeState.Loading, awaitItem())

            // (1) ContentConflict даёт Error поколения N и ровно одно действие.
            val first = awaitItem() as HomeState.Error
            assertEquals(TodayFailureKind.ContentConflict, first.kind)
            assertEquals(1, first.recoveryActions.size)
            assertEquals(FakeRecoveryAction.ID, first.recoveryActions.single().id)
            assertNull(first.runningRecoveryId)
            val generationN = first.recomputeGeneration

            // (11) day_results пуст на всём протяжении: триггером пересчёта служит
            // только refreshSignals, а не эмиссия Room-Flow.
            assertTrue(progress.dayResults.value.isEmpty())

            // Успешный perform() восстанавливает контент.
            action.onPerform = { content.failure = null }

            val computesBeforeRecovery = content.computes

            // Все три события отправляются СИНХРОННО, до того как планировщик успеет
            // выполнить корутину восстановления: фаза становится Running внутри
            // performRecovery, ещё до launch, поэтому (2) второе подтверждение и
            // (4) Retry отсекаются проверкой «фаза не Idle».
            viewModel.onEvent(HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, generationN))
            viewModel.onEvent(HomeEvent.RetryClicked)
            viewModel.onEvent(HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, generationN))

            // (3) runningRecoveryId опубликован немедленно, до новой доменной эмиссии:
            // Turbine сохраняет промежуточный кадр даже если восстановление уже
            // завершилось к моменту чтения.
            val running = awaitItem() as HomeState.Error
            assertEquals(FakeRecoveryAction.ID, running.runningRecoveryId)
            assertEquals(generationN, running.recomputeGeneration)

            runCurrent()

            // (5) После refresh приходит новое состояние с поколением больше N.
            val afterReset = expectMostRecentItem()
            assertTrue("ожидали FirstRun, получили $afterReset", afterReset is HomeState.FirstRun)
            assertEquals("perform() вызван ровно один раз", 1, action.calls)
            assertEquals(
                "единственный пересчёт — тот, что инициировало восстановление; " +
                    "RetryClicked во время восстановления compute() не запускает",
                computesBeforeRecovery + 1,
                content.computes,
            )

            // (12) runningRecoveryId снова null — но FirstRun поля вовсе не имеет,
            // поэтому проверяем следующую ошибку ниже.

            // (6) Старое подтверждение после успешного refresh игнорируется.
            viewModel.onEvent(HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, generationN))
            runCurrent()
            assertEquals(1, action.calls)

            // (7) perform() бросает Exception: фаза всё равно проходит AwaitingRefresh,
            // выполняются refresh и ожидание нового поколения.
            content.failure = conflict()
            viewModel.onEvent(HomeEvent.RetryClicked)
            runCurrent()
            val conflictAgain = expectMostRecentItem() as HomeState.Error
            val generationNPlus1 = conflictAgain.recomputeGeneration
            assertTrue(generationNPlus1 > generationN)
            assertNull("после завершения обеих веток фаза Idle", conflictAgain.runningRecoveryId)

            // (8) Старое подтверждение N после нового ContentConflict поколения N+1
            // не вызывает действие.
            viewModel.onEvent(HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, generationN))
            runCurrent()
            assertEquals(1, action.calls)

            // (9) Новое подтверждение N+1 разрешает ровно один новый вызов, и он падает.
            action.onPerform = { throw IllegalStateException("сброс не удался") }
            viewModel.onEvent(HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, generationNPlus1))
            runCurrent()
            assertEquals(2, action.calls)

            val afterFailedPerform = expectMostRecentItem() as HomeState.Error
            assertTrue(
                "после отказа perform() обязан прийти новый пересчёт",
                afterFailedPerform.recomputeGeneration > generationNPlus1,
            )
            // (12) runningRecoveryId снова null после каждой из веток.
            assertNull(afterFailedPerform.runningRecoveryId)

            // (11) Ни одной строки в day_results так и не появилось.
            assertTrue(progress.dayResults.value.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * `I3-V32`, пункт 10. Событие, попавшее в окно между новой эмиссией `recomputes` и
     * обновлением `uiState`, отклоняется: проверка идёт по `recomputes.value`.
     *
     * Окно воспроизводится без коллектора `uiState`: `recomputes` уже отдал новое
     * поколение, а `uiState.value` ещё показывает прежний `Error`.
     */
    @Test
    fun `I3-V32 stale confirmation is rejected by recomputes not uiState`() = homeTest {
        content.failure = conflict()
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val action = FakeRecoveryAction()
        val viewModel = createViewModel(recoveryActions = setOf(action))

        val generationN = viewModel.uiState.first { it is HomeState.Error }
            .let { (it as HomeState.Error).recomputeGeneration }

        // Пересчёт без коллектора uiState: recomputes уходит вперёд, uiState.value
        // остаётся прежним.
        val collector = launch { viewModel.uiState.collect { } }
        runCurrent()
        viewModel.onEvent(HomeEvent.RetryClicked)
        runCurrent()
        collector.cancel()

        viewModel.onEvent(HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, generationN))
        runCurrent()

        assertEquals("подтверждение прежнего поколения обязано быть отклонено", 0, action.calls)
    }

    /** Отмена скоупа остаётся отменой: `CancellationException` не становится ошибкой. */
    @Test
    fun `recovery rethrows cancellation instead of swallowing it`() = homeTest {
        content.failure = conflict()
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val action = FakeRecoveryAction()
        action.onPerform = { throw CancellationException("скоуп отменён") }
        val viewModel = createViewModel(recoveryActions = setOf(action))

        viewModel.uiState.test {
            skipItems(1)
            val error = awaitItem() as HomeState.Error
            viewModel.onEvent(
                HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, error.recomputeGeneration),
            )
            runCurrent()

            // Действие вызвано; отмена не превратилась в обычную ошибку и не оставила
            // фазу в Running — состояние прежней ошибки не разблокировано «наполовину».
            assertEquals(1, action.calls)
            assertEquals(
                "отмена не должна инициировать пересчёт",
                1,
                content.computes,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Действие, неприменимое к причине отказа, не показывается и не выполняется. */
    @Test
    fun `generic failure filters the recovery descriptor out`() = homeTest {
        content.failure = IllegalStateException("обычная ошибка")
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val action = FakeRecoveryAction()
        val viewModel = createViewModel(recoveryActions = setOf(action))

        viewModel.uiState.test {
            skipItems(1)
            val error = awaitItem() as HomeState.Error
            assertEquals(TodayFailureKind.Generic, error.kind)
            assertTrue(error.recoveryActions.isEmpty())

            viewModel.onEvent(
                HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, error.recomputeGeneration),
            )
            runCurrent()
            assertEquals(0, action.calls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- I3-V35: устойчивость поколения ----------------------------------------------

    /**
     * `I3-V35`. Поколение переживает остановку upstream по `WhileSubscribed` и
     * восстановление `SavedStateHandle`.
     *
     * **Почему `advanceTimeBy(10_001)`, а не `5_001`:** `stateIn` здесь два, вложенных,
     * и таймауты складываются — через 5 с `uiState` перестаёт собирать `recomputes`,
     * и только ещё через 5 с сам `recomputes` перестаёт собирать
     * `GetTodayStateUseCase`. При `advanceTimeBy(5_001)` исходный upstream остался бы
     * живым, и тест проверял бы не перезапуск, а всего лишь отписку верхнего слоя.
     */
    @Test
    fun `I3-V35 generation survives upstream stop and SavedStateHandle restore`() = homeTest {
        content.failure = conflict()
        assignments.decision = Decision.NewSet(PACK, setIndex = 0)
        val action = FakeRecoveryAction()
        val handle = SavedStateHandle()
        val viewModel = createViewModel(recoveryActions = setOf(action), savedStateHandle = handle)

        // 1. Получаем Error поколения N. Экран собирает ОБА потока — uiState и
        // countdown, — и оба держат recomputes живым, поэтому снимать нужно оба.
        val firstState = launch { viewModel.uiState.collect { } }
        val firstCountdown = launch { viewModel.countdown.collect { } }
        runCurrent()
        val generationN = (viewModel.uiState.value as HomeState.Error).recomputeGeneration

        // 2–4. Убираем всех коллекторов и ждём остановки ОБОИХ вложенных stateIn.
        firstState.cancel()
        firstCountdown.cancel()
        advanceTimeBy(10_001)
        runCurrent()

        // 5–6. Новая подписка даёт пересчёт с поколением строго больше N.
        val secondCollector = launch { viewModel.uiState.collect { } }
        runCurrent()
        val restarted = viewModel.uiState.value as HomeState.Error
        assertTrue(
            "после перезапуска upstream поколение обязано вырасти: было $generationN, стало ${restarted.recomputeGeneration}",
            restarted.recomputeGeneration > generationN,
        )

        // 7. Сохранённое подтверждение старого поколения не вызывает perform().
        viewModel.onEvent(HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, generationN))
        runCurrent()
        assertEquals(0, action.calls)
        secondCollector.cancel()
        runCurrent()

        // 8–9. Новая ViewModel с SavedStateHandle, уже содержащим N: первая эмиссия
        // получает поколение больше N, а не единицу.
        val restoredAction = FakeRecoveryAction()
        val restoredViewModel = createViewModel(
            recoveryActions = setOf(restoredAction),
            savedStateHandle = handle,
        )
        val restoredCollector = launch { restoredViewModel.uiState.collect { } }
        runCurrent()
        val restoredState = restoredViewModel.uiState.value as HomeState.Error
        assertTrue(
            "восстановленная ViewModel не должна начинать нумерацию заново",
            restoredState.recomputeGeneration > generationN,
        )

        // 10. То же старое подтверждение снова отклоняется.
        restoredViewModel.onEvent(HomeEvent.RecoveryConfirmed(FakeRecoveryAction.ID, generationN))
        runCurrent()
        assertEquals(0, restoredAction.calls)
        restoredCollector.cancel()
    }

    // --- Инфраструктура --------------------------------------------------------------

    /**
     * Минутный тикер живёт в `viewModelScope` и перепланирует себя бесконечно, поэтому
     * `advanceUntilIdle()` из него никогда не вернулся бы — ни в теле теста, ни в
     * завершении `runTest`. Тело теста работает `runCurrent()`/`advanceTimeBy()`
     * (обе операции ограничены), а скоуп отменяется ДО завершения `runTest`.
     */
    private fun homeTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            store.clear()
            runCurrent()
        }
    }

    /**
     * `StartDailySessionUseCase` — final-класс, поэтому подменяется не он, а его
     * репозитории: use case строится настоящий, а счётчик вызовов живёт в фейке
     * назначений.
     */
    private fun createViewModel(
        recoveryActions: Set<HomeErrorRecoveryAction> = emptySet(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): HomeViewModel = HomeViewModel(
        getTodayState = GetTodayStateUseCase(
            content = content,
            assignments = assignments,
            progress = progress,
            streaks = GetStreaksUseCase(progress, NoOpUserPreferences()),
        ),
        startDailySession = StartDailySessionUseCase(
            content = content,
            assignments = assignments,
            sets = sets,
            progress = progress,
        ),
        clock = clock,
        recoveryActions = recoveryActions,
        savedStateHandle = savedStateHandle,
    ).also { store.put("home-${store.keys().size}", it) }

    private fun TodayState.map(): HomeState =
        toHomeState(recoveryActions = emptyList(), runningRecoveryId = null, recomputeGeneration = 1L)

    private fun stats(completedDayCount: Int, playedDayCount: Int = completedDayCount) = TodayStats(
        streaks = Streaks(current = 2, best = 5),
        bestDayScore = 17,
        playedDayCount = playedDayCount,
        completedDayCount = completedDayCount,
    )

    private fun completedDay(date: LocalDate, score: Int) = DayResult(
        localDate = date,
        totalScore = score,
        completedCount = 3,
        isComplete = true,
        completedAt = 0L,
    )

    private fun partialDay(date: LocalDate, completed: Int) = DayResult(
        localDate = date,
        totalScore = 0,
        completedCount = completed,
        isComplete = false,
        completedAt = null,
    )

    private fun conflict() = ContentInstallException.Conflict(
        packId = PACK,
        staleSetIndexes = listOf(7),
        blockedDates = listOf(today.minusDays(2)),
    )

    private companion object {
        const val PACK = "core-ru"

        val RECOVERY_DESCRIPTOR = RecoveryActionUi(
            id = "temporary_content_reset",
            labelRes = 1,
            confirmationRes = 2,
        )
    }
}

// --- Фейки -------------------------------------------------------------------------

/** Часы с подвижным моментом и счётчиком обращений (проверка «один `now()` на тик»). */
private class MutableClockProvider(date: LocalDate, private val zone: ZoneId) : ClockProvider {
    private var instant: Instant = date.atTime(LocalTime.NOON).atZone(zone).toInstant()
    var reads: Int = 0
        private set

    override fun clock(): Clock = Clock.fixed(instant, zone)

    override fun now(): TimeSnapshot {
        reads++
        return TimeSnapshot.of(instant, zone)
    }

    fun moveTo(date: LocalDate, time: LocalTime = LocalTime.NOON) {
        instant = date.atTime(time).atZone(zone).toInstant()
    }
}

private class FakeContentInstaller : ContentInstaller {
    var failure: Exception? = null
    var computes: Int = 0
        private set

    override suspend fun ensureInstalled() {
        computes++
        failure?.let { throw it }
    }
}

private class FakeAssignmentRepository(private val clock: MutableClockProvider) : DayAssignmentRepository {
    var decision: Decision = Decision.AwaitingNextDay
    var assignment: DayAssignment? = null

    /** Второй рубеж защиты от двойного нажатия проверяется именно здесь. */
    var startSessionCalls: Int = 0
        private set

    override suspend fun peek(): DecisionContext = DecisionContext(decision, clock.now())

    override suspend fun startSession(): DecisionContext {
        startSessionCalls++
        return DecisionContext(decision, clock.now())
    }

    override suspend fun getAssignment(localDate: LocalDate): DayAssignment? = assignment
}

private class FakeDailySetRepository : DailySetRepository {
    var set: DailySet? = DailySet(
        packId = "core-ru",
        setIndex = 0,
        puzzleId1 = "p1",
        puzzleId2 = "p2",
        puzzleId3 = "p3",
    )

    override suspend fun getSet(packId: String, setIndex: Int): DailySet? = set
}

/** Кэш серии в этих тестах не проверяется — запись просто поглощается. */
private class NoOpUserPreferences : UserPreferencesRepository {
    override val preferences: Flow<UserPreferences> = emptyFlow()

    override suspend fun setSoundEnabled(enabled: Boolean) = Unit
    override suspend fun setVibrationEnabled(enabled: Boolean) = Unit
    override suspend fun setReminderEnabled(enabled: Boolean) = Unit
    override suspend fun setReminderTime(time: LocalTime) = Unit
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setStoredContentVersion(version: Int) = Unit
    override suspend fun setHasSeenDragHint(seen: Boolean) = Unit
    override suspend fun setHasSeenScoringHint(seen: Boolean) = Unit
    override suspend fun setHasCompletedFirstDay(completed: Boolean) = Unit
    override suspend fun setNotificationPromptShown(shown: Boolean) = Unit
    override suspend fun setLastSeenDate(date: LocalDate?) = Unit
    override suspend fun updateStreakCache(current: Int, best: Int, date: LocalDate) = Unit
}

private class FakeProgressRepository : ProgressRepository {
    val dayResults = MutableStateFlow<List<DayResult>>(emptyList())

    override suspend fun recordAttempt(attempt: ru.poporyadku.core.model.PuzzleAttempt) = Unit

    override suspend fun getDayResult(localDate: LocalDate): DayResult? =
        dayResults.value.firstOrNull { it.localDate == localDate }

    override suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult> =
        dayResults.value.filter { it.localDate in from..to }

    override suspend fun getAttempt(localDate: LocalDate, slotIndex: Int) = null

    override suspend fun getAttempts(localDate: LocalDate) = emptyList<ru.poporyadku.core.model.PuzzleAttempt>()

    override suspend fun getAllDayResults(): List<DayResult> = dayResults.value

    override suspend fun getCompletedDates(): List<LocalDate> =
        dayResults.value.filter { it.isComplete }.map { it.localDate }

    override fun observeDayResults(): Flow<List<DayResult>> = dayResults
}

/**
 * Действие восстановления: `perform()` завершается немедленно, без задержек и без
 * собственного диспетчера, считает вызовы и умеет по требованию бросить исключение.
 */
private class FakeRecoveryAction : HomeErrorRecoveryAction {
    var calls: Int = 0
        private set

    var onPerform: () -> Unit = {}

    override val id: String = ID
    override fun isApplicableTo(kind: TodayFailureKind): Boolean =
        kind == TodayFailureKind.ContentConflict

    override val labelRes: Int = 1
    override val confirmationRes: Int = 2

    override suspend fun perform() {
        calls++
        onPerform()
    }

    companion object {
        const val ID = "temporary_content_reset"
    }
}
