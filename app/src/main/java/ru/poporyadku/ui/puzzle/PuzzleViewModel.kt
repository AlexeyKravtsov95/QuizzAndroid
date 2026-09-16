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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.domain.model.FeedbackSettings
import ru.poporyadku.domain.usecase.AttemptKind
import ru.poporyadku.domain.usecase.GetPuzzleResult
import ru.poporyadku.domain.usecase.GetPuzzleUseCase
import ru.poporyadku.domain.usecase.ObserveFeedbackSettingsUseCase
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.Submission
import ru.poporyadku.domain.usecase.SubmitAnswerUseCase
import ru.poporyadku.domain.usecase.SubmitResult
import ru.poporyadku.ui.feedback.FeedbackCue
import ru.poporyadku.ui.feedback.FeedbackPolicy

/** Ключи восстановления порядка карточек (I3-D26). */
internal const val KEY_CURRENT_ORDER = "puzzle.currentOrder"
internal const val KEY_ORDER_PUZZLE_ID = "puzzle.orderPuzzleId"

/** Разделитель — тот же, что у `puzzle_attempts.submitted_order` (`ProgressMappers`). */
private const val ORDER_SEPARATOR = ","

private const val LAST_SLOT_INDEX = SLOTS_PER_DAY - 1

/**
 * Откуда пришло намерение перестановки (ITERATION_6_DESIGN.md, §4.4).
 *
 * Различие нужно ровно для двух вещей: кнопка и custom action закрывают висящую сессию
 * жеста и объявляют результат сразу, жест — только при завершении. Алгоритм перестановки
 * от источника не зависит.
 */
private enum class MoveOrigin { Button, Drag }

/**
 * ViewModel игрового экрана (ITERATION_3_DESIGN.md, раздел 11).
 *
 * Инъекции ограничены тремя use cases и `SavedStateHandle` — третий появился в
 * итерации 5: `ObserveFeedbackSettingsUseCase`, только чтение двух переключателей
 * отдачи (ITERATION_5_DESIGN.md, §4.7). Калькулятор счёта,
 * репозитории прогресса и контента, провайдеры часов и даты сюда **не** инжектируются:
 * считать счёт, писать попытку и определять «сегодня» этому экрану нечем, и отсутствие
 * зависимости — единственная проверяемая форма этого запрета (проверка `rg` раздела 22.4
 * ITERATION_3_DESIGN.md обязана не находить их имён в этом пакете вовсе — поэтому здесь
 * они не названы и в комментарии).
 *
 * Порядок карточек — единственное, что ViewModel держит сама; он живёт в состоянии и
 * дублируется в `SavedStateHandle` строкой идентификаторов. UI список не переставляет —
 * в том числе во время жеста: он присылает намерение, а не готовый порядок
 * (ITERATION_6_DESIGN.md, §4.4, I6-D6).
 *
 * Все три источника перестановки — кнопки ↑/↓, custom actions и перетаскивание — идут
 * одним путём: `PuzzleEvent` → [reorder] → `CardOrder.move` → состояние →
 * `SavedStateHandle` → отдача (проверка `I6-K6`).
 *
 * Отдача решается здесь, а исполняется в route-контейнере: звуковой пул, `View` и
 * `HapticFeedbackConstants` во ViewModel не появляются, поэтому все комбинации настроек
 * проверяются JVM-тестами по содержимому эффекта (`I5-V29`…`I5-V34`).
 */
