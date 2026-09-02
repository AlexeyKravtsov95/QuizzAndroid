package ru.poporyadku.ui.home

import java.time.LocalDate

/**
 * Одноразовые эффекты Home (ITERATION_3_DESIGN.md, I3-D25).
 *
 * Доставляются через `Channel`, а не `StateFlow`: навигация обязана произойти ровно
 * один раз, а `StateFlow` переиграл бы последнее значение при повторной подписке.
 */
sealed interface HomeEffect {

    /** `SessionStart.Started` — та же дата и тот же слот, что вернул use case. */
    data class NavigateToPuzzle(val slotIndex: Int, val date: LocalDate) : HomeEffect

    /** Итог конкретного дня: сессионной даты либо последнего завершённого дня. */
    data class NavigateToRecap(val date: LocalDate) : HomeEffect

    /** CTA состояния `ContentExhausted`. */
    data object NavigateToArchive : HomeEffect
}
