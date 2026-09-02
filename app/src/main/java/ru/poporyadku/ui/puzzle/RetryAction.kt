package ru.poporyadku.ui.puzzle

import ru.poporyadku.domain.usecase.Submission

/**
 * Что делает «Повторить» (ITERATION_3_DESIGN.md, I3-D44).
 *
 * Намерение повтора — часть состояния экрана, а не приватный `Boolean` «повторяем
 * загрузку или отправку» во ViewModel: обработчик `RetryClicked` не ветвится по фазе,
 * он читает это поле.
 */
sealed interface RetryAction {

    /** Повторить загрузку головоломки: `GetPuzzleUseCase(date, slot)`. */
    data object Reload : RetryAction

    /**
     * Повторить отправку: `SubmitAnswerUseCase(date, slot, submission)`.
     *
     * Отправляется **та же** [submission], а не пересобранная из текущего состояния:
     * `Answer` повторяет исходный порядок, `Skip` остаётся пропуском.
     */
    data class Resubmit(val submission: Submission) : RetryAction

    /** Повторять нечего — экран уходит на Home (`InvalidRoute`, `NoAssignment`, `SetNotFound`). */
    data object None : RetryAction
}
