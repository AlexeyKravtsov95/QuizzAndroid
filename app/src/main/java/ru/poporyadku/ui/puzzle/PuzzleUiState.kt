package ru.poporyadku.ui.puzzle

import ru.poporyadku.core.model.Category
import ru.poporyadku.domain.usecase.PuzzleErrorKind
import ru.poporyadku.domain.usecase.Submission

/**
 * Неизменяемый «стол»: то, что показывается и в `Playing`, и в `Submitting.Answer`
 * (ITERATION_3_DESIGN.md, I3-D44).
 *
 * Сгруппирован отдельным типом, чтобы состояние отправки несло **ровно те же** данные,
 * что предшествующее `Playing`, без копирования пяти полей.
 */
data class PuzzleBoard(
    /** 0..2. */
    val slotIndex: Int,
    /** Всегда `SLOTS_PER_DAY`. */
    val totalSlots: Int,
    val puzzleId: String,
    val category: Category,
    val prompt: String,
    val directionLabel: String,
    /** Текущий порядок пользователя; идентичность и `key` списка — `cardId`. */
    val cards: List<CardUi>,
    /** Всегда `null` в итерации 3: жеста перетаскивания нет (I3-D24). */
    val draggedCardId: String?,
)

/**
 * Состояние игрового экрана (ITERATION_3_DESIGN.md, раздел 11, I3-D44).
 *
 * Экран stateless, поэтому каждое состояние обязано быть представимо целиком: из
 * состояния-синглтона «идёт запись» нечего отрисовать, и во время записи экран показал
 * бы пустоту вместо тех же четырёх карточек с отключённым управлением.
 */
sealed interface PuzzleUiState {

    data object Loading : PuzzleUiState

    data class Playing(
        val board: PuzzleBoard,
        val isSubmitEnabled: Boolean,
        /** Всегда `false` в итерации 3: `DragEducationHint` не рендерится (I3-D24). */
        val showDragHint: Boolean,
    ) : PuzzleUiState

    /**
     * Идёт запись. Два варианта, потому что во время записи рисуется разное: у ответа
     * есть «стол», у пропуска его не существует по построению (I3-D44).
     */
    sealed interface Submitting : PuzzleUiState {

        /** Чем повторить отправку при отказе записи; собирается одинаково для обоих вариантов. */
        val pending: Submission

        /** Отправлен ответ: те же карточки и тот же порядок, управление отключено. */
        data class Answer(
            val board: PuzzleBoard,
            override val pending: Submission.Answer,
        ) : Submitting

        /**
         * Отправлен пропуск. Запускается только из [Error], где «стола» не существует:
         * экран продолжает рисовать ту же композицию ошибки, а карточки не выдумываются.
         *
         * [sourceErrorKind] — вид ошибки, с которой пришли: `PuzzleNotFound`/`InvalidPuzzle`
         * при первой отправке и `Storage` при повторе неудавшегося пропуска. Экран рисует
         * по нему **ту же** композицию, что была до нажатия, со всеми действиями `disabled`.
         */
        data class Skip(
            val sourceErrorKind: PuzzleErrorKind,
        ) : Submitting {
            override val pending: Submission get() = Submission.Skip
        }
    }

    /** Ошибка знает, что повторять (I3-D44). */
    data class Error(
        val kind: PuzzleErrorKind,
        val retry: RetryAction,
        /** Не `null` только при отказе записи ответа: «стол» последнего `Playing`. */
        val board: PuzzleBoard?,
    ) : PuzzleUiState
}
