package ru.poporyadku.domain.usecase

import ru.poporyadku.core.model.PuzzleAttempt

/**
 * Чем именно закрыт слот (ITERATION_3_DESIGN.md, I3-D45).
 *
 * Классификация читается ИЗ СОХРАНЁННОЙ записи `puzzle_attempts`, то есть из
 * единственного источника истины, а не из намерения вызывающего. Поэтому она одинакова
 * во всех входах: успешная запись, повтор, проигранная гонка, восстановление процесса
 * и прямое открытие маршрута.
 */
enum class AttemptKind {
    /** `submittedOrder` непуст — есть что показать на экране результата. */
    Answered,

    /** `submittedOrder` пуст — головоломка была пропущена, показывать нечего. */
    Skipped;

    companion object {
        fun of(attempt: PuzzleAttempt): AttemptKind =
            if (attempt.submittedOrder.isEmpty()) Skipped else Answered
    }
}
