package ru.poporyadku.domain.scoring

/**
 * Пара «текущая / лучшая» серия (ITERATION_3_DESIGN.md, I3-D10).
 *
 * `best >= current` выполняется по построению [StreakCalculator]: текущая серия —
 * один из отрезков, по которым считается лучшая. Это же требует `require`
 * в `UserPreferencesRepository.updateStreakCache`.
 */
data class Streaks(val current: Int, val best: Int)
