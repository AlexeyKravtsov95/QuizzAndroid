package ru.poporyadku.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.poporyadku.core.time.ClockProvider
import ru.poporyadku.domain.model.TodayState
import ru.poporyadku.domain.usecase.GetTodayStateUseCase
import ru.poporyadku.domain.usecase.SessionStart
import ru.poporyadku.domain.usecase.StartDailySessionUseCase

/**
 * Фаза восстановления (ITERATION_3_DESIGN.md, I3-D47).
 *
 * Явная конечная машина состояний, а не один nullable-флаг: между завершением
 * `perform()` и приходом инициированного им пересчёта существует окно, в котором
 * кнопка обязана оставаться `disabled`, и одним флагом это окно не выражается.
 * Приватна для ViewModel — в [HomeState] уезжает только идентификатор (или `null`).
 */
private sealed interface RecoveryPhase {

    /** Ничего не выполняется; подтверждение принимается. */
    data object Idle : RecoveryPhase

    /** Идёт `perform()`. `startedAtGeneration` — поколение `Failure`, на котором дано согласие. */
    data class Running(val actionId: String, val startedAtGeneration: Long) : RecoveryPhase

    /**
     * `perform()` завершился — успехом ИЛИ отказом; ждём результат инициированного им
     * пересчёта. `startedAtGeneration` тот же, что был в [Running].
     */
    data class AwaitingRefresh(val actionId: String, val startedAtGeneration: Long) : RecoveryPhase
}

private val RecoveryPhase.runningActionId: String?
    get() = when (this) {
        RecoveryPhase.Idle -> null
        is RecoveryPhase.Running -> actionId
        is RecoveryPhase.AwaitingRefresh -> actionId
    }

/** Пронумерованный пересчёт: номер поколения — то, чего ждёт `AwaitingRefresh`. */
private data class Recompute(val generation: Long, val state: TodayState)

