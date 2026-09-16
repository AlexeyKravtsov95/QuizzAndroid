package ru.poporyadku.ui.puzzle

/**
 * События игрового экрана (ARCHITECTURE.md §4; ITERATION_6_DESIGN.md, §4.3, I6-D5).
 *
 * Каждое намерение перестановки адресует карточку **только** `cardId` (I6-D3): индексы
 * «откуда» устаревают между кадрами жеста, а идентификатор — нет.
 *
 * Жестовые события несут [DragGestureId] — идентификатор конкретного жеста. Он выдаётся
 * UI один раз в `onDragStart` и передаётся неизменным во все события этого жеста, чтобы
 * ViewModel могла отличить продолжение текущего жеста от запоздавшего события уже
 * несуществующего (I6-D9). Прежних жестовых событий итерации 3 — пары индексов
 * «откуда/куда» и завершения без идентификатора жеста — больше нет.
 */
sealed interface PuzzleEvent {

    /** Захват карточки за ручку. */
    data class DragStarted(val cardId: String, val gesture: DragGestureId) : PuzzleEvent

    /** Пересечён порог перестановки: цель — **абсолютный** индекс, поэтому повтор безвреден. */
    data class DragMovedTo(
        val cardId: String,
        val targetIndex: Int,
        val gesture: DragGestureId,
    ) : PuzzleEvent

    /** И отпускание, и отмена, и потеря указателя: поведение одинаково (I6-D8). */
    data class DragFinished(val cardId: String, val gesture: DragGestureId) : PuzzleEvent

    /** Заполняется в 6C вместе с `DragEducationHint`. */
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
