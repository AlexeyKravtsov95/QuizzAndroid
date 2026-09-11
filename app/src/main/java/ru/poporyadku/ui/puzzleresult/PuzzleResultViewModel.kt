package ru.poporyadku.ui.puzzleresult

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import ru.poporyadku.core.model.SLOTS_PER_DAY
import ru.poporyadku.domain.repository.UserPreferencesRepository
import ru.poporyadku.domain.usecase.GetPuzzleResultUseCase
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.PuzzleResultLoad
import ru.poporyadku.ui.navigation.RouteOrigin

private const val LAST_SLOT_INDEX = SLOTS_PER_DAY - 1

/**
 * ViewModel экрана результата (ITERATION_3_DESIGN.md, I3-D21, I3-D49;
 * ITERATION_5_DESIGN.md, §3.7, §4.4, I5-D10, I5-D11).
 *
 * Репозиториев контента и прогресса и калькулятора счёта здесь нет: экран восстанавливает
 * себя одним use case по паре `(localDate, slotIndex)`. Отображение доменного
 * `PuzzleResultLoad` в экранную модель делает именно ViewModel — use case про экранные
 * типы не знает.
 *
 * **Два режима по `origin`.** Сессионный сохраняет поведение итерации 3. Архивный
 * отправляет только [PuzzleResultEffect.NavigateBack]: переходы в игровой маршрут
 * `puzzle/{slotIndex}`, к следующему слоту и в сессионный итог в нём не существуют ни при
 * каком исходе — исторический результат никогда не запускает игру прошлого дня.
 */
@HiltViewModel
class PuzzleResultViewModel @Inject constructor(
    private val getPuzzleResult: GetPuzzleResultUseCase,
    private val preferences: UserPreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Тот же строгий разбор даты и слота, что у `Puzzle` (I3-D39), плюс `origin`. */
    private val route: ResultRouteArgs = savedStateHandle.readResultRoute()

    private val state = MutableStateFlow<PuzzleResultState>(PuzzleResultState.Loading)
    val uiState: StateFlow<PuzzleResultState> = state.asStateFlow()

    private val effectChannel = Channel<PuzzleResultEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I3-D25). */
    val effects: Flow<PuzzleResultEffect> = effectChannel.receiveAsFlow()

    init {
        when (route) {
            // Невалидный маршрут (дата, слот или origin): режима нет, и единственный
            // безопасный исход — существующий Home, как в итерации 3.
            is ResultRouteArgs.Invalid -> {
                state.value = PuzzleResultState.Error(PuzzleErrorKind.InvalidRoute)
                effectChannel.trySend(PuzzleResultEffect.NavigateHome)
            }

            is ResultRouteArgs.Valid -> load(route)
        }
    }

    fun onEvent(event: PuzzleResultEvent) {
        val args = route as? ResultRouteArgs.Valid
        when (event) {
            PuzzleResultEvent.PrimaryAction -> {
                val content = state.value as? PuzzleResultState.Content ?: return
                effectChannel.trySend(
                    when (content.origin) {
                        RouteOrigin.Session -> nextStepFor(content.slotIndex)
                        // «К итогу дня» архивного режима — назад, к архивному итогу.
                        RouteOrigin.Archive -> PuzzleResultEffect.NavigateBack(isRedirect = false)
                    },
                )
            }

            PuzzleResultEvent.BackPressed -> effectChannel.trySend(
                when (args?.origin) {
                    RouteOrigin.Archive -> PuzzleResultEffect.NavigateBack(isRedirect = false)
                    RouteOrigin.Session, null -> PuzzleResultEffect.NavigateHome
                },
            )
        }
    }

    private fun load(args: ResultRouteArgs.Valid) {
        viewModelScope.launch {
            try {
                when (val load = getPuzzleResult(args.date, args.slotIndex)) {
                    is PuzzleResultLoad.Content ->
                        state.value = load.toContent(args.origin, readScoringHint())

                    // Показывать нечего: ни правильного порядка, ни объяснения. Кадра
                    // не показываем: в сессии — дальше по таблице I3-D45, в архиве —
                    // назад к итогу (достижимо только восстановлением бэкстека).
                    is PuzzleResultLoad.Skipped -> effectChannel.trySend(
                        when (args.origin) {
                            RouteOrigin.Session -> nextStepFor(load.slotIndex)
                            RouteOrigin.Archive -> PuzzleResultEffect.NavigateBack(isRedirect = true)
                        },
                    )

                    // Слот ещё не сыгран. В сессии — назад в головоломку; в архиве
                    // игровой маршрут запрещён: доигрывать прошлый день нельзя.
                    is PuzzleResultLoad.NoAttempt -> effectChannel.trySend(
                        when (args.origin) {
                            RouteOrigin.Session -> PuzzleResultEffect.NavigateToPuzzle(load.slotIndex)
                            RouteOrigin.Archive -> PuzzleResultEffect.NavigateBack(isRedirect = true)
                        },
                    )

                    is PuzzleResultLoad.Failure ->
                        state.value = PuzzleResultState.Error(load.kind)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = PuzzleResultState.Error(PuzzleErrorKind.Storage)
            }
        }
    }

    /**
     * Флаг ставится при ПОКАЗЕ, а не при уходе с экрана: иначе убийство процесса на
     * первом же результате показало бы подсказку второй раз.
     */
    private suspend fun readScoringHint(): Boolean {
        val show = !preferences.preferences.first().hasSeenScoringHint
        if (show) preferences.setHasSeenScoringHint(true)
        return show
    }

    private fun PuzzleResultLoad.Content.toContent(
        origin: RouteOrigin,
        showScoringHint: Boolean,
    ): PuzzleResultState.Content {
        val cardsById = puzzle.cards.associateBy { it.cardId }
        return PuzzleResultState.Content(
            slotIndex = slotIndex,
            totalSlots = SLOTS_PER_DAY,
            correctOrder = puzzle.correctOrder.mapIndexed { index, cardId ->
                val card = requireNotNull(cardsById[cardId]) {
                    "correctOrder ссылается на чужой cardId: $cardId"
                }
                ResultCardUi(
                    cardId = card.cardId,
                    position = index + 1,
                    title = card.title,
                    subtitle = card.subtitle,
                    displayValue = card.displayValue,
                )
            },
            submittedOrder = attempt.submittedOrder,
            // Счёт — из попытки: он уже вошёл в day_results, в итог дня и в статистику.
            // Пары — из пересчёта того же порядка.
            score = attempt.score,
            invertedPairs = scored.invertedPairs,
            explanation = puzzle.explanation,
            sources = puzzle.sources,
            showScoringHint = showScoringHint,
            isLastSlot = slotIndex == LAST_SLOT_INDEX,
            // Из попытки, а не из набора: показывать надо ту головоломку, на которую
            // отвечал игрок.
            puzzleId = attempt.puzzleId,
            origin = origin,
            // Решение отзыва принял домен; экран только показывает пометку.
            isRetired = isRetired,
        )
    }

    /** Только сессия: одна таблица следующего шага для CTA и для редиректа пропуска. */
    private fun nextStepFor(slotIndex: Int): PuzzleResultEffect =
        if (slotIndex < LAST_SLOT_INDEX) {
            PuzzleResultEffect.NavigateToNextSlot(slotIndex + 1)
        } else {
            PuzzleResultEffect.NavigateToRecap
        }
}
