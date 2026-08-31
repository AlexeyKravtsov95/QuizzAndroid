package ru.poporyadku.core.model

import java.time.LocalDate

// ARCHITECTURE.md, §3 (таблица `day_results`); D-5: пересчитывается из puzzle_attempts,
// а не инкрементируется.
data class DayResult(
    val localDate: LocalDate,
    val totalScore: Int,
    val completedCount: Int,
    val isComplete: Boolean,
    val completedAt: Long?,
)