/**
 * ViewModel главного экрана (ITERATION_3_DESIGN.md, I3-D14, I3-D15, I3-D34, I3-D40, I3-D47).
 *
 * Отдельный провайдер даты сюда **не** инжектируется: `ClockProvider.now()` отдаёт согласованный
 * `TimeSnapshot` — и дату для сравнения, и момент для вычитания — из одного `Clock`.
 * Два провайдера дали бы два независимых чтения часов на один тик.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    getTodayState: GetTodayStateUseCase,
    private val startDailySession: StartDailySessionUseCase,
    private val clock: ClockProvider,
    private val recoveryActions: Set<@JvmSuppressWildcards HomeErrorRecoveryAction>,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** «Пересчитай сейчас»: `ON_START`, ретрай, смена даты по тикеру, восстановление. */
    private val refreshSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val recoveryPhase = MutableStateFlow<RecoveryPhase>(RecoveryPhase.Idle)

    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I3-D25). */
    val effects: Flow<HomeEffect> = effectChannel.receiveAsFlow()

    /**
     * Первый рубеж защиты от двойного нажатия CTA: пока предыдущий вызов
     * `StartDailySessionUseCase` не завершился, повторный игнорируется (I3-D17).
     */
    private val isStarting = AtomicBoolean(false)

    /**
     * Один upstream на всех подписчиков; каждая эмиссия получает номер поколения.
     *
     * `map`, а не `scan`: `scan` хранит аккумулятор внутри конкретного сбора, и после
     * перезапуска upstream по `WhileSubscribed` начал бы с нуля — новое состояние
     * получило бы то же поколение, что старое, и устаревшее подтверждение прошло бы
     * проверку (I3-D47).
     */
    private val recomputes: StateFlow<Recompute?> =
        getTodayState(refreshSignals)
            .map { state -> Recompute(generation = nextRecomputeGeneration(), state = state) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * `combine` доменного потока и фазы восстановления: смена `runningRecoveryId`
     * немедленно перерисовывает `HomeState.Error`, не дожидаясь новой эмиссии
     * `TodayState`. Второй подписки на `GetTodayStateUseCase` при этом не возникает —
     * `recomputes` уже разделяемый.
     */
    val uiState: StateFlow<HomeState> =
        combine(recomputes.filterNotNull(), recoveryPhase) { recompute, phase ->
            recompute.state.toHomeState(
                recoveryActions = descriptorsFor(recompute.state),
                runningRecoveryId = phase.runningActionId,
                recomputeGeneration = recompute.generation,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState.Loading)

    /** Собирается в `viewModelScope`; своего диспетчера не назначает (I3-D15). */
    private val minuteTicks: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    /**
     * Обратный отсчёт до начала следующей локальной даты.
     *
     * Отдельный поток, а не поле [HomeState]: минутное обновление не перестраивает весь
     * экран и **не выполняет ни одного запроса к базе**. `Duration` в состоянии не
     * хранится — сохранённое значение устарело бы к следующему кадру.
     *
     * Производный от ДВУХ источников: пересчитывается и на каждом минутном тике, и на
     * каждой новой эмиссии `recomputes`. Второе обязательно: первый тик приходится на
     * момент подписки, когда доменного состояния ещё нет, и без зависимости от
     * `recomputes` пришедший следом `Completed` до минуты показывал бы пустой отсчёт.
     *
     * Постоянного коллектора у тикера нет: `WhileSubscribed(5_000)` останавливает и его,
     * и — следом — `recomputes`, поэтому уход с экрана по-прежнему гасит upstream.
     */
    val countdown: StateFlow<Duration?> =
        combine(recomputes, minuteTicks) { recompute, _ -> recompute?.state }
            .map { state -> onTimeSample(state) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * `ON_START` (I3-D14). Не событие экрана: только отправляет сигнал пересчёта.
     * За ПЕРВУЮ эмиссию не отвечает — она гарантирована конструкцией потока (I3-D38).
     */
    fun onScreenStarted() {
        refreshSignals.tryEmit(Unit)
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.PrimaryAction -> onPrimaryAction()

            // Ретрай во время восстановления игнорируется: он запустил бы compute()
            // параллельно сбросу и вернул бы состояние, посчитанное на середине очистки.
            HomeEvent.RetryClicked ->
                if (recoveryPhase.value == RecoveryPhase.Idle) refreshSignals.tryEmit(Unit)

            is HomeEvent.RecoveryConfirmed -> performRecovery(event.actionId, event.generation)
        }
    }

    /**
     * Ровно один снимок часов на выборку: обратный отсчёт и решение «дата сменилась»
     * не могут относиться к разным моментам (I3-D15).
     *
     * Выборка делает две вещи и ни одной лишней — считает остаток до следующей
     * локальной даты и ловит её смену.
     */
    private suspend fun onTimeSample(state: TodayState?): Duration? {
        val now = clock.now()

        val stateDate = state?.todayOrNull
        if (stateDate != null && stateDate != now.localDate) {
            // Приложение, оставленное открытым через полночь, само выходит из
            // Completed — без ON_START и без действий пользователя.
            refreshSignals.emit(Unit)
        }

        return (state as? TodayState.Completed)?.let { completed ->
            val remaining = Duration.between(
                Instant.ofEpochMilli(now.epochMillis),
                completed.nextLocalDateStartsAt,
            )
            if (remaining.isNegative) Duration.ZERO else remaining
        }
    }

    /**
     * Основная кнопка. Таблица утверждённых переходов (I3-D17, разделы 6 и 15):
     * `FirstRun`/`Ready`/`InProgress` начинают или продолжают день; `Completed` и
     * `AwaitingNextDay` открывают итог конкретной даты; `ContentExhausted` — архив;
     * `AwaitingFirstDay` кнопки не имеет вовсе; на `Failure` основная кнопка — «Повторить».
     */
    private fun onPrimaryAction() {
        when (val state = recomputes.value?.state ?: return) {
            is TodayState.FirstRun,
            is TodayState.Ready,
            is TodayState.InProgress,
            -> startSession()

            is TodayState.Completed -> emitEffect(HomeEffect.NavigateToRecap(state.sessionDate))

            is TodayState.AwaitingNextDay -> state.lastCompleted?.let { summary ->
                emitEffect(HomeEffect.NavigateToRecap(summary.localDate))
            }

            is TodayState.ContentExhausted -> emitEffect(HomeEffect.NavigateToArchive)

            is TodayState.Failure -> Unit
        }
    }

    private fun startSession() {
        if (!isStarting.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (val start = startDailySession()) {
                    is SessionStart.Started ->
                        emitEffect(HomeEffect.NavigateToPuzzle(start.slotIndex, start.localDate))

                    is SessionStart.AlreadyCompleted ->
                        emitEffect(HomeEffect.NavigateToRecap(start.localDate))

                    // Остаёмся на Home и пересчитываем состояние. SetMissing — дефект
                    // контента: штатная ошибка появится через TodayState.Failure.
                    SessionStart.AwaitingNextDay,
                    SessionStart.ContentExhausted,
                    is SessionStart.SetMissing,
                    -> refreshSignals.emit(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Отказ «начать день» не роняет процесс: пересчёт покажет штатную
                // ошибку тем же путём, что и отказ compute() (I3-D43).
                refreshSignals.emit(Unit)
            } finally {
                isStarting.set(false)
            }
        }
    }

    /**
     * Шесть проверок — все до запуска корутины и все по `recomputes.value`, а НЕ по
     * `uiState.value` (I3-D47). Причина: `uiState` — производный `StateFlow` с
     * собственным конвейером; между новой эмиссией `recomputes` и обновлением
     * `uiState` существует окно, в котором `uiState.value` ещё показывает прежний
     * `Error`. Авторитетным источником служит домен, а не то, что успел опубликовать
     * экран.
     */
    private fun performRecovery(actionId: String, generation: Long) {
        val recompute = recomputes.value ?: return
        if (recompute.generation != generation) return                          // 1. подтверждение устарело
        val state = recompute.state
        if (state !is TodayState.Failure) return                                // 2. состояние уже не отказ
        val action = recoveryActions.firstOrNull { it.id == actionId } ?: return // 3. реализация есть
        if (!action.isApplicableTo(state.kind)) return                          // 4. применимо к причине
        if (recoveryPhase.value != RecoveryPhase.Idle) return                    // 5. другое действие идёт

        val running = RecoveryPhase.Running(actionId, startedAtGeneration = generation)
        if (!recoveryPhase.compareAndSet(RecoveryPhase.Idle, running)) return     // 6. гонка двух вызовов

        viewModelScope.launch {
            try {
                try {
                    action.perform()
                } finally {
                    // Переход в AwaitingRefresh выполняется и при успехе, и при отказе,
                    // ДО того как машина сможет разблокироваться: иначе между быстрым
                    // perform() и приходом нового TodayState экран на мгновение показал
                    // бы разблокированную кнопку на прежнем Error того же поколения.
                    recoveryPhase.value =
                        RecoveryPhase.AwaitingRefresh(actionId, startedAtGeneration = generation)
                }
                // Пересчёт обязателен и обязателен ДО разблокировки: полагаться на
                // эмиссию day_results нельзя — конфликт возникает до первой попытки,
                // и после полной очистки базы таблица может остаться пустой.
                refreshSignals.emit(Unit)
                awaitRecomputeAfter(generation)
            } catch (e: CancellationException) {
                throw e // отмена скоупа остаётся отменой (I3-D43)
            } catch (e: Exception) {
                // Отказ perform(): та же последовательность, что при успехе —
                // сигнал, ожидание нового поколения, и только потом Idle.
                refreshSignals.emit(Unit)
                awaitRecomputeAfter(generation)
            } finally {
                recoveryPhase.value = RecoveryPhase.Idle
            }
        }
    }

    /**
     * Ждём пересчёт, номер которого строго больше поколения, на котором дано согласие.
     * Ожидание идёт по УЖЕ разделяемому `recomputes`: второй подписки на
     * `GetTodayStateUseCase`, а значит и второго `compute()`, не возникает.
     */
    private suspend fun awaitRecomputeAfter(generation: Long) {
        recomputes.first { it != null && it.generation > generation }
    }

    /** Дескрипторы, применимые к ЭТОЙ причине отказа; для не-`Failure` — пустой список. */
    private fun descriptorsFor(state: TodayState): List<RecoveryActionUi> =
        if (state !is TodayState.Failure) {
            emptyList()
        } else {
            recoveryActions
                .filter { it.isApplicableTo(state.kind) }
                .sortedBy { it.id } // порядок Set не определён — фиксируем его явно
                .map { RecoveryActionUi(it.id, it.labelRes, it.confirmationRes) }
        }

    /**
     * Счётчик живёт в `SavedStateHandle`, а НЕ в операторе потока: он обязан пережить
     * и остановку upstream по `WhileSubscribed`, и смерть процесса вместе с открытым
     * диалогом подтверждения. Читаем — инкрементируем — записываем на каждой эмиссии.
     */
    private fun nextRecomputeGeneration(): Long {
        val next = (savedStateHandle.get<Long>(KEY_RECOMPUTE_GENERATION) ?: 0L) + 1
        savedStateHandle[KEY_RECOMPUTE_GENERATION] = next
        return next
    }

    private fun emitEffect(effect: HomeEffect) {
        effectChannel.trySend(effect)
    }

    private companion object {
        const val KEY_RECOMPUTE_GENERATION = "home.recomputeGeneration"
    }
}

/** Дата, к которой относится состояние; у отказа её может не быть вовсе. */
private val TodayState.todayOrNull: LocalDate?
    get() = when (this) {
        is TodayState.FirstRun -> today
        is TodayState.Ready -> today
        is TodayState.InProgress -> today
        is TodayState.Completed -> today
        is TodayState.AwaitingNextDay -> today
        is TodayState.ContentExhausted -> today
        is TodayState.Failure -> today
    }
