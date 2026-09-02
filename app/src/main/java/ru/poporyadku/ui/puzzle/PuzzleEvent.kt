package ru.poporyadku.ui.puzzle

/**
 * События игрового экрана (ARCHITECTURE.md §4; ITERATION_3_DESIGN.md, раздел 11).
 *
 * Жестовые события сохранены в контракте намеренно (I3-D24): итерация 6 заполняет уже
 * существующий тип, а не переписывает его. В итерации 3 их не отправляет ни один
 * компонент — ни `DragHandle`, ни `DragEducationHint` не создаются.
 */
sealed interface PuzzleEvent {

    data class DragStarted(val cardId: String) : PuzzleEvent
    data class DragMoved(val fromIndex: Int, val toIndex: Int) : PuzzleEvent
    data object DragEnded : PuzzleEvent
    data object DragHintDismissed : PuzzleEvent

    data class MoveUp(val cardId: String) : PuzzleEvent
    data class MoveDown(val cardId: String) : PuzzleEvent

    /** Accessibility action; кнопки для него на экране нет. */
    data class MoveToTop(val cardId: String) : PuzzleEvent

    /** Accessibility action; кнопки для него на экране нет. */
    data class MoveToBottom(val cardId: String) : PuzzleEvent

    data object Submit : PuzzleEvent
    data object BackPressed : PuzzleEvent
    data object RetryClicked : PuzzleEvent

    /** Доступно только на `Error(PuzzleNotFound/InvalidPuzzle)` (I3-D28). */
    data object SkipClicked : PuzzleEvent
}
