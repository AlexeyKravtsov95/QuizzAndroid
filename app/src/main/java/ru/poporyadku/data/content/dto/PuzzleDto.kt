package ru.poporyadku.data.content.dto

import kotlinx.serialization.Serializable

/**
 * Объект головоломки формата (ITERATION_4_DESIGN.md, §5.3; `CONTENT_MODEL.md` §4).
 *
 * [category], [sortKey], [sortDirection] и [volatility] объявлены **строками**, а не
 * перечислениями Kotlin, намеренно (**§6 задания, §7.3 дизайна**): неизвестный токен
 * обязан дойти до защитной проверки `D02` и получить точный код, а не превратиться
 * в общий `R01` во время десериализации.
 *
 * [packId] и [contentVersion] в объекте отсутствуют по формату и берутся из манифеста
 * (**I4-D15**). [volatility], [verifiedAt], [verifiedBy] есть в формате и **не**
 * переносятся в Room (**I4-D16**).
 */
@Serializable
data class PuzzleDto(
    val puzzleId: String,
    val category: String,
    val prompt: String,
    val sortKey: String,
    val sortDirection: String,
    val directionLabel: String,
    val cards: List<CardDto>,
    val correctOrder: List<String>,
    val explanation: String,
    val sources: List<SourceDto>,
    val volatility: String,
    val difficulty: Int,
    val verifiedAt: String,
    val verifiedBy: String,
    /** Обязательное nullable-поле: «забыли» и «не отозвана» — одно состояние (§5.3). */
    val retiredIn: Int?,
)
