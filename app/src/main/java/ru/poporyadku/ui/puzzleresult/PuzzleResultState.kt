package ru.poporyadku.ui.puzzleresult

import ru.poporyadku.core.model.Puzzle
import ru.poporyadku.domain.scoring.InvertedPair
import ru.poporyadku.domain.usecase.PuzzleErrorKind

/**
 * Карточка правильного порядка (ITERATION_3_DESIGN.md, I3-D21).
 *
 * [displayValue] — «5642 м»: именно оно делает экран обучающим, и потому это отдельное
 * поле, а не часть заголовка.
 */
data class ResultCardUi(
    val cardId: String,
    /** 1..4 — итоговая позиция в ПРАВИЛЬНОМ порядке. */
    val position: Int,
    val title: String,
    val subtitle: String?,
    val displayValue: String,
)

/**
 * Состояние экрана результата (ITERATION_3_DESIGN.md, I3-D21).
 *
 * Всё содержимое выводится из пары `(localDate, slotIndex)`: через `Bundle` не едет ни
 * `Puzzle`, ни `PairwiseScore`, ни само это состояние.
 */
sealed interface PuzzleResultState {

    data object Loading : PuzzleResultState

    data class Content(
        val slotIndex: Int,
        val totalSlots: Int,
        /** В правильном порядке, со значениями. */
        val correctOrder: List<ResultCardUi>,
        /** `cardId` в порядке пользователя; нужен для проверок и тестов. */
        val submittedOrder: List<String>,
        /** 0..6 — из СОХРАНЁННОЙ попытки, а не из пересчёта. */
        val score: Int,
        val invertedPairs: List<InvertedPair>,
        val explanation: String,
        val sources: List<Puzzle.Source>,
        /** `hasSeenScoringHint == false`: первый в жизни результат. */
        val showScoringHint: Boolean,
        /** `slotIndex == 2` → CTA «К итогу дня». */
        val isLastSlot: Boolean,
        /** Для «Сообщить о неточности» (итерация 5). */
        val puzzleId: String,
    ) : PuzzleResultState

    data class Error(val kind: PuzzleErrorKind) : PuzzleResultState
}
