package ru.poporyadku.domain.model

import java.time.LocalDate

/**
 * Строка архива — один сыгранный день (ITERATION_5_DESIGN.md, §3.3, §4.1, I5-D3, I5-D6).
 *
 * Источник — `day_results ⋈ day_assignments` по `local_date`. Все поля проверены при
 * отображении строки базы (`ArchiveRepositoryImpl`): значения вне диапазонов не
 * обрезаются, а бросают исключение до построения модели (§3.5, п. 7).
 */
data class ArchiveDay(
    val localDate: LocalDate,
    /** `set_index + 1` назначения этой даты, ≥ 1. */
    val dayNumber: Int,
    /** 0..18; у незавершённого дня — частичный счёт. */
    val totalScore: Int,
    /** 1..3 — закрытые слоты; пропуск считается закрытым слотом с нулём. */
    val completedCount: Int,
    /** Ровно `completedCount == 3`. */
    val isComplete: Boolean,
)
