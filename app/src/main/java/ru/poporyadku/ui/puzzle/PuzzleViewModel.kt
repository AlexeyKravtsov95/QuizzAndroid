package ru.poporyadku.ui.puzzle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.domain.usecase.AttemptKind
import ru.poporyadku.domain.usecase.GetPuzzleResult
import ru.poporyadku.domain.usecase.GetPuzzleUseCase
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.domain.usecase.SubmitAnswerUseCase
import ru.poporyadku.domain.usecase.SubmitResult

/** Ключи восстановления порядка карточек (I3-D26). */
internal const val KEY_CURRENT_ORDER = "puzzle.currentOrder"
internal const val KEY_ORDER_PUZZLE_ID = "puzzle.orderPuzzleId"

/** Разделитель — тот же, что у `puzzle_attempts.submitted_order` (`ProgressMappers`). */
private const val ORDER_SEPARATOR = ","

private const val LAST_SLOT_INDEX = SLOTS_PER_DAY - 1

/**
 * ViewModel игрового экрана (ITERATION_3_DESIGN.md, раздел 11).
 *
 * Инъекции ограничены двумя use cases и `SavedStateHandle`. Калькулятор счёта,
 * репозитории прогресса и контента, провайдеры часов и даты сюда **не** инжектируются:
 * считать счёт, писать попытку и определять «сегодня» этому экрану нечем, и отсутствие
 * зависимости — единственная проверяемая форма этого запрета (проверка `rg` раздела 22.4
 * ITERATION_3_DESIGN.md обязана не находить их имён в этом пакете вовсе — поэтому здесь
 * они не названы и в комментарии).
 *
 * Порядок карточек — единственное, что ViewModel держит сама; он живёт в состоянии и
 * дублируется в `SavedStateHandle` строкой идентификаторов. UI список не переставляет.
 */
