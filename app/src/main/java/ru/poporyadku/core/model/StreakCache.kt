package ru.poporyadku.core.model

import java.time.LocalDate

// ITERATION_2_DESIGN.md, D-7 / D-18: тройка кэша серии — одно значение, а не три
// независимых поля. Несогласованная тройка читается как EMPTY целиком.
data class StreakCache(
    val current: Int,
    val best: Int,
    val date: LocalDate?,
) {
    companion object {
        val EMPTY = StreakCache(current = 0, best = 0, date = null)
    }
}