@HiltViewModel
class PuzzleViewModel @Inject constructor(
    private val getPuzzle: GetPuzzleUseCase,
    private val submitAnswer: SubmitAnswerUseCase,
    observeFeedbackSettings: ObserveFeedbackSettingsUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Первое, что делает ViewModel: без валидного маршрута играть не на чем. */
    private val route: RouteArgs = savedStateHandle.readPuzzleRoute()

    private val state = MutableStateFlow<PuzzleUiState>(PuzzleUiState.Loading)
    val uiState: StateFlow<PuzzleUiState> = state.asStateFlow()

    private val effectChannel = Channel<PuzzleEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I3-D25). */
    val effects: Flow<PuzzleEffect> = effectChannel.receiveAsFlow()

    /**
     * Настройки отдачи (ITERATION_5_DESIGN.md, §3.14, §6.10, I5-D22).
     *
     * `Eagerly` — чтение начинается при создании ViewModel, а не с первой подпиской: у
     * этого потока подписчиков нет вовсе, его значение читается синхронно в момент
     * события. `Unknown` до первой эмиссии означает «отдачи нет»: см. `FeedbackPolicy`.
     */
    private val feedbackSettings: StateFlow<FeedbackSettings> =
        observeFeedbackSettings().stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FeedbackSettings.Unknown,
        )

    init {
        when (route) {
            // Ни одного обращения к use case и ни одной записи в базу: экран уходит на
            // Home, где состояние пересчитывается штатно (I3-D39).
            is RouteArgs.Invalid -> failStructurally(PuzzleErrorKind.InvalidRoute)
            is RouteArgs.Valid -> load()
        }
    }

    /**
     * Сессия перетаскивания (ITERATION_6_DESIGN.md, §4.5, I6-D9).
     *
     * Только в памяти и никогда в `SavedStateHandle`: после поворота, пересоздания
     * Activity или смерти процесса пальца на экране нет.
     *
     * Нужна ровно для двух вещей: не отдать `CardGrabbed` дважды за **один** жест и
     * объявить результат **этого** жеста один раз.
     */
    private data class DragSession(
        val cardId: String,
        val gesture: DragGestureId,
        val startIndex: Int,
    )

    private var dragSession: DragSession? = null

    fun onEvent(event: PuzzleEvent) {
        when (event) {
            is PuzzleEvent.MoveUp -> reorder(event.cardId, MoveTarget.Up, MoveOrigin.Button)
            is PuzzleEvent.MoveDown -> reorder(event.cardId, MoveTarget.Down, MoveOrigin.Button)
            is PuzzleEvent.MoveToTop -> reorder(event.cardId, MoveTarget.First, MoveOrigin.Button)
            is PuzzleEvent.MoveToBottom -> reorder(event.cardId, MoveTarget.Last, MoveOrigin.Button)

            is PuzzleEvent.DragStarted -> onDragStarted(event.cardId, event.gesture)
            is PuzzleEvent.DragMovedTo ->
                onDragMovedTo(event.cardId, event.targetIndex, event.gesture)
            is PuzzleEvent.DragFinished -> onDragFinished(event.cardId, event.gesture)

            PuzzleEvent.Submit -> onSubmit()
            PuzzleEvent.SkipClicked -> onSkip()
            PuzzleEvent.RetryClicked -> onRetry()
            PuzzleEvent.BackPressed -> onBackPressed()

            // Подсказка перетаскивания заполняется в 6C; ветка существует ровно для
            // исчерпывающего `when`.
            PuzzleEvent.DragHintDismissed -> Unit
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
        )
    }

    /**
     * **Единственная** точка изменения порядка (ITERATION_6_DESIGN.md, §4.4, I6-D4).
     *
     * Кнопки ↑/↓, custom actions TalkBack и жест приходят сюда одним и тем же намерением
     * и проходят один и тот же алгоритм [CardOrder.move] — поэтому разойтись в поведении
     * они не могут, а «недоступная перестановка молчит» остаётся свойством потока
     * управления, а не отдельной проверкой в каждом источнике.
     *
     * Принимается только из `Playing`: в `Submitting` и `Error` двигать нечего, и
     * «блокировка управления» — свойство состояния, а не флаг.
     *
     * @return `true`, если порядок изменился.
     */
    private fun reorder(cardId: String, target: MoveTarget, origin: MoveOrigin): Boolean {
        val playing = state.value as? PuzzleUiState.Playing ?: return false

        // Кнопка и custom action закрывают висящую сессию жеста молча: подтверждённый
        // порядок уже в состоянии, и жест, начатый до них, больше ничего не объявляет.
        if (origin == MoveOrigin.Button) dragSession = null

        val cards = playing.board.cards
        val order = cards.map { it.cardId }
        val next = CardOrder.move(order, cardId, target) ?: return false

        val byId = cards.associateBy { it.cardId }
        val board = playing.board.copy(
            cards = next.mapIndexed { position, id ->
                requireNotNull(byId[id]) { "порядок содержит чужой cardId: $id" }.copy(
                    position = position + 1,
                    canMoveUp = position > 0,
                    canMoveDown = position < next.lastIndex,
                )
            },
        )
        state.value = playing.copy(board = board)

        // Немедленно, оба ключа сразу: после смерти процесса восстанавливается ровно то,
        // что пользователь видел.
        savedStateHandle[KEY_CURRENT_ORDER] = next.joinToString(ORDER_SEPARATOR)
        savedStateHandle[KEY_ORDER_PUZZLE_ID] = board.puzzleId

        // Не больше одной отдачи на подтверждённую перестановку: сюда попадают только
        // ненулевые результаты `CardOrder.move`.
        emitFeedback(FeedbackCue.CardMoved)

        // Во время жеста объявлений нет: TalkBack прочитал бы каждое пересечение порога.
        // Жест объявляет один раз, при завершении (I6-D16).
        if (origin == MoveOrigin.Button) announce(cardId, next)
        return true
    }

    /** «{Название} перемещён на позицию N из 4» — структурой, текст собирает route (I3-D25). */
    private fun announce(cardId: String, order: List<String>) {
        val board = (state.value as? PuzzleUiState.Playing)?.board ?: return
        val card = board.cards.firstOrNull { it.cardId == cardId } ?: return
        emitEffect(
            PuzzleEffect.AnnounceCardMoved(
                cardTitle = card.title,
                position = order.indexOf(cardId) + 1,
                totalPositions = order.size,
            ),
        )
    }

    // --- Жест перетаскивания -----------------------------------------------------------

    /**
     * Начало жеста (I6-D9, I6-D14).
     *
     * Повтор начала **того же** жеста игнорируется — второй отдачи захвата не будет.
     * Любая другая сессия, в том числе жест той же карточки из уничтоженного UI,
     * заменяется целиком: `startIndex` берётся заново, а прежний идентификатор больше
     * ничего не закроет и не объявит.
     */
    private fun onDragStarted(cardId: String, gesture: DragGestureId) {
        val playing = state.value as? PuzzleUiState.Playing ?: return
        val index = playing.board.cards.indexOfFirst { it.cardId == cardId }
        if (index < 0) return
        if (dragSession?.gesture == gesture) return

        dragSession = DragSession(cardId, gesture, index)
        emitFeedback(FeedbackCue.CardGrabbed)
    }

    /**
     * Пересечён порог перестановки. Цель абсолютная, поэтому повтор той же цели — пустая
     * операция: `CardOrder.move` вернёт `null`, и ни состояние, ни `SavedStateHandle`, ни
     * отдача не тронуты (I6-D3).
     */
    private fun onDragMovedTo(cardId: String, targetIndex: Int, gesture: DragGestureId) {
        val session = dragSession ?: return
        if (session.gesture != gesture || session.cardId != cardId) return
        reorder(cardId, MoveTarget.Index(targetIndex), MoveOrigin.Drag)
    }

    /**
     * Завершение жеста: отпускание, отмена и потеря указателя — одинаково (I6-D8).
     *
     * Событие чужого или устаревшего жеста не закрывает текущую сессию и не объявляет
     * её результат: сравнивается пара `cardId` + [DragGestureId].
     */
    private fun onDragFinished(cardId: String, gesture: DragGestureId) {
        val session = dragSession ?: return
        if (session.gesture != gesture || session.cardId != cardId) return
        dragSession = null

        val playing = state.value as? PuzzleUiState.Playing ?: return
        val order = playing.board.cards.map { it.cardId }
        // Ровно одно объявление и только если позиция отличается от начала ЭТОГО жеста:
        // вернувшаяся на место карточка сообщать не о чем.
        if (order.indexOf(cardId) != session.startIndex) announce(cardId, order)
    }

    // --- Отправка --------------------------------------------------------------------

    /**
     * `Submit` принимается только из `Playing`. Повторное нажатие некуда доставить:
     * состояние уже `Submitting`, и `when` уходит в `else`. Ни флага, ни таймера,
     * ни debounce.
     */
    private fun onSubmit() {
        val playing = state.value as? PuzzleUiState.Playing ?: return
        // Открытая сессия закрывается молча: отправляется последний подтверждённый
        // порядок, а запоздалое завершение жеста уже ничего не объявит.
        dragSession = null
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
                    is SubmitResult.Recorded -> {
                        // Попытка уже в базе: отдача подтверждает зафиксированное и не
                        // может соврать при отказе записи. Пропуск не подтверждается —
                        // подтверждать нечего.
                        if (result.kind == AttemptKind.Answered) {
                            emitFeedback(FeedbackCue.AnswerAccepted)
                        }
                        emitEffect(effectForClosedSlot(result.kind, result.slotIndex))
                    }

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

    /**
     * Единственный вход отдачи. `load()`, `orderOf()` (восстановление из
     * `SavedStateHandle`), `onSkip()`, отказы записи, `AlreadyClosed` и `BackPressed` его
     * не вызывают — поэтому отдачи в них нет по построению, а не по проверке флага.
     */
    private fun emitFeedback(cue: FeedbackCue) {
        FeedbackPolicy.requestFor(cue, feedbackSettings.value)
            ?.let { request -> emitEffect(PuzzleEffect.Feedback(request)) }
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