@HiltViewModel
class PuzzleViewModel @Inject constructor(
    private val getPuzzle: GetPuzzleUseCase,
    private val submitAnswer: SubmitAnswerUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Первое, что делает ViewModel: без валидного маршрута играть не на чем. */
    private val route: RouteArgs = savedStateHandle.readPuzzleRoute()

    private val state = MutableStateFlow<PuzzleUiState>(PuzzleUiState.Loading)
    val uiState: StateFlow<PuzzleUiState> = state.asStateFlow()

    private val effectChannel = Channel<PuzzleEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I3-D25). */
    val effects: Flow<PuzzleEffect> = effectChannel.receiveAsFlow()

    init {
        when (route) {
            // Ни одного обращения к use case и ни одной записи в базу: экран уходит на
            // Home, где состояние пересчитывается штатно (I3-D39).
            is RouteArgs.Invalid -> failStructurally(PuzzleErrorKind.InvalidRoute)
            is RouteArgs.Valid -> load()
        }
    }

    fun onEvent(event: PuzzleEvent) {
        when (event) {
            is PuzzleEvent.MoveUp -> move(event.cardId) { index, _ -> index - 1 }
            is PuzzleEvent.MoveDown -> move(event.cardId) { index, _ -> index + 1 }
            is PuzzleEvent.MoveToTop -> move(event.cardId) { _, _ -> 0 }
            is PuzzleEvent.MoveToBottom -> move(event.cardId) { _, last -> last }

            PuzzleEvent.Submit -> onSubmit()
            PuzzleEvent.SkipClicked -> onSkip()
            PuzzleEvent.RetryClicked -> onRetry()
            PuzzleEvent.BackPressed -> onBackPressed()

            // I3-D24: жеста в итерации 3 нет, и события эти не отправляет ни один
            // компонент. Ветка существует ровно для исчерпывающего `when`.
            is PuzzleEvent.DragStarted,
            is PuzzleEvent.DragMoved,
            PuzzleEvent.DragEnded,
            PuzzleEvent.DragHintDismissed,
            -> Unit
        }
    }

    // --- Загрузка --------------------------------------------------------------------

    private fun load() {
        val args = route as? RouteArgs.Valid ?: return
        state.value = PuzzleUiState.Loading
        viewModelScope.launch {
            try {
                when (val result = getPuzzle(args.date, args.slotIndex)) {
                    is GetPuzzleResult.Playable -> state.value = PuzzleUiState.Playing(
                        board = buildBoard(result.puzzle, args.slotIndex, orderOf(result)),
                        isSubmitEnabled = true,
                        showDragHint = false,
                    )

                    // Промежуточного кадра нет: состояние остаётся Loading, экран
                    // немедленно уходит по таблице I3-D45.
                    is GetPuzzleResult.AlreadyClosed ->
                        emitEffect(effectForClosedSlot(result.kind, result.slotIndex))

                    is GetPuzzleResult.Failure -> onLoadFailure(result.kind)
                }
            } catch (e: CancellationException) {
                // Отмена скоупа обязана остаться отменой, а не стать состоянием экрана.
                throw e
            } catch (e: Exception) {
                state.value = PuzzleUiState.Error(PuzzleErrorKind.Storage, RetryAction.Reload, null)
            }
        }
    }

    private fun onLoadFailure(kind: PuzzleErrorKind) {
        when (kind) {
            // Skippable: «Задание недоступно» + «Пропустить» (I3-D28).
            PuzzleErrorKind.PuzzleNotFound,
            PuzzleErrorKind.InvalidPuzzle,
            -> state.value = PuzzleUiState.Error(kind, RetryAction.Reload, null)

            PuzzleErrorKind.Storage ->
                state.value = PuzzleUiState.Error(kind, RetryAction.Reload, null)

            // Играть нечего и повторять нечего: уходим на Home без кадра ошибки.
            PuzzleErrorKind.InvalidRoute,
            PuzzleErrorKind.SlotOutOfRange,
            PuzzleErrorKind.NoAssignment,
            PuzzleErrorKind.SetNotFound,
            -> failStructurally(kind)
        }
    }

    private fun failStructurally(kind: PuzzleErrorKind) {
        state.value = PuzzleUiState.Error(kind, RetryAction.None, null)
        emitEffect(PuzzleEffect.NavigateHome)
    }

    // --- Порядок карточек ------------------------------------------------------------

    /**
     * Восстановленный порядок принимается, только если он относится к ЭТОЙ головоломке
     * и является перестановкой ровно её карточек (I3-D26).
     *
     * Проверяются все реальные способы испортить значение: чужая головоломка,
     * обрезанная строка, дубликат, лишний или отсутствующий `cardId`. При любом из них —
     * молча детерминированный стартовый порядок: перестановки до «Проверить»
     * бесплатны, и ошибка была бы хуже, чем начать заново.
     */
    private fun orderOf(playable: GetPuzzleResult.Playable): List<String> {
        val cardIds = playable.puzzle.cards.map { it.cardId }
        val restored = savedStateHandle.get<String>(KEY_CURRENT_ORDER)
            ?.split(ORDER_SEPARATOR)
            .orEmpty()

        val valid = savedStateHandle.get<String>(KEY_ORDER_PUZZLE_ID) == playable.puzzle.puzzleId &&
            restored.size == cardIds.size &&
            restored.toSet() == cardIds.toSet()

        return if (valid) restored else playable.startOrder
    }

    private fun buildBoard(puzzle: Puzzle, slotIndex: Int, order: List<String>): PuzzleBoard {
        val byId = puzzle.cards.associateBy { it.cardId }
        return PuzzleBoard(
            slotIndex = slotIndex,
            totalSlots = SLOTS_PER_DAY,
            puzzleId = puzzle.puzzleId,
            category = puzzle.category,
            prompt = puzzle.prompt,
            directionLabel = puzzle.directionLabel,
            cards = order.mapIndexed { index, cardId ->
                val card = requireNotNull(byId[cardId]) { "порядок содержит чужой cardId: $cardId" }
                CardUi(
                    cardId = card.cardId,
                    title = card.title,
                    subtitle = card.subtitle,
                    position = index + 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < order.lastIndex,
                )
            },
            draggedCardId = null,
        )
    }

    /**
     * Перестановка принимается только из `Playing`: в `Submitting` и `Error` двигать
     * нечего, и «блокировка управления» — свойство состояния, а не флаг.
     *
     * [targetIndex] получает текущий индекс карточки и индекс последней; выход за
     * границы означает «действие неприменимо» и не меняет состояние.
     */
    private fun move(cardId: String, targetIndex: (index: Int, lastIndex: Int) -> Int) {
        val playing = state.value as? PuzzleUiState.Playing ?: return
        val cards = playing.board.cards
        val index = cards.indexOfFirst { it.cardId == cardId }
        if (index < 0) return

        val target = targetIndex(index, cards.lastIndex)
        if (target == index || target !in cards.indices) return

        val reordered = cards.toMutableList().apply { add(target, removeAt(index)) }
        val order = reordered.map { it.cardId }
        val board = playing.board.copy(
            cards = reordered.mapIndexed { position, card ->
                card.copy(
                    position = position + 1,
                    canMoveUp = position > 0,
                    canMoveDown = position < reordered.lastIndex,
                )
            },
        )
        state.value = playing.copy(board = board)

        // Немедленно, оба ключа сразу: после смерти процесса восстанавливается ровно то,
        // что пользователь видел.
        savedStateHandle[KEY_CURRENT_ORDER] = order.joinToString(ORDER_SEPARATOR)
        savedStateHandle[KEY_ORDER_PUZZLE_ID] = board.puzzleId

        emitEffect(
            PuzzleEffect.AnnounceCardMoved(
                cardTitle = cards[index].title,
                position = target + 1,
                totalPositions = reordered.size,
            ),
        )
    }

    // --- Отправка --------------------------------------------------------------------

    /**
     * `Submit` принимается только из `Playing`. Повторное нажатие некуда доставить:
     * состояние уже `Submitting`, и `when` уходит в `else`. Ни флага, ни таймера,
     * ни debounce.
     */
    private fun onSubmit() {
        val playing = state.value as? PuzzleUiState.Playing ?: return
        val submission = Submission.Answer(playing.board.cards.map { it.cardId })
        state.value = PuzzleUiState.Submitting.Answer(playing.board, submission)
        submit(submission, playing.board)
    }

    /**
     * «Пропустить» — только с `Error`, и только с двух видов ошибки (I3-D28). Второй
     * `SkipClicked` тем же приёмом не запускает вторую запись: состояние уже
     * `Submitting.Skip`, и событие не проходит `as?`.
     */
    private fun onSkip() {
        val error = state.value as? PuzzleUiState.Error ?: return
        if (!error.kind.isSkippable) return
        state.value = PuzzleUiState.Submitting.Skip(error.kind)
        submit(Submission.Skip, board = null)
    }

    /**
     * Один обработчик без ветвлений по «фазе»: что повторять, знает само состояние.
     */
    private fun onRetry() {
        val error = state.value as? PuzzleUiState.Error ?: return
        when (val retry = error.retry) {
            RetryAction.Reload -> load()

            is RetryAction.Resubmit -> when (val pending = retry.submission) {
                is Submission.Answer -> {
                    // «Стол» без карточек не отрисовать; при его потере честнее
                    // перезагрузить головоломку, чем выдумывать порядок.
                    val board = error.board
                    if (board == null) {
                        load()
                    } else {
                        state.value = PuzzleUiState.Submitting.Answer(board, pending)
                        submit(pending, board)
                    }
                }

                Submission.Skip -> {
                    // Композиция та же, с которой пришли: отказ записи пропуска
                    // показывает «Не удалось сохранить ответ», а не «Задание недоступно».
                    state.value = PuzzleUiState.Submitting.Skip(error.kind)
                    submit(pending, board = null)
                }
            }

            RetryAction.None -> emitEffect(PuzzleEffect.NavigateHome)
        }
    }

    /**
     * Навигационный эффект отправляется **только** после завершения записи: обратный
     * порядок оставил бы слот неотвеченным при уже показанном результате.
     */
    private fun submit(submission: Submission, board: PuzzleBoard?) {
        val args = route as? RouteArgs.Valid ?: return
        viewModelScope.launch {
            try {
                when (val result = submitAnswer(args.date, args.slotIndex, submission)) {
                    is SubmitResult.Recorded ->
                        emitEffect(effectForClosedSlot(result.kind, result.slotIndex))

                    // Гонка: идём по ПОБЕДИВШЕЙ записи, а не по своему намерению.
                    is SubmitResult.AlreadyClosed ->
                        emitEffect(effectForClosedSlot(result.kind, result.slotIndex))

                    is SubmitResult.Failure -> onSubmitFailure(result.kind, submission, board)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = PuzzleUiState.Error(
                    kind = PuzzleErrorKind.Storage,
                    retry = RetryAction.Resubmit(submission),
                    board = board,
                )
            }
        }
    }

    private fun onSubmitFailure(
        kind: PuzzleErrorKind,
        submission: Submission,
        board: PuzzleBoard?,
    ) {
        when (kind) {
            PuzzleErrorKind.Storage -> state.value =
                PuzzleUiState.Error(kind, RetryAction.Resubmit(submission), board)

            // Контент под слотом сломался между загрузкой и отправкой: остаётся пропуск.
            PuzzleErrorKind.PuzzleNotFound,
            PuzzleErrorKind.InvalidPuzzle,
            -> state.value = PuzzleUiState.Error(kind, RetryAction.Reload, null)

            PuzzleErrorKind.InvalidRoute,
            PuzzleErrorKind.SlotOutOfRange,
            PuzzleErrorKind.NoAssignment,
            PuzzleErrorKind.SetNotFound,
            -> failStructurally(kind)
        }
    }

    /**
     * `BackPressed` из `Submitting` игнорируется: запись уже идёт, и уход с экрана до её
     * завершения оставил бы пользователя без зафиксированного результата.
     */
    private fun onBackPressed() {
        if (state.value is PuzzleUiState.Submitting) return
        emitEffect(PuzzleEffect.NavigateHome)
    }

    private fun emitEffect(effect: PuzzleEffect) {
        effectChannel.trySend(effect)
    }

    private companion object {

        /** Skippable — ровно два вида: только у них известен `puzzleId` и есть что записать. */
        val PuzzleErrorKind.isSkippable: Boolean
            get() = this == PuzzleErrorKind.PuzzleNotFound || this == PuzzleErrorKind.InvalidPuzzle

        /**
         * Единственная таблица навигации закрытого слота (I3-D45): успешная запись,
         * повтор, проигранная гонка, восстановление процесса и прямое открытие маршрута
         * обслуживаются ею одной. `Skipped` не ведёт на `PuzzleResult` никогда.
         */
        fun effectForClosedSlot(kind: AttemptKind, slotIndex: Int): PuzzleEffect = when (kind) {
            AttemptKind.Answered -> PuzzleEffect.NavigateToResult(slotIndex)
            AttemptKind.Skipped ->
                if (slotIndex < LAST_SLOT_INDEX) {
                    PuzzleEffect.NavigateToNextSlot(slotIndex + 1)
                } else {
                    PuzzleEffect.NavigateToRecap
                }
        }
    }
}
