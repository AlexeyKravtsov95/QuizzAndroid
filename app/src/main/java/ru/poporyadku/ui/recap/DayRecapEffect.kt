package ru.poporyadku.ui.recap

import java.time.LocalDate

/**
 * Одноразовые эффекты итога дня (ITERATION_3_DESIGN.md, I3-D25;
 * ITERATION_5_DESIGN.md, §4.3).
 *
 * Доставляются через `Channel`, а не `StateFlow`: переход обязан произойти ровно один раз.
 */
sealed interface DayRecapEffect {
    /** Сессионный вариант: существующий Home, `popBackStack(HOME, false)`. */
    data object NavigateHome : DayRecapEffect

    /** Архивный вариант: `popBackStack()` к архиву, лежащему непосредственно ниже. */
    data object NavigateBack : DayRecapEffect

    /** Только архив и только `Played`: исторический результат этого слота (I5-D10). */
    data class OpenResult(val slotIndex: Int, val localDate: LocalDate) : DayRecapEffect
}
