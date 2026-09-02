package ru.poporyadku.ui.recap

/**
 * Одноразовые эффекты итога дня (ITERATION_3_DESIGN.md, I3-D25).
 *
 * Доставляются через `Channel`, а не `StateFlow`: возврат на Home обязан произойти
 * ровно один раз.
 */
sealed interface DayRecapEffect {
    data object NavigateHome : DayRecapEffect
}
