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
import ru.poporyadku.ui.puzzle.RouteArgs
import ru.poporyadku.ui.puzzle.readPuzzleRoute

private const val LAST_SLOT_INDEX = SLOTS_PER_DAY - 1

/**
 * ViewModel экрана результата (ITERATION_3_DESIGN.md, I3-D21, I3-D49).
 *
 * Репозиториев контента и прогресса и калькулятора счёта здесь нет: экран восстанавливает
 * себя одним use case по паре `(localDate, slotIndex)`. Отображение доменного
 * `PuzzleResultLoad` в экранную модель делает именно ViewModel — use case про экранные
 * типы не знает.
 */
@HiltViewModel
class PuzzleResultViewModel @Inject constructor(
    private val getPuzzleResult: GetPuzzleResultUseCase,
    private val preferences: UserPreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Тот же разбор аргументов, что и у `Puzzle`: два маршрута — один контракт (I3-D39). */
    private val route: RouteArgs = savedStateHandle.readPuzzleRoute()

    private val state = MutableStateFlow<PuzzleResultState>(PuzzleResultState.Loading)
    val uiState: StateFlow<PuzzleResultState> = state.asStateFlow()

    private val effectChannel = Channel<PuzzleResultEffect>(Channel.BUFFERED)

    /** Ровно один коллектор на стороне UI — на уровне route-контейнера (I3-D25). */
    val effects: Flow<PuzzleResultEffect> = effectChannel.receiveAsFlow()

    init {
        when (route) {
            is RouteArgs.Invalid -> {
                state.value = PuzzleResultState.Error(PuzzleErrorKind.InvalidRoute)
                effectChannel.trySend(PuzzleResultEffect.NavigateHome)
            }

            is RouteArgs.Valid -> load(route)
        }
    }

    fun onEvent(event: PuzzleResultEvent) {
        when (event) {
            PuzzleResultEvent.PrimaryAction -> {
                val content = state.value as? PuzzleResultState.Content ?: return
                effectChannel.trySend(nextStepFor(content.slotIndex))
            }

            PuzzleResultEvent.BackPressed ->
                effectChannel.trySend(PuzzleResultEffect.NavigateHome)
        }
    }

    private fun load(args: RouteArgs.Valid) {
        viewModelScope.launch {
            try {
                when (val load = getPuzzleResult(args.date, args.slotIndex)) {
                    is PuzzleResultLoad.Content -> state.value = load.toContent(readScoringHint())

                    // Показывать нечего: ни правильного порядка, ни объяснения. Кадра
                    // не показываем — сразу дальше по таблице I3-D45.
                    is PuzzleResultLoad.Skipped ->
                        effectChannel.trySend(nextStepFor(load.slotIndex))

                    // Слот ещё не сыгран: возвращаемся в головоломку.
                    is PuzzleResultLoad.NoAttempt ->
                        effectChannel.trySend(PuzzleResultEffect.NavigateToPuzzle(load.slotIndex))

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

    private fun PuzzleResultLoad.Content.toContent(showScoringHint: Boolean): PuzzleResultState.Content {
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
        )
    }

    /** Одна таблица следующего шага для CTA и для редиректа пропущенного слота. */
    private fun nextStepFor(slotIndex: Int): PuzzleResultEffect =
        if (slotIndex < LAST_SLOT_INDEX) {
            PuzzleResultEffect.NavigateToNextSlot(slotIndex + 1)
        } else {
            PuzzleResultEffect.NavigateToRecap
        }
}
