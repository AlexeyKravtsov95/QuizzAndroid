package ru.poporyadku.domain.repository

import java.time.LocalDate
import ru.poporyadku.core.model.DayResult
import ru.poporyadku.core.model.PuzzleAttempt

// ITERATION_2_DESIGN.md, D-5 / D-17: запись попытки и пересчёт day_results — одна
// транзакция; доменные диапазоны валидируются реализацией до записи.
interface ProgressRepository {
    suspend fun recordAttempt(attempt: PuzzleAttempt)

    suspend fun getDayResult(localDate: LocalDate): DayResult?

    suspend fun getDayResults(from: LocalDate, to: LocalDate): List<DayResult>
}
