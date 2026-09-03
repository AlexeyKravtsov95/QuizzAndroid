package ru.poporyadku.data.content.dto

import kotlinx.serialization.Serializable

/**
 * Один набор (ITERATION_4_DESIGN.md, §4.3).
 *
 * Длина [puzzleIds] схемой зафиксирована ровно тройкой; защитная проверка формы набора
 * живёт в `ContentValidator` и в мапперах, а не в форме DTO: список произвольной длины
 * даёт точный код правила, а не общий `R01`.
 */
@Serializable
data class DailySetDto(
    val setIndex: Int,
    val puzzleIds: List<String>,
)
