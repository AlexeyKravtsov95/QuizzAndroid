package ru.poporyadku.domain.model

import java.time.LocalDate

/**
 * Сводка последнего завершённого дня (ITERATION_3_DESIGN.md, I3-D34, I3-D35).
 *
 * Существует только тогда, когда все три поля прочитаны из реальных данных: день есть
 * в `day_results`, и на его дату есть назначение, дающее номер дня. Если чего-то нет —
 * сводки нет вовсе (`null`), а не сводка с выдуманной датой и «0 из 18».
 */
data class CompletedDaySummary(
    val localDate: LocalDate,
    /** `setIndex + 1` назначения этой даты. */
    val dayNumber: Int,
    /** 0..18. */
    val totalScore: Int,
)
